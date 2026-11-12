package myos.better;

import blue.BlueId;
import blue.BluePackage;
import blue.Language;
import blue.LanguageContent;
import blue.LanguageDocument;
import blue.LanguageException;
import blue.TimelineAccess;
import blue.TimelineReader;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/**
 * Read-only bootstrap: the selected HTTPS endpoint and exact Timeline are verified before adoption.
 */
final class CoreTimelineDiscovery {
  record Source(
      LanguageDocument provider,
      LanguageDocument timeline,
      LanguageDocument control,
      LanguageContent descriptor) {}

  private final BluePackage coordination;
  private final Language language;
  private final CoreReaderKeys keys;
  private final TimelineTransport transport;

  CoreTimelineDiscovery(
      BluePackage coordination,
      PublicPackages packages,
      CoreReaderKeys keys,
      TimelineTransport transport) {
    this.coordination = Objects.requireNonNull(coordination);
    this.language =
        Language.newBuilder().mount("Coordination", coordination).provider(packages).build();
    this.keys = Objects.requireNonNull(keys);
    this.transport = Objects.requireNonNull(transport);
  }

  Source discover(String account, String key, String endpoint, BlueId expected)
      throws IOException, InterruptedException {
    endpoint(endpoint);
    keys.read(account, key);
    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    Provider discovery = provider(endpoint, deadline);
    Language context = discovery.context();
    LanguageDocument provider = discovery.provider(), control = discovery.control();
    LanguageContent descriptor = discovery.descriptor();
    var lookup = keys.timelineRequest(account, key, coordination, expected);
    LanguageContent response =
        exchange(context, endpoint + "/timelines/content", lookup.signed().toJson(), deadline);
    LanguageDocument timeline =
        context.toBuilder()
            .content(response)
            .build()
            .read(new BlueId(response.at("/object/content").observation("exactId").text()));
    if (!expected.equals(timeline.blueId()))
      throw new IllegalArgumentException("Provider returned another Timeline identity");
    TimelineReader reader = TimelineReader.adopt(coordination, provider, timeline, descriptor);
    LanguageContent verified = reader.content(lookup.request()).accept(response);
    if (!expected.equals(verified.blueId()))
      throw new IllegalArgumentException("Verified Timeline identity differs from selection");
    keys.read(account, key);
    return new Source(provider, timeline, control, descriptor);
  }

  private record Provider(
      Language context,
      LanguageDocument provider,
      LanguageDocument control,
      LanguageContent descriptor) {}

  record Access(
      Source source, LanguageDocument initial, TimelineReader.AccessDescription description) {}

  private Provider provider(String endpoint, long deadline)
      throws IOException, InterruptedException {
    LanguageContent descriptor = exchange(language, endpoint + "/provider", null, deadline);
    Language context = language.toBuilder().content(descriptor).build();
    BlueId providerId = new BlueId(descriptor.at("/object/provider").observation("exactId").text());
    LanguageDocument provider;
    try {
      provider = context.read(providerId);
    } catch (LanguageException unavailable) {
      if (!"ProviderUnavailable".equals(unavailable.category())) throw unavailable;
      context = providerDefinition(endpoint, deadline).toBuilder().content(descriptor).build();
      provider = context.read(providerId);
      // Preserve the original prepared closure while attaching the newly verified definition.
      descriptor = context.prepare(Language.Input.exactJson(descriptor.toJson()));
    }
    if (!endpoint.equals(provider.at("/endpoint").text()))
      throw new IllegalArgumentException("Provider endpoint differs from the selected endpoint");
    LanguageDocument control =
        context.read(
            new BlueId(descriptor.at("/object/controlTimeline").observation("exactId").text()));
    // The native reader authenticates descriptor/provider identity and its public reserved control.
    TimelineReader.adopt(coordination, provider, control, descriptor);
    return new Provider(context, provider, control, descriptor);
  }

  /** Public discovery does not require or expose a private reader credential. */
  Access access(String endpoint, BlueId expected) throws IOException, InterruptedException {
    endpoint(endpoint);
    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    Provider source = provider(endpoint, deadline);
    TimelineReader reader =
        TimelineReader.adopt(
            coordination, source.provider(), source.control(), source.descriptor());
    Language context = source.context();
    LanguageContent request =
        context.prepare(
            Language.Input.yaml(
                "type: Coordination/Get Timeline Access Request\naccess: {blueId: '"
                    + expected
                    + "'}"));
    LanguageContent response =
        exchange(context, endpoint + "/access/get", request.toJson(), deadline);
    var description = reader.access(request).accept(response);
    context = context.toBuilder().content(response).build();
    var fetched = new java.util.HashSet<BlueId>();
    // Bounded lazy hydration retains only provider-authenticated exact content. The initial
    // document is admitted only after the normal Language validator has all required evidence.
    for (int attempt = 0; attempt <= 64; attempt++) {
      try {
        LanguageDocument initial = context.read(expected);
        try (var verification =
            TimelineAccess.track(coordination, source.provider(), source.descriptor(), initial)) {
          if (verification.view().isPresent())
            throw new IllegalStateException("Discovery granted access before replay");
        }
        return new Access(
            new Source(source.provider(), source.control(), source.control(), source.descriptor()),
            initial,
            description);
      } catch (LanguageException unavailable) {
        if (!"ProviderUnavailable".equals(unavailable.category()) || attempt == 64)
          throw unavailable;
        BlueId missing = new BlueId(unavailable.diagnostic().at("/detail").text());
        if (!fetched.add(missing)) throw unavailable;
        LanguageContent lookup =
            context.prepare(
                Language.Input.yaml(
                    "type: Coordination/Resolve Timeline Content Request\ntimeline: {blueId: '"
                        + source.control().blueId()
                        + "'}\ncontent: {blueId: '"
                        + missing
                        + "'}"));
        var exact =
            reader
                .content(lookup)
                .accept(
                    exchange(context, endpoint + "/timelines/content", lookup.toJson(), deadline));
        context = context.toBuilder().content(exact).build();
      }
    }
    throw new IllegalStateException("Access hydration did not terminate");
  }

  /**
   * A provider may publish a custom data subtype. Its exact hash and native ancestry still verify.
   */
  private Language providerDefinition(String endpoint, long deadline)
      throws IOException, InterruptedException {
    var advertised =
        RuntimeConfiguration.object(
            Json.object(
                    exchangeText(endpoint + "/types", null, deadline)
                        .getBytes(StandardCharsets.UTF_8))
                .get("providerType"),
            "providerType");
    var expected =
        new BlueId(RuntimeConfiguration.text(advertised.get("blueId"), "provider type identity"));
    var definition =
        language.admit(
            language.prepare(Language.Input.exactJson(JsonText.of(advertised.get("definition")))),
            Language.Goal.DEFINITION);
    if (!expected.equals(definition.blueId()))
      throw new IllegalArgumentException("Provider type definition has another identity");
    return language.toBuilder().content(definition).build();
  }

  static URI endpoint(String value) {
    try {
      URI uri = URI.create(value);
      if (value.length() > 4096
          || !"https".equals(uri.getScheme())
          || uri.getHost() == null
          || uri.getRawUserInfo() != null
          || uri.getRawQuery() != null
          || uri.getRawFragment() != null
          || !uri.normalize().equals(uri)
          || uri.getPort() != -1 && uri.getPort() != 443
          || value.endsWith("/")) throw new IllegalArgumentException();
      return uri;
    } catch (RuntimeException invalid) {
      throw new ConsoleProblem(
          400,
          "invalid_endpoint",
          "Select an HTTPS provider endpoint on port 443 without credentials, query or a trailing"
              + " slash.");
    }
  }

  private LanguageContent exchange(Language context, String target, String request, long deadline)
      throws IOException, InterruptedException {
    return context.prepare(Language.Input.exactJson(exchangeText(target, request, deadline)));
  }

  private String exchangeText(String target, String request, long deadline)
      throws IOException, InterruptedException {
    long remaining = deadline - System.nanoTime();
    if (remaining <= 0)
      throw new java.net.http.HttpTimeoutException("Provider discovery deadline exceeded");
    var response =
        transport.exchange(URI.create(target), request, 256 * 1024, Duration.ofNanos(remaining));
    if (response.status() != 200)
      throw new IOException("Provider discovery HTTP " + response.status());
    if (response.byteSize() > 256 * 1024)
      throw new IOException("Provider discovery response exceeds 256 KiB");
    // Decode only valid UTF-8; a replacement character must not change signed wire meaning.
    String json =
        StandardCharsets.UTF_8
            .newDecoder()
            .decode(java.nio.ByteBuffer.wrap(response.body()))
            .toString();
    return json;
  }
}
