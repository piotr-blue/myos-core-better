package myos.better;

import blue.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.security.interfaces.EdECPublicKey;
import java.util.*;

/** Explicit one-time provisioning utility. The service never generates a replacement identity. */
public final class ProviderIdentityTool {
  private ProviderIdentityTool() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 2)
      throw new IllegalArgumentException(
          "Expected HTTPS provider endpoint and new private output file");
    java.net.URI endpoint = java.net.URI.create(args[0]);
    if (!"https".equals(endpoint.getScheme())
        || endpoint.getHost() == null
        || endpoint.getRawUserInfo() != null
        || endpoint.getQuery() != null
        || endpoint.getFragment() != null)
      throw new IllegalArgumentException("HTTPS provider endpoint required");
    KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    var point = ((EdECPublicKey) keys.getPublic()).getPoint();
    byte[] big = point.getY().toByteArray(), encoded = new byte[32];
    for (int i = 0; i < Math.min(big.length, encoded.length); i++)
      encoded[i] = big[big.length - 1 - i];
    if (point.isXOdd()) encoded[31] |= (byte) 0x80;
    Language language =
        Language.newBuilder().mount("Coordination", ProviderIdentity.coordination()).build();
    LanguageDocument provider =
        language.readYaml(
            JsonText.of(
                Map.of(
                    "type",
                    "Coordination/Timeline Provider",
                    "endpoint",
                    args[0],
                    "publicKey",
                    HexFormat.of().formatHex(encoded))));
    String secret =
        JsonText.of(
            Map.of(
                "format",
                ProviderIdentity.FORMAT,
                "provider",
                ProviderSigning.wire(provider),
                "privateKeyPkcs8",
                Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded())));
    ProviderIdentity.decode(secret, provider.blueId(), args[0]);
    Path file = Path.of(args[1]);
    Files.createFile(
        file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    Files.writeString(file, secret);
    System.out.println(
        JsonText.of(
            Map.of(
                "providerId",
                provider.blueId().value(),
                "provider",
                ProviderSigning.wire(provider))));
  }
}
