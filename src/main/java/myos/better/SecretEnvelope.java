package myos.better;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** JCA authenticated encryption with separate domains and resource-bound associated data. */
final class SecretEnvelope {
  private static final SecureRandom RANDOM = new SecureRandom();
  private final SecretKeySpec key;
  private final String domain;

  SecretEnvelope(byte[] master, String domain) {
    if (master.length != 32) throw new IllegalArgumentException("A 256-bit master is required");
    this.key = new SecretKeySpec(master, "AES");
    this.domain = Objects.requireNonNull(domain);
  }

  SecretEnvelope domain(String name) {
    return new SecretEnvelope(key.getEncoded(), name);
  }

  String seal(String plain, String context) {
    byte[] nonce = new byte[12];
    RANDOM.nextBytes(nonce);
    try {
      byte[] encrypted =
          cipher(Cipher.ENCRYPT_MODE, nonce, context)
              .doFinal(plain.getBytes(StandardCharsets.UTF_8));
      byte[] envelope = Arrays.copyOf(nonce, nonce.length + encrypted.length);
      System.arraycopy(encrypted, 0, envelope, nonce.length, encrypted.length);
      return Base64.getEncoder().encodeToString(envelope);
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("Secret envelope could not be sealed");
    }
  }

  String open(String envelope, String context) {
    try {
      byte[] encoded = Base64.getDecoder().decode(envelope);
      if (encoded.length < 28) throw new IllegalArgumentException();
      var cipher = cipher(Cipher.DECRYPT_MODE, Arrays.copyOf(encoded, 12), context);
      return new String(cipher.doFinal(encoded, 12, encoded.length - 12), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException | IllegalArgumentException failure) {
      throw new IllegalStateException("Secret envelope authentication failed");
    }
  }

  private Cipher cipher(int mode, byte[] nonce, String context) throws GeneralSecurityException {
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(mode, key, new GCMParameterSpec(128, nonce));
    cipher.updateAAD((domain + "\n" + context).getBytes(StandardCharsets.UTF_8));
    return cipher;
  }
}
