package myos.better;

import blue.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.EdECPublicKey;
import java.util.*;

/** Synthetic public source; its fresh signing key exists only during qualification setup. */
final class QualificationTimeline {
  static final BluePackage COORDINATION =
      PackageLoader.create()
          .resource(
              QualificationTimeline.class.getClassLoader(),
              "coordination/coordination.bluepkg",
              ProviderSignatures.COORDINATION_PACKAGE);
  final KeyPair keys;
  final LanguageDocument provider, timeline;
  final Language language;
  final String url;

  QualificationTimeline(String name) throws GeneralSecurityException {
    this(name, "https://qualification.invalid");
  }

  QualificationTimeline(String name, String endpoint) throws GeneralSecurityException {
    keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    url = "https://qualification.invalid/" + name;
    // RFC8032 public encoding: little-endian y with x parity in the most significant bit.
    var point = ((EdECPublicKey) keys.getPublic()).getPoint();
    byte[] big = point.getY().toByteArray(), encoded = new byte[32];
    for (int i = 0; i < Math.min(big.length, encoded.length); i++)
      encoded[i] = big[big.length - 1 - i];
    if (point.isXOdd()) encoded[31] |= (byte) 0x80;
    Language base = Language.newBuilder().mount("Coordination", COORDINATION).build();
    provider =
        base.readYaml(
            "type: Coordination/Timeline Provider\n"
                + "endpoint: "
                + endpoint
                + "\n"
                + "publicKey: '"
                + HexFormat.of().formatHex(encoded)
                + "'");
    Language sources =
        Language.newBuilder().mount("Coordination", COORDINATION).content(provider).build();
    timeline =
        sources.readYaml(
            "type: Coordination/Signed Timeline\nprovider: {blueId: '"
                + provider.blueId()
                + "'}\nurl: "
                + url
                + "\npublicRead: true");
    language =
        Language.newBuilder()
            .mount("Coordination", COORDINATION)
            .content(provider)
            .content(timeline)
            .build();
  }

  TimelineArchive archive(Repository repository, String account) {
    return new TimelineArchive(repository, account, COORDINATION, provider, timeline);
  }

  LanguageContent typed(String type, String fields) {
    return language.prepare(Language.Input.yaml("type: Coordination/" + type + "\n" + fields));
  }

  LanguageContent entry(BlueId previous, long time) {
    return typed(
        "Timeline Entry",
        "timeline: {blueId: '"
            + timeline.blueId()
            + "'}\ntimestamp: "
            + time
            + "\n"
            + "actor: {type: Coordination/Agent Actor, service: provider}\n"
            + "message: {kind: Tick, amount: 7}"
            + (previous == null ? "" : "\nprevEntry: {blueId: '" + previous + "'}"));
  }

  String range(
      BlueId after, BlueId last, long before, List<LanguageContent> entries, boolean corrupt)
      throws GeneralSecurityException {
    LanguageContent attestation = checkpoint(last, before, corrupt);
    return typed(
            "Timeline Range Evidence",
            "checkpoint: "
                + attestation.toJson()
                + "\nentries: ["
                + String.join(",", entries.stream().map(LanguageContent::toJson).toList())
                + "]"
                + (after == null ? "" : "\nafter: {blueId: '" + after + "'}"))
        .toJson();
  }

  LanguageContent attest(LanguageContent object, boolean corrupt) throws GeneralSecurityException {
    Signature signer = Signature.getInstance("Ed25519");
    signer.initSign(keys.getPrivate());
    signer.update(
        ("blue.coordination.object\n" + provider.blueId() + "\n" + object.blueId())
            .getBytes(StandardCharsets.UTF_8));
    byte[] signature = signer.sign();
    if (corrupt) signature[0] ^= 1;
    return typed(
        "Provider Attestation",
        "provider: {blueId: '"
            + provider.blueId()
            + "'}\nobject: "
            + object.toJson()
            + "\nsignature: '"
            + HexFormat.of().formatHex(signature)
            + "'");
  }

  LanguageContent checkpoint(BlueId last, long before, boolean corrupt)
      throws GeneralSecurityException {
    LanguageContent guarantee =
        typed(
            "Timeline Completeness Guarantee",
            "timeline: {blueId: '"
                + timeline.blueId()
                + "'}\ncompleteBefore: "
                + before
                + (last == null ? "" : "\nlastEntry: {blueId: '" + last + "'}"));
    Signature signer = Signature.getInstance("Ed25519");
    signer.initSign(keys.getPrivate());
    signer.update(
        ("blue.coordination.guarantee\n" + guarantee.blueId()).getBytes(StandardCharsets.UTF_8));
    byte[] signature = signer.sign();
    if (corrupt) signature[0] ^= 1;
    return typed(
        "Timeline Attestation",
        "timeline: {blueId: '"
            + timeline.blueId()
            + "'}\nguarantee: "
            + guarantee.toJson()
            + "\nsignature: '"
            + HexFormat.of().formatHex(signature)
            + "'");
  }
}
