package myos.better;

import blue.Contracts;
import blue.Language;
import blue.LanguageDocument;
import blue.NativeAdmission;
import blue.NativeChannel;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Test-only signed channel; no test key or permissive verifier is shipped in the service. */
final class NativeAdmissionFixture {
  static final String NAMESPACE = "test-host/one";
  static final Language LANGUAGE =
      Language.newBuilder().mount("Contracts", Contracts.publication()).build();
  static final LanguageDocument TYPE = LANGUAGE.defineYaml("type: Contracts/Channel");
  static final LanguageDocument SEMANTICS =
      LANGUAGE.readYaml("description: Host scoped HMAC test protocol, edition 1.");
  static final Map<String, Object> EVENT = Map.of("kind", "Tick");
  static final String SOURCE =
      """
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
          .formatted(TYPE.blueId());

  final AtomicInteger verifications = new AtomicInteger();
  final CanonicalRuntime runtime;

  NativeAdmissionFixture(String edition) {
    runtime =
        CanonicalRuntime.newBuilder()
            .edition(edition)
            .nativeChannel(
                NativeChannel.newBuilder(TYPE, SEMANTICS, "hostpermit")
                    .counter("signature", 7)
                    .verifyAdmission(this::verify)
                    .accept(context -> context.channel().equals(context.admission().get("channel")))
                    .build())
            .build();
  }

  Core core(AtomicStore store, String namespace) {
    return new Core(
        store,
        RuntimeCatalog.single(runtime),
        Core.ProjectionPolicy.BOUNDED,
        SessionCache.Policy.STANDARD,
        ReadBudget.Limits.STANDARD,
        true,
        namespace);
  }

  private Map<String, Object> verify(NativeAdmission context) {
    verifications.incrementAndGet();
    context.charge("signature", 1);
    NativeAdmission.Receiver receiver = context.receivers().getFirst();
    byte[] expected =
        sign(
            message(
                NativeInput.admissionScope(),
                context.snapshotIdentity(),
                context.inputId().value(),
                context.eventId().value(),
                context.timestamp(),
                receiver.channel(),
                receiver.generation()));
    byte[] supplied =
        Base64.getDecoder().decode((String) ((Map<?, ?>) context.evidence()).get("signature"));
    if (!MessageDigest.isEqual(expected, supplied)) context.reject("Invalid scoped permit");
    return Map.of("channel", receiver.channel(), "input", context.inputId().value());
  }

  static String snapshot(AtomicStore store, String account, String document) {
    return (String) new Repository(store).load(account, document).checkpoint().get("identity");
  }

  static Map<String, Object> permit(
      AtomicStore store,
      String namespace,
      String account,
      String document,
      String command,
      Object event,
      long timestamp) {
    return permitIdentity(
        store,
        namespace,
        account,
        document,
        command,
        LANGUAGE.readJson(JsonText.of(event)).blueId().value(),
        timestamp);
  }

  static Map<String, Object> permitIdentity(
      AtomicStore store,
      String namespace,
      String account,
      String document,
      String command,
      String payload,
      long timestamp) {
    // Independently construct the protocol's typed scope; ambiguous string concatenation is
    // forbidden.
    String scope =
        "myos.native-input/1:"
            + Json.hash(
                Json.bytes(
                    Map.of(
                        "format",
                        "myos.native-input/1",
                        "namespace",
                        namespace,
                        "account",
                        account,
                        "document",
                        document,
                        "command",
                        command)));
    String input = payload;
    return Map.of(
        TYPE.blueId().value(),
        Map.of(
            "signature",
            Base64.getEncoder()
                .encodeToString(
                    sign(
                        message(
                            scope,
                            snapshot(store, account, document),
                            input,
                            payload,
                            timestamp,
                            "input",
                            1)))));
  }

  private static byte[] message(
      String scope,
      String snapshot,
      String input,
      String event,
      long timestamp,
      String channel,
      long generation) {
    return Json.bytes(
        List.of(
            SEMANTICS.blueId().value(),
            scope,
            TYPE.blueId().value(),
            snapshot,
            input,
            event,
            timestamp,
            channel,
            generation));
  }

  private static byte[] sign(byte[] message) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(
          new SecretKeySpec(
              "test-only-host-admission-key".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return mac.doFinal(message);
    } catch (GeneralSecurityException failure) {
      throw new AssertionError(failure);
    }
  }
}
