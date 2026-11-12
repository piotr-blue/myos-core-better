package myos.better;

import blue.Contracts;
import blue.Language;
import blue.LanguageDocument;
import blue.NativeAdmission;
import blue.NativeChannel;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Explicit signed-ingress qualification on new synthetic parentless documents, never a scan. */
public final class NativeAdmissionProbe {
  private static final String NAMESPACE = "myos.native-qualification/1";
  private static final String PROTOCOL = "qualification.ed25519-input/1";
  private static final Map<String, Object> EVENT = Map.of("kind", "Tick");
  private static final String DOCUMENT = "one";

  public static void main(String[] args) throws Exception {
    if (args.length != 3 || !args[0].matches("native-bench-[A-Za-z0-9._-]{1,80}"))
      throw new IllegalArgumentException(
          "Expected new native-bench-* account, warmups and samples");
    int warmups = Integer.parseInt(args[1]), samples = Integer.parseInt(args[2]);
    if (warmups < 0 || warmups > 64 || samples < 1 || samples > 64)
      throw new IllegalArgumentException("Warmups must be0..64; samples must be1..64");
    String account = args[0], peer = account + "-peer";
    String table = System.getenv("MYOS_TABLE");
    MemoryStore local = new MemoryStore();
    Supplier<AtomicStore> stores =
        () -> table == null ? local : new DynamoStore(table, "eu-west-1");
    Protocol protocol = new Protocol(KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
    Map<String, Object> report = new LinkedHashMap<>();
    List<Double> elapsed = new ArrayList<>(),
        runtime = new ArrayList<>(),
        preparation = new ArrayList<>();
    List<Long> gas = new ArrayList<>();
    Core.Result first;
    Map<String, Object> firstProof;
    Object retained;
    long expected = 1L + warmups + samples;
    MeasuredStore store = new MeasuredStore(stores.get());
    try (Core core = protocol.core(store, NAMESPACE)) {
      for (List<String> target :
          List.of(List.of(account, DOCUMENT), List.of(account, "two"), List.of(peer, DOCUMENT))) {
        require(
            new Repository(store).load(target.get(0), target.get(1)) == null,
            "Target already exists");
        require(
            core.create(target.get(0), target.get(1), "create", protocol.source())
                .status()
                .equals("APPLIED"),
            "Create failed");
      }
      require(
          snapshot(store, account, DOCUMENT).equals(snapshot(store, account, "two")),
          "Different initial document snapshots");
      require(
          snapshot(store, account, DOCUMENT).equals(snapshot(store, peer, DOCUMENT)),
          "Different initial account snapshots");
      firstProof = protocol.permit(store, NAMESPACE, account, DOCUMENT, "first", 1);
      byte[] forged =
          Base64.getDecoder()
              .decode(
                  (String) object(firstProof.get(protocol.type.blueId().value())).get("signature"));
      forged[0] ^= 1;
      Map<String, Object> invalid =
          Map.of(
              protocol.type.blueId().value(),
              Map.of("signature", Base64.getEncoder().encodeToString(forged)));
      reject(core, account, DOCUMENT, "first", 1, invalid);
      reject(core, account, "two", "first", 1, firstProof);
      reject(core, peer, DOCUMENT, "first", 1, firstProof);
      reject(core, account, DOCUMENT, "wrong-command", 1, firstProof);
      reject(core, account, DOCUMENT, "first", 2, firstProof);
      try (Core other = protocol.core(new MeasuredStore(stores.get()), NAMESPACE + "/other")) {
        reject(other, account, DOCUMENT, "first", 1, firstProof);
      }
      refused(
          () -> core.process(account, DOCUMENT, "first", EVENT, 1),
          "Legacy ingress accepted protected input");
      noReceipt(core, account, DOCUMENT, "first");
      first = core.admit(account, DOCUMENT, "first", EVENT, 1, firstProof);
      require(
          first.status().equals("APPLIED") && first.version() == 2,
          "Valid permit failed after rejection");
      require(first.events().equals(List.of(Map.of("kind", "Counted"))), "Wrong first event");
      require(
          first.equals(core.admit(account, DOCUMENT, "first", EVENT, 1, firstProof)),
          "Duplicate result changed");
      retained = core.command(account, DOCUMENT, "first").get("input");
      require(
          object(retained).get("evidence").equals(firstProof), "Original proof was not retained");
      Map<String, Long> before = null;
      for (int i = 0; i < warmups + samples; i++) {
        if (i == warmups) before = core.statistics();
        String command = "tick-" + i;
        long timestamp = i + 2L;
        long start = System.nanoTime();
        Map<String, Object> proof =
            protocol.permit(store, NAMESPACE, account, DOCUMENT, command, timestamp);
        double prepared = (System.nanoTime() - start) / 1e6;
        start = System.nanoTime();
        Core.Result result = core.admit(account, DOCUMENT, command, EVENT, timestamp, proof);
        double millis = (System.nanoTime() - start) / 1e6;
        require(
            result.status().equals("APPLIED") && result.version() == i + 3L,
            "Signed update did not advance exactly once");
        require(
            result.events().equals(List.of(Map.of("kind", "Counted"))),
            "Signed update emitted wrong event");
        require(count(core, account, DOCUMENT) == i + 2L, "Wrong running total");
        if (i >= warmups) {
          preparation.add(prepared);
          elapsed.add(millis);
          runtime.add(result.executionNanos() / 1e6);
          gas.add(result.gas());
        }
      }
      Map<String, Long> counters = difference(core.statistics(), before);
      require(
          counters.get("commit.calls") == samples, "Unexpected successful-path transaction count");
      require(
          counters.get("commit.failures") == 0 && counters.get("commit.retryableFailures") == 0,
          "Storage failure during signed sample");
      report.put("measuredStorage", counters);
      require(count(core, account, DOCUMENT) == expected, "Final count is wrong");
      require(
          count(core, account, "two") == 0 && count(core, peer, DOCUMENT) == 0,
          "Replay modified another target");
      require(
          ((List<?>) core.events(account, DOCUMENT, 0, 100).get("items")).size()
              == Math.min(expected + 1, 100),
          "Wrong retained event page size");
    }
    // Rebuild native registration, host, sessions and SDK client; no warm runtime state is
    // retained.
    Protocol restartedProtocol = new Protocol(protocol.keys);
    try (Core restarted = restartedProtocol.core(new MeasuredStore(stores.get()), NAMESPACE)) {
      require(
          first.equals(restarted.admit(account, DOCUMENT, "first", EVENT, 1, firstProof)),
          "Cold duplicate result changed");
      require(restartedProtocol.verifications.get() == 0, "Cold duplicate unexpectedly executed");
      require(
          retained.equals(restarted.command(account, DOCUMENT, "first").get("input")),
          "Cold original input changed");
      refused(
          () -> restarted.admit(account, DOCUMENT, "first", EVENT, 1, Map.of()),
          "Changed evidence reused command");
      reject(restarted, account, DOCUMENT, "stale", 1, firstProof);
      require(count(restarted, account, DOCUMENT) == expected, "Cold retry changed business state");
      report.put("finalVersion", restarted.head(account, DOCUMENT).get("version"));
      report.put("localBlueId", restarted.head(account, DOCUMENT).get("localBlueId"));
      report.put("retainedInputSha256", Json.hash(Json.bytes(retained)));
    }
    report.put("status", "PASSED");
    report.put("account", account);
    report.put("peerAccount", peer);
    report.put("backend", table == null ? "memory" : "dynamodb");
    report.put("edition", CanonicalRuntime.HOST_LINKS_EDITION);
    report.put("namespace", NAMESPACE);
    report.put("channelTypeBlueId", protocol.type.blueId().value());
    report.put("semanticsBlueId", protocol.semantics.blueId().value());
    report.put("signature", "Ed25519; fresh keypair, public key committed in synthetic semantics");
    report.put("warmups", warmups);
    report.put("samples", samples);
    report.put("count", expected);
    report.put("callMillis", elapsed);
    report.put("runtimeMillis", runtime);
    report.put("preparationMillis", preparation);
    report.put("gas", gas);
    report.put("callMedianMillis", median(elapsed));
    report.put("runtimeMedianMillis", median(runtime));
    report.put("preparationMedianMillis", median(preparation));
    report.put(
        "checked",
        List.of(
            "identical initial snapshots",
            "signature forgery rejected",
            "account/document/command/time/namespace replay rejected",
            "unverified rejection retains no command",
            "legacy ingress cannot poison command",
            "valid same-ID command succeeds",
            "exact events and running totals",
            "original proof retained",
            "cold duplicate does not execute",
            "changed proof conflicts",
            "stale proof leaves state unchanged"));
    System.out.println(JsonText.of(report));
  }

  private static final class Protocol {
    final KeyPair keys;
    final Language language =
        Language.newBuilder().mount("Contracts", Contracts.publication()).build();
    final LanguageDocument type =
        language.defineYaml(
            "type: Contracts/Channel\n"
                + "description: Synthetic signed input channel for qualification.");
    final LanguageDocument semantics;
    final AtomicInteger verifications = new AtomicInteger();

    Protocol(KeyPair keys) {
      this.keys = keys;
      semantics =
          language.readJson(
              JsonText.of(
                  Map.of(
                      "protocol",
                      PROTOCOL,
                      "publicKeyX509",
                      Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()))));
    }

    Core core(AtomicStore store, String namespace) {
      NativeChannel channel =
          NativeChannel.newBuilder(type, semantics, "qualification")
              .counter("signature", 100)
              .verifyAdmission(this::verify)
              .accept(context -> context.channel().equals(context.admission().get("channel")))
              .build();
      CanonicalRuntime runtime =
          CanonicalRuntime.newBuilder()
              .edition(CanonicalRuntime.HOST_LINKS_EDITION)
              .nativeChannel(channel)
              .build();
      return new Core(
          store,
          RuntimeCatalog.single(runtime),
          Core.ProjectionPolicy.BOUNDED,
          new SessionCache.Policy(16, 32L * 1024 * 1024, 16),
          ReadBudget.Limits.STANDARD,
          true,
          namespace);
    }

    String source() {
      return """
      count: 0
      contracts:
        input: {type: {blueId: %s}}
        tick:
          type: Contracts/Sequential Workflow
          channel: input
          steps:
            - type: Contracts/Update Document
              changeset: [{op: replace, path: /count, val: {$add: [{$document: /count}, 1]}}]
            - type: Contracts/Trigger Event
              event: {kind: Counted}
      """
          .formatted(type.blueId());
    }

    Map<String, Object> verify(NativeAdmission input) {
      verifications.incrementAndGet();
      input.charge("signature", 1);
      NativeAdmission.Receiver receiver = input.receivers().getFirst();
      try {
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(keys.getPublic());
        verifier.update(
            message(
                input.snapshotIdentity(),
                input.inputId().value(),
                input.eventId().value(),
                input.timestamp(),
                receiver.channel(),
                receiver.generation()));
        if (!verifier.verify(
            Base64.getDecoder().decode((String) object(input.evidence()).get("signature"))))
          input.reject("Invalid scoped signature");
      } catch (GeneralSecurityException error) {
        input.reject("Invalid signature encoding");
      }
      return Map.of("channel", receiver.channel(), "input", input.inputId().value());
    }

    Map<String, Object> permit(
        AtomicStore store,
        String namespace,
        String account,
        String document,
        String command,
        long timestamp)
        throws GeneralSecurityException {
      String event = language.readJson(JsonText.of(EVENT)).blueId().value();
      String scoped = NativeInput.scopedCommand(namespace, account, document, command);
      String input =
          language
              .readJson(JsonText.of(Map.of("command", scoped, "eventIdentity", event)))
              .blueId()
              .value();
      Signature signer = Signature.getInstance("Ed25519");
      signer.initSign(keys.getPrivate());
      signer.update(
          message(snapshot(store, account, document), input, event, timestamp, "input", 1));
      return Map.of(
          type.blueId().value(),
          Map.of("signature", Base64.getEncoder().encodeToString(signer.sign())));
    }

    byte[] message(
        String snapshot,
        String input,
        String event,
        long timestamp,
        String channel,
        long generation) {
      return Json.bytes(
          List.of(
              PROTOCOL,
              type.blueId().value(),
              semantics.blueId().value(),
              snapshot,
              input,
              event,
              timestamp,
              channel,
              generation));
    }
  }

  private static void reject(
      Core core, String account, String document, String command, long timestamp, Object proof) {
    Map<String, Object> before = core.head(account, document);
    Core.Result rejected = core.admit(account, document, command, EVENT, timestamp, proof);
    require(
        rejected.status().equals("FAILED") && rejected.events().isEmpty(),
        "Forged/replayed input did not fail");
    require(core.head(account, document).equals(before), "Rejected input changed the document");
    noReceipt(core, account, document, command);
  }

  private static void noReceipt(Core core, String account, String document, String command) {
    refused(
        () -> core.command(account, document, command), "Unverified input reserved a command ID");
  }

  private static void refused(Runnable action, String message) {
    try {
      action.run();
    } catch (IllegalArgumentException expected) {
      return;
    }
    throw new IllegalStateException(message);
  }

  private static String snapshot(AtomicStore store, String account, String document) {
    return (String) new Repository(store).load(account, document).checkpoint().get("identity");
  }

  private static long count(Core core, String account, String document) {
    return Repository.number(
        object(core.inspect(account, document, false).get("data")).get("count"));
  }

  private static Map<String, Long> difference(Map<String, Long> after, Map<String, Long> before) {
    Map<String, Long> result = new LinkedHashMap<>();
    after.forEach((key, value) -> result.put(key, value - before.getOrDefault(key, 0L)));
    return result;
  }

  private static double median(List<Double> values) {
    List<Double> sorted = values.stream().sorted().toList();
    int middle = sorted.size() / 2;
    return sorted.size() % 2 == 0
        ? (sorted.get(middle - 1) + sorted.get(middle)) / 2
        : sorted.get(middle);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Object value) {
    return (Map<String, Object>) value;
  }

  private static void require(boolean value, String message) {
    if (!value) throw new IllegalStateException(message);
  }
}
