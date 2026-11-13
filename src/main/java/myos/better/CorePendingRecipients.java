package myos.better;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** A reserved recipient account has no authenticated owner until a verified login claims it. */
final class CorePendingRecipients {
  static AtomicStore.Key key(String email) {
    return new AtomicStore.Key(
        "CORE_PENDING_RECIPIENTS", Json.hash(Json.bytes(CoreUserIdentities.email(email))));
  }

  static String account(String email) {
    return account(email, 1L);
  }

  static String account(String email, long generation) {
    return "account-recipient-"
        + Json.hash(Json.bytes(List.of(CoreUserIdentities.email(email), generation)))
            .substring(0, 40);
  }

  static String outbox(String account) {
    return "signup-" + account;
  }

  static boolean pending(AtomicStore store, String email) {
    var row = store.get(key(email));
    return row != null && "PENDING".equals(row.value().get("status"));
  }

  /** Staged with recipient fences and command acceptance; never during pure preparation. */
  static void stage(
      Map<String, Object> prepared,
      List<AtomicStore.Write> writes,
      Map<AtomicStore.Key, Long> expected) {
    var pending =
        RuntimeConfiguration.object(
            prepared.getOrDefault("pendingRecipients", Map.of()), "pending recipients");
    for (var entry : pending.entrySet()) {
      String email = CoreUserIdentities.email(entry.getKey()), account = (String) entry.getValue();
      long generation = Math.incrementExact(Objects.requireNonNull(expected.get(key(email))));
      if (!account.equals(account(email, generation))) {
        throw new IllegalStateException("Invalid reserved recipient");
      }
      put(
          writes,
          expected,
          key(email),
          Map.of(
              "format",
              1,
              "account",
              account,
              "email",
              email,
              "status",
              "PENDING",
              "generation",
              generation));
      put(
          writes,
          expected,
          CoreWorkspaces.accountKey(account, "ACCOUNT"),
          Map.of(
              "kind",
              "PERSONAL",
              "profile",
              Map.of("displayName", email),
              "status",
              "ACTIVE",
              "activeAdminCount",
              0L,
              "membershipRevision",
              0L,
              "pendingRecipient",
              true));
      put(
          writes,
          expected,
          new AtomicStore.Key("CORE_OWNERSHIP", "ACCOUNT#" + account),
          Map.of("format", 1, "user", ""));
      var runtime =
          RuntimeConfiguration.object(
              prepared.getOrDefault("recipientRuntime", Map.of()), "recipient runtime");
      var frozenRuntime = new LinkedHashMap<String, Object>();
      frozenRuntime.put("selection", runtime);
      frozenRuntime.put("origin", "bootstrap");
      if (OwnerRecords.EDITION.equals(runtime.get("hostExecution"))) {
        frozenRuntime.put("initialGas", OwnerGas.initialAllocation());
      }
      put(writes, expected, CoreWorkspaces.accountKey(account, "RUNTIME"), frozenRuntime);
      put(writes, expected, CoreUserIdentities.emailKey(email), Map.of("format", 1));
      var delivery = new LinkedHashMap<String, Object>();
      String id = outbox(account);
      delivery.put("invitationId", id);
      delivery.put("purpose", "SIGNUP");
      delivery.put("accountId", account);
      delivery.put("recipientEmail", email);
      delivery.put("status", "PENDING");
      delivery.put("attempts", 0L);
      delivery.put("availableAt", 0L);
      delivery.put("work", "CORE_SIGNUP_DELIVERY");
      delivery.put("position", id);
      put(writes, expected, CoreInvitations.outboxKey(id), delivery);
    }
  }

  /** Account entitlement is installed before native initialization, once across retries. */
  static void provision(AtomicStore store, String account) {
    var runtime = store.get(CoreWorkspaces.accountKey(account, "RUNTIME"));
    if (runtime == null
        || !"bootstrap".equals(runtime.value().get("origin"))
        || !runtime.value().containsKey("initialGas")) {
      return;
    }
    var allocation = RuntimeConfiguration.object(runtime.value().get("initialGas"), "initial gas");
    for (int attempt = 0; attempt < 8; attempt++) {
      var tx = new OwnerMetadata(store, account).begin();
      new OwnerGas(account).provision(tx, allocation);
      tx.publish(List.of(), List.of(new AtomicStore.Check(runtime.key(), runtime.version())));
      if (tx.commit()) {
        return;
      }
    }
    throw new ConsoleProblem(
        503, "recipient_provisioning_busy", "Retry recipient runtime provisioning.");
  }

  private static void put(
      List<AtomicStore.Write> writes,
      Map<AtomicStore.Key, Long> expected,
      AtomicStore.Key key,
      Map<String, Object> value) {
    Long version = expected.remove(key);
    if (version == null) {
      throw new IllegalStateException("Unobserved recipient mutation");
    }
    writes.add(AtomicStore.Write.put(key, version, value));
  }
}
