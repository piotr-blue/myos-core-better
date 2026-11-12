package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class WebhookSecretsTest {
  @Test
  void nativeSignatureMatchesAnIndependentHmacVector() {
    byte[] body =
        "{\"id\":\"evt_example\",\"type\":\"CONTENT_CREATED\"}".getBytes(StandardCharsets.UTF_8);
    assertEquals(
        "t=2026-11-07T00:00:00Z,v1=bd1e4148abbd68ef733ab40141c45203347a3ca7b05343487cd114438bbc1211",
        WebhookSecrets.signature("whsec_native-vector", "2026-11-07T00:00:00Z", body));
    assertNotEquals(
        WebhookSecrets.signature("a", "time", body), WebhookSecrets.signature("b", "time", body));
  }

  @Test
  void envelopesSurviveRestartButCannotMoveBetweenAccountsOrReceiptKinds() {
    byte[] key = new byte[32];
    Arrays.fill(key, (byte) 9);
    var secrets = new WebhookSecrets(key);
    String value = WebhookSecrets.generate(), context = "alice/hook/one";
    String first = secrets.seal(value, context), second = secrets.seal(value, context);
    assertNotEquals(first, second);
    assertEquals(value, new WebhookSecrets(key).open(first, context));
    for (String other : List.of("bob/hook/one", "alice/receipt/one", "alice/hook/two"))
      assertThrows(IllegalStateException.class, () -> secrets.open(first, other));
    byte[] broken = Base64.getDecoder().decode(first);
    broken[broken.length - 1] ^= 1;
    assertThrows(
        IllegalStateException.class,
        () -> secrets.open(Base64.getEncoder().encodeToString(broken), context));
    assertThrows(
        IllegalStateException.class, () -> new WebhookSecrets(new byte[32]).open(first, context));
    assertThrows(IllegalStateException.class, () -> secrets.open("invalid", context));
    assertThrows(IllegalArgumentException.class, () -> new WebhookSecrets(new byte[31]));
    assertNull(WebhookSecrets.configured(Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> WebhookSecrets.configured(Map.of("MYOS_WEBHOOK_SECRET_ARN", "missing-version")));
  }

  @Test
  void deploymentReadsOnlyItsPinnedVersionAndRejectsMalformedSecretsWithoutEchoingThem() {
    var env =
        Map.of(
            "MYOS_WEBHOOK_SECRET_ARN",
            "arn:aws:secretsmanager:eu-west-1:581571886327:secret:callback-test",
            "MYOS_WEBHOOK_SECRET_VERSION",
            "00000000-0000-0000-0000-000000000000");
    String seed = "a".repeat(64);
    SigningSecret.Source source =
        ref -> {
          assertEquals(env.get("MYOS_WEBHOOK_SECRET_ARN"), ref.arn());
          assertEquals(env.get("MYOS_WEBHOOK_SECRET_VERSION"), ref.version());
          return JsonText.of(Map.of("format", "myos.better.webhook-key/1", "seed", seed));
        };
    var key = WebhookSecrets.configured(env, source);
    String sealed = key.seal("callback-value", "account/hook");
    assertEquals(
        "callback-value", WebhookSecrets.configured(env, source).open(sealed, "account/hook"));
    var bad =
        assertThrows(
            IllegalStateException.class,
            () ->
                WebhookSecrets.configured(
                    env, ref -> JsonText.of(Map.of("format", "wrong", "seed", seed))));
    assertFalse(bad.toString().contains(seed));
    assertNull(bad.getCause());
  }
}
