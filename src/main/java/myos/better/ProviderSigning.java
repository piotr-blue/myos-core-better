package myos.better;

import blue.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Exact published signature domains; JCA owns Ed25519. Key custody belongs to deployment. */
final class ProviderSigning {
  private final PrivateKey key;
  private final LanguageDocument provider;
  private final BluePackage coordination;
  private final Language language;

  ProviderSigning(BluePackage coordination, LanguageDocument provider, PrivateKey key) {
    this.coordination = Objects.requireNonNull(coordination);
    this.provider = Objects.requireNonNull(provider);
    this.key = Objects.requireNonNull(key);
    language = Language.newBuilder().mount("Coordination", coordination).content(provider).build();
    // Refuse a wrong key before any durable identity or signed fact is published.
    ProviderSignatures.adopt(coordination, provider)
        .verifyObject(provider.blueId(), sign(objectDomain(provider.blueId())));
  }

  BluePackage coordination() {
    return coordination;
  }

  LanguageDocument provider() {
    return provider;
  }

  Language language() {
    return language;
  }

  LanguageContent attest(LanguageContent object) {
    return typed(
        "Provider Attestation",
        Map.of(
            "provider",
            ref(provider.blueId()),
            "object",
            wire(object),
            "signature",
            sign(objectDomain(object.blueId()))));
  }

  LanguageContent checkpoint(BlueId timeline, BlueId last, long before) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("timeline", ref(timeline));
    fields.put("completeBefore", before);
    if (last != null) fields.put("lastEntry", ref(last));
    LanguageContent guarantee = typed("Timeline Completeness Guarantee", fields);
    return typed(
        "Timeline Attestation",
        Map.of(
            "timeline",
            ref(timeline),
            "guarantee",
            wire(guarantee),
            "signature",
            sign("blue.coordination.guarantee\n" + guarantee.blueId())));
  }

  LanguageContent typed(String type, Map<String, Object> fields) {
    Map<String, Object> value = new LinkedHashMap<>(fields);
    value.put("type", ref(coordination.export(type)));
    return language.prepare(Language.Input.exactJson(JsonText.of(value)));
  }

  static Map<String, String> ref(BlueId id) {
    return Map.of("blueId", id.value());
  }

  static Object wire(LanguageContent content) {
    return Json.read(content.toJson().getBytes(StandardCharsets.UTF_8), Object.class);
  }

  private String objectDomain(BlueId object) {
    return "blue.coordination.object\n" + provider.blueId() + "\n" + object;
  }

  private String sign(String message) {
    try {
      Signature signer = Signature.getInstance("Ed25519");
      signer.initSign(key);
      signer.update(message.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(signer.sign());
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("Provider signing failed", failure);
    }
  }
}
