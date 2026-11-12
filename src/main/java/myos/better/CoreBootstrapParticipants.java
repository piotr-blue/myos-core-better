package myos.better;

import blue.LanguageView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** Pure participant selection with the same verified-email and membership fences as ownership. */
final class CoreBootstrapParticipants {
  private final AtomicStore store;
  private final String initiator;
  private final Map<AtomicStore.Key, Long> observed = new LinkedHashMap<>();
  private boolean selectedByEmail;
  private final boolean provision;
  private final Map<String, String> pending = new TreeMap<>();

  CoreBootstrapParticipants(AtomicStore store) {
    this(store, null);
  }

  CoreBootstrapParticipants(AtomicStore store, String initiator) {
    this(store, initiator, false);
  }

  CoreBootstrapParticipants(AtomicStore store, String initiator, boolean provision) {
    this.store = store;
    this.initiator = initiator;
    this.provision = provision;
  }

  String account(Map<String, Object> locator) {
    String account =
        locator.containsKey("accountId")
            ? Repository.identifier(
                RuntimeConfiguration.text(locator.get("accountId"), "accountId"))
            : null;
    if (locator.containsKey("email")) {
      String selected = email(CoreUserIdentities.email(locator.get("email")));
      if (account != null && !account.equals(selected))
        throw problem(
            422, "PARTICIPANT_MISMATCH", "Email and account select different participants.");
      account = selected;
    }
    if (account == null)
      throw problem(422, "MISSING_MAPPING", "Provide a participant accountId or email.");
    var workspace = read(CoreWorkspaces.accountKey(account, "ACCOUNT"));
    if (workspace == null && !account.equals(initiator) && !pending.containsValue(account)
        || workspace != null
            && !"ACTIVE".equals(workspace.value().getOrDefault("status", "ACTIVE")))
      throw problem(
          422, "BOOTSTRAP_RECIPIENT_UNAVAILABLE", "A selected participant account is unavailable.");
    if (locator.containsKey("representativeId")) {
      String user =
          Repository.identifier(
              RuntimeConfiguration.text(locator.get("representativeId"), "representativeId"));
      var member = read(CoreWorkspaces.accountKey(account, "MEMBER#" + user));
      if (member == null ? !user.equals(account) : !"ACTIVE".equals(member.value().get("status")))
        throw problem(
            403, "membership_required", "Representative must be an active account member.");
      if (read(CoreWorkspaces.accountKey(user, "IDENTITY")) == null)
        throw problem(422, "representative_unavailable", "Representative identity is unavailable.");
    }
    return account;
  }

  private String email(String email) {
    selectedByEmail = true;
    read(CoreUserIdentities.emailKey(email));
    var owners = new TreeSet<String>();
    for (String user : new CorePublicDirectory(store).identityCandidates(email)) {
      var identity = read(CoreWorkspaces.accountKey(user, "IDENTITY"));
      if (identity == null || !email.equals(identity.value().get("email"))) continue;
      var pointer = read(new AtomicStore.Key("CORE_OWNERSHIP", "USER#" + user));
      String account = pointer == null ? user : (String) pointer.value().get("account");
      if (account == null || account.isEmpty()) continue;
      var membership = read(CoreWorkspaces.accountKey(account, "MEMBER#" + user));
      if (membership != null
          && (!"ACTIVE".equals(membership.value().get("status"))
              || !"OWNER".equals(membership.value().get("role")))) continue;
      if (membership == null && !account.equals(user)) continue;
      var workspace = read(CoreWorkspaces.accountKey(account, "ACCOUNT"));
      if (workspace == null || !"ACTIVE".equals(workspace.value().getOrDefault("status", "ACTIVE")))
        throw problem(409, "recipient_unavailable", "Recipient account is unavailable.");
      owners.add(account);
    }
    if (owners.size() > 1)
      throw problem(
          409,
          "recipient_identity_ambiguous",
          "This email has conflicting owners; select an accountId.");
    if (owners.isEmpty()) {
      var reservation = read(CorePendingRecipients.key(email));
      if (reservation != null && "PENDING".equals(reservation.value().get("status")))
        return (String) reservation.value().get("account");
      if (reservation != null && !"CLAIMED".equals(reservation.value().get("status")))
        throw problem(409, "recipient_unavailable", "The reserved recipient ownership changed.");
      if (!provision)
        throw problem(422, "recipient_not_found", "No owned account is registered for this email.");
      String account =
          CorePendingRecipients.account(
              email, reservation == null ? 1L : Math.incrementExact(reservation.version()));
      if (read(CoreWorkspaces.accountKey(account, "ACCOUNT")) != null
          || read(CoreWorkspaces.accountKey(account, "RUNTIME")) != null
          || read(new AtomicStore.Key("CORE_OWNERSHIP", "ACCOUNT#" + account)) != null
          || read(CoreInvitations.outboxKey(CorePendingRecipients.outbox(account))) != null)
        throw problem(409, "recipient_unavailable", "The recipient reservation is inconsistent.");
      pending.put(email, account);
      return account;
    }
    return owners.getFirst();
  }

  Map<String, String> pending() {
    return Map.copyOf(pending);
  }

  List<Object> checks() {
    return observed.entrySet().stream()
        .map(
            entry ->
                (Object)
                    Map.of(
                        "partition",
                        entry.getKey().partition(),
                        "sort",
                        entry.getKey().sort(),
                        "version",
                        entry.getValue()))
        .toList();
  }

  boolean selectedByEmail() {
    return selectedByEmail;
  }

  /** An authored public key selects existing live account custody; it cannot grant ownership. */
  String readerKey(String account, LanguageView keys) {
    if (!keys.exists()) return null;
    if (!"list".equals(keys.kind()) || keys.size() < 1 || keys.size() > 16)
      throw problem(
          422, "reader_keys_required", "Supply one to sixteen alternative public reader keys.");
    for (int i = 0; i < keys.size(); i++) {
      String value = keys.at("/items/" + i).text();
      if (!value.matches("[a-f0-9]{64}"))
        throw problem(422, "reader_keys_required", "Use Ed25519 public reader keys.");
      var index = read(CoreReaderKeys.publicIndex(account, value));
      if (index == null) continue;
      String id = (String) index.value().get("id");
      var reader = read(CoreReaderKeys.key(account, id));
      if (reader != null
          && !Boolean.TRUE.equals(reader.value().get("deleted"))
          && account.equals(reader.value().get("accountId"))
          && value.equals(reader.value().get("publicKey"))) return id;
    }
    throw problem(
        422,
        "reader_key_unavailable",
        "A declared reader key must be active and owned by its account.");
  }

  static Map<String, Object> locator(LanguageView party) {
    var result = new LinkedHashMap<String, Object>();
    for (String field :
        List.of("myosAccountId", "accountId", "email", "representativeId", "agentId")) {
      var value = party.at("/" + field);
      if (!value.exists()) continue;
      String name = field.equals("myosAccountId") ? "accountId" : field;
      Object previous = result.put(name, value.text());
      if (previous != null && !previous.equals(value.text()))
        throw problem(422, "PARTICIPANT_MISMATCH", "Authored account identities differ.");
    }
    return result;
  }

  private AtomicStore.Row read(AtomicStore.Key key) {
    var row = store.get(key);
    long version = row == null ? 0 : row.version();
    Long previous = observed.putIfAbsent(key, version);
    if (previous != null && previous != version)
      throw problem(
          409, "recipient_resolution_changed", "Participant identity changed during preparation.");
    if (observed.size() > 90)
      throw problem(
          422,
          "participant_resolution_limit",
          "Participant resolution exceeds one bounded acceptance transaction.");
    return row;
  }

  static void accept(
      AtomicStore store, String account, String command, Map<String, Object> prepared) {
    var writes = new ArrayList<AtomicStore.Write>();
    var checks = new ArrayList<AtomicStore.Check>();
    stage(store, account, command, prepared, writes, checks);
    if (writes.isEmpty() || store.commit(writes, checks)) return;
    // A lost success response or another worker may already have accepted this same selection.
    writes.clear();
    checks.clear();
    stage(store, account, command, prepared, writes, checks);
    if (writes.isEmpty()) return;
    throw problem(
        409,
        "recipient_resolution_changed",
        "Participant ownership or membership changed; prepare the bootstrap again.");
  }

  /** Compose recipient authority with the durable job acceptance, never after it. */
  static void stage(
      AtomicStore store,
      String account,
      String command,
      Map<String, Object> prepared,
      List<AtomicStore.Write> writes,
      List<AtomicStore.Check> conditions) {
    if (!(prepared.get("participantChecks") instanceof List<?> checks) || checks.isEmpty()) return;
    String hash = Json.hash(Json.bytes(prepared));
    var key =
        new AtomicStore.Key(
            "CORE_BOOTSTRAP_PARTICIPANTS#" + Repository.identifier(account),
            Repository.identifier(command));
    var before = store.get(key);
    if (before != null) {
      if (!hash.equals(before.value().get("fingerprint")))
        throw problem(409, "BOOTSTRAP_CONFLICT", "Bootstrap participant selection changed.");
      return;
    }
    var expected = new LinkedHashMap<AtomicStore.Key, Long>();
    conditions.forEach(check -> expected.put(check.key(), check.version()));
    checks.forEach(
        raw -> {
          var row = RuntimeConfiguration.object(raw, "participant check");
          var selected =
              new AtomicStore.Key((String) row.get("partition"), (String) row.get("sort"));
          long version = Repository.number(row.get("version"));
          Long old = expected.putIfAbsent(selected, version);
          if (old != null && old != version)
            throw problem(
                409,
                "recipient_resolution_changed",
                "Participant identity changed before acceptance.");
        });
    CorePendingRecipients.stage(prepared, writes, expected);
    if (expected.size() + writes.size() > 95)
      throw problem(
          422,
          "participant_resolution_limit",
          "Participant resolution exceeds one bounded acceptance transaction.");
    conditions.clear();
    expected.forEach(
        (selected, version) -> conditions.add(new AtomicStore.Check(selected, version)));
    writes.add(AtomicStore.Write.put(key, 0, Map.of("fingerprint", hash)));
  }

  private static ConsoleProblem problem(int status, String code, String message) {
    return new ConsoleProblem(status, code, message);
  }
}
