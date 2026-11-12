package myos.better;

import blue.BlueId;
import blue.BluePackage;
import blue.Language;
import blue.LanguageContent;
import blue.LanguageDocument;
import blue.PackageLoader;
import blue.ProviderSignatures;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.NamedParameterSpec;
import java.util.HexFormat;
import java.util.List;

/** Published Coordination and public RFC test keys, with no deployment credentials. */
final class TimelineFixture {
  static final BluePackage COORDINATION =
      PackageLoader.create()
          .resource(
              TimelineFixture.class.getClassLoader(),
              "coordination/coordination.bluepkg",
              ProviderSignatures.COORDINATION_PACKAGE);
  final LanguageDocument provider, timeline;
  final Language language;

  TimelineFixture() {
    this("https://provider.example/public");
  }

  TimelineFixture(String url) {
    this(url, true);
  }

  TimelineFixture(String url, boolean publicRead) {
    this(url, publicRead, "https://provider.example");
  }

  TimelineFixture(String url, boolean publicRead, String endpoint) {
    Language base = Language.newBuilder().mount("Coordination", COORDINATION).build();
    provider =
        base.readYaml(
            """
            type: Coordination/Timeline Provider
            endpoint: %s
            publicKey: d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a
            """
                .formatted(endpoint));
    Language sources =
        Language.newBuilder().mount("Coordination", COORDINATION).content(provider).build();
    timeline =
        sources.readYaml(
            "type: Coordination/Signed Timeline\nprovider: {blueId: '"
                + provider.blueId()
                + "'}\nurl: "
                + url
                + "\npublicRead: "
                + publicRead);
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

  LanguageContent entry(BlueId previous, long time, String message) {
    return typed(
        "Timeline Entry",
        "timeline: {blueId: '"
            + timeline.blueId()
            + "'}\n"
            + "timestamp: "
            + time
            + "\nactor: {type: Coordination/Agent Actor, service: provider}\nmessage: "
            + message
            + (previous == null ? "" : "\nprevEntry: {blueId: '" + previous + "'}"));
  }

  LanguageContent checkpoint(BlueId last, long before) {
    LanguageContent guarantee =
        typed(
            "Timeline Completeness Guarantee",
            "timeline: {blueId: '"
                + timeline.blueId()
                + "'}\ncompleteBefore: "
                + before
                + (last == null ? "" : "\nlastEntry: {blueId: '" + last + "'}"));
    return typed(
        "Timeline Attestation",
        "timeline: {blueId: '"
            + timeline.blueId()
            + "'}\nguarantee: "
            + guarantee.toJson()
            + "\nsignature: '"
            + sign("blue.coordination.guarantee\n" + guarantee.blueId())
            + "'");
  }

  LanguageContent range(BlueId after, BlueId last, long before, List<LanguageContent> entries) {
    LanguageContent attestation = checkpoint(last, before);
    return typed(
        "Timeline Range Evidence",
        "checkpoint: "
            + attestation.toJson()
            + "\nentries: ["
            + String.join(",", entries.stream().map(LanguageContent::toJson).toList())
            + "]"
            + (after == null ? "" : "\nafter: {blueId: '" + after + "'}"));
  }

  LanguageContent successor(BlueId after, LanguageContent next) {
    return typed(
        "Timeline Successor Evidence",
        "entries: ["
            + next.toJson()
            + "]\n"
            + (after == null ? "" : "after: {blueId: '" + after + "'}\n")
            + "successor:\n  type: Coordination/Provider Attestation\n"
            + "  provider: {blueId: '"
            + provider.blueId()
            + "'}\n"
            + "  object: "
            + next.toJson()
            + "\n"
            + "  signature: '"
            + sign("blue.coordination.object\n" + provider.blueId() + "\n" + next.blueId())
            + "'");
  }

  LanguageContent attest(LanguageContent value) {
    return typed(
        "Provider Attestation",
        "provider: {blueId: '"
            + provider.blueId()
            + "'}\nobject: "
            + value.toJson()
            + "\nsignature: '"
            + sign("blue.coordination.object\n" + provider.blueId() + "\n" + value.blueId())
            + "'");
  }

  private static String sign(String text) {
    try {
      byte[] seed =
          HexFormat.of()
              .parseHex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60");
      PrivateKey key =
          KeyFactory.getInstance("Ed25519")
              .generatePrivate(new EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed));
      Signature signature = Signature.getInstance("Ed25519");
      signature.initSign(key);
      signature.update(text.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(signature.sign());
    } catch (GeneralSecurityException failure) {
      throw new AssertionError(failure);
    }
  }
}
