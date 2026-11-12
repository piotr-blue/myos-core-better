package myos.better;

import blue.BluePackage;
import blue.Language;
import java.util.List;

/** Isolated real provider/reader protocol with in-memory durable boundaries and test-only keys. */
final class ConsoleInputFixture implements AutoCloseable {
  final JsonBoundaryStore store = new JsonBoundaryStore();
  final TimelineFixture protocol = new TimelineFixture();
  final ReaderCustodyTest.Secrets secrets = new ReaderCustodyTest.Secrets();
  final ReaderCustody custody = secrets.custody();
  final TimelineService service;
  final ConsoleTimelines timelines;
  final Core core;
  final TimelineRunner runner;
  final TimelineTransport transport;

  ConsoleInputFixture(Language language, List<BluePackage> packages) {
    this(language, new PublicPackages(packages));
  }

  ConsoleInputFixture(Language language, PublicPackages packages) {
    this(language, packages, 100);
  }

  ConsoleInputFixture(Language language, PublicPackages packages, long pollMillis) {
    this(language, packages, pollMillis, false);
  }

  ConsoleInputFixture(
      Language language, PublicPackages packages, long pollMillis, boolean canonical) {
    this(language, packages, pollMillis, canonical, true);
  }

  /** HTTP fixtures let ConsoleServer install the one local permission history. */
  ConsoleInputFixture(
      Language language,
      PublicPackages packages,
      long pollMillis,
      boolean canonical,
      boolean installLocalAccess) {
    this(
        language,
        packages,
        pollMillis,
        canonical,
        installLocalAccess,
        SessionCache.Policy.STANDARD);
  }

  ConsoleInputFixture(
      Language language,
      PublicPackages packages,
      long pollMillis,
      boolean canonical,
      boolean installLocalAccess,
      SessionCache.Policy sessionPolicy) {
    ProviderSigning signing =
        new ProviderSigning(
            TimelineFixture.COORDINATION, protocol.provider, HostedTimelineFixture.key());
    service =
        new TimelineService(
            new Repository(store),
            TimelineFixture.COORDINATION,
            "console-input-test",
            List.of(),
            new TimelineService.Automatic(pollMillis, 1),
            true,
            true,
            custody,
            packages);
    service.bind();
    if (canonical) {
      service.enableCanonicalGraphs(language, List.of());
    }
    timelines = new ConsoleTimelines(store, signing, service, custody, packages);
    CanonicalRuntime.Builder builder =
        CanonicalRuntime.newBuilder()
            .edition(CanonicalRuntime.EDITION)
            .language(language)
            .enableDocumentDispatch()
            .timelineChannels(service.channels());

    core =
        new Core(
            store,
            catalog(builder.build(), packages),
            Core.ProjectionPolicy.BOUNDED,
            sessionPolicy,
            ReadBudget.Limits.STANDARD,
            true,
            service.namespace(),
            service);
    NativeTimelineApi api = timelines.nativeApi();
    if (installLocalAccess) {
      api.useLocalAccess(service);
    }
    transport =
        api.transport(
            (uri, request, maximum, timeout) -> {
              throw new AssertionError("Unexpected external provider " + uri);
            });
    runner = new TimelineRunner(core, service, transport);
  }

  private static RuntimeCatalog catalog(CanonicalRuntime runtime, PublicPackages packages) {
    return new RuntimeCatalog(runtime, List.of(runtime), packages);
  }

  @Override
  public void close() {
    runner.close();
    core.close();
    service.close();
    custody.close();
    store.close();
  }
}
