package myos.better;

import blue.BlueId;
import blue.BluePackage;
import blue.Language;
import blue.LanguageContent;
import blue.LanguageDocument;
import blue.TimelineRequests;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

/** One protected reader key bound to one exact source; never owner or business authority. */
final class TimelineReaderSigning {
  private final Language language;
  private final TimelineRequests requests;
  private final BluePackage coordination;
  private final BlueId provider, timeline;
  private final String publicKey;
  private final PrivateKey key;

  TimelineReaderSigning(
      BluePackage coordination,
      LanguageDocument provider,
      LanguageDocument timeline,
      String publicKey,
      PrivateKey key) {
    this.coordination = Objects.requireNonNull(coordination);
    this.provider = provider.blueId();
    this.timeline = timeline.blueId();
    this.publicKey = Objects.requireNonNull(publicKey);
    this.key = Objects.requireNonNull(key);
    language =
        Language.newBuilder()
            .mount("Coordination", coordination)
            .content(provider)
            .content(timeline)
            .build();
    requests = TimelineRequests.forTimeline(coordination, provider, timeline, 10_000);
    // Verify the pinned public/private pair using the published reader signature domain.
    LanguageContent probe =
        language.prepare(
            Language.Input.exactJson(
                JsonText.of(
                    Map.of(
                        "type",
                        ProviderSigning.ref(coordination.export("Read Timeline Request")),
                        "timeline",
                        ProviderSigning.ref(this.timeline),
                        "limit",
                        0))));
    TimelineRequests.Reader verified =
        requests.reader(sign(TimelineRequests.Operation.READ, probe));
    if (!verified.publicKey().orElseThrow().equals(publicKey)) {
      throw new IllegalArgumentException("Reader key mismatch");
    }
  }

  String publicKey() {
    return publicKey;
  }

  void requireSource(TimelineArchive.Source source) {
    if (!provider.equals(source.provider()) || !timeline.equals(source.timeline())) {
      throw new IllegalArgumentException("Reader key belongs to another adopted source");
    }
  }

  LanguageContent sign(TimelineRequests.Operation operation, LanguageContent request) {
    TimelineRequests.Reader selected = requests.reader(request);
    if (selected.publicKey().isPresent() || selected.operation() != operation) {
      throw new IllegalArgumentException(
          "Expected the unsigned request for the selected read route");
    }
    try {
      Signature signature = Signature.getInstance("Ed25519");
      signature.initSign(key);
      signature.update(
          ("blue.coordination.reader-request\n" + selected.request().blueId())
              .getBytes(StandardCharsets.UTF_8));
      return language.prepare(
          Language.Input.exactJson(
              JsonText.of(
                  Map.of(
                      "type",
                      ProviderSigning.ref(coordination.export("Signed Reader Request")),
                      "request",
                      ProviderSigning.wire(selected.request()),
                      "publicKey",
                      publicKey,
                      "signature",
                      HexFormat.of().formatHex(signature.sign())))));
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("Unable to sign the selected Timeline read", failure);
    }
  }
}
