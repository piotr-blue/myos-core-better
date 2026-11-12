package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import blue.*;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProviderIdentityTest {
  static String secret(TimelineFixture protocol) throws Exception {
    byte[] seed =
        HexFormat.of().parseHex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60");
    PrivateKey key =
        KeyFactory.getInstance("Ed25519")
            .generatePrivate(new EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed));
    return JsonText.of(
        Map.of(
            "format",
            ProviderIdentity.FORMAT,
            "provider",
            ProviderSigning.wire(protocol.provider),
            "privateKeyPkcs8",
            Base64.getEncoder().encodeToString(key.getEncoded())));
  }

  @Test
  void configuredIdentityIsExactAndRefusesReplacementOrLeakingSecretDiagnostics() throws Exception {
    TimelineFixture f = new TimelineFixture();
    String source = secret(f);
    ProviderSigning signing =
        ProviderIdentity.decode(source, f.provider.blueId(), "https://provider.example");
    assertEquals(f.provider.blueId(), signing.provider().blueId());
    String key =
        (String)
            Json.object(source.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .get("privateKeyPkcs8");
    String wrong =
        source.replace(
            key,
            Base64.getEncoder()
                .encodeToString(
                    KeyPairGenerator.getInstance("Ed25519")
                        .generateKeyPair()
                        .getPrivate()
                        .getEncoded()));
    for (String value :
        List.of(
            wrong,
            source + " private-secret",
            "{\"privateKeyPkcs8\":\"not-a-key\"}",
            "x".repeat(16385))) {
      IllegalArgumentException failure =
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  ProviderIdentity.decode(value, f.provider.blueId(), "https://provider.example"));
      assertEquals("Invalid pinned provider identity or signing key", failure.getMessage());
      assertNull(failure.getCause());
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> ProviderIdentity.decode(source, f.timeline.blueId(), "https://provider.example"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ProviderIdentity.decode(source, f.provider.blueId(), "https://other.example"));
  }

  @Test
  void partialConfigurationFailsBeforeAnyRemoteRead() {
    assertNull(ProviderIdentity.configured(Map.of(), "https://console.example"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ProviderIdentity.configured(
                Map.of("MYOS_PROVIDER_ID", "anything"), "https://console.example"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ProviderIdentity.configured(
                Map.of(
                    "MYOS_PROVIDER_SECRET_ARN",
                    "invalid",
                    "MYOS_PROVIDER_SECRET_VERSION",
                    "invalid",
                    "MYOS_PROVIDER_ID",
                    "invalid"),
                "https://console.example"));
  }
}
