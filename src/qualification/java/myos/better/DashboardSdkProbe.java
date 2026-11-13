package myos.better;

import blue.BluePackage;
import blue.Contracts;
import blue.Language;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Real native pipeline with loopback-only test identity, excluded from the service distribution.
 */
public final class DashboardSdkProbe {
  private DashboardSdkProbe() {}

  public static void main(String[] args) throws Exception {
    int port = args.length == 0 ? 0 : Integer.parseInt(args[0]);
    String origin = port == 0 ? "https://console.example" : "http://localhost:" + port;
    QualificationTimeline protocol =
        new QualificationTimeline("dashboard-sdk", "https://console.example");
    ProviderSigning signing =
        new ProviderSigning(
            QualificationTimeline.COORDINATION, protocol.provider, protocol.keys.getPrivate());
    BluePackage manual =
        BluePackage.open(Path.of("examples/clause-tutorial/packages/manualpayments.bluepkg"));
    Language language =
        Language.newBuilder()
            .mount("Contracts", Contracts.publication())
            .mount("ManualPayments", manual)
            .mount("Coordination", QualificationTimeline.COORDINATION)
            .build();
    PublicPackages packages =
        new PublicPackages(
            List.of(QualificationTimeline.COORDINATION, Contracts.publication(), manual),
            Map.of(
                "ManualPayments",
                manual,
                "Contracts",
                Contracts.publication(),
                "Coordination",
                QualificationTimeline.COORDINATION));
    ConsoleLogin login =
        new ConsoleLogin() {
          @Override
          public User verify(String token) {
            if (token == null || !Set.of("sdk-test-user", "sdk-test-admin").contains(token)) {
              throw new ConsoleProblem(401, "SIGN_IN_REQUIRED", "Invalid test identity");
            }
            return new User(
                token,
                token.equals("sdk-test-admin") ? "admin@example.invalid" : "sdk@example.invalid",
                System.currentTimeMillis() + 900_000);
          }

          @Override
          public Map<String, Object> action(String action, Map<String, Object> input) {
            if (!action.equals("login")
                || !Set.of("sdk@example.invalid", "admin@example.invalid")
                    .contains(input.get("email"))
                || !"loopback-test-only".equals(input.get("password"))) {
              throw new ConsoleProblem(401, "SIGN_IN_REQUIRED", "Use the isolated test identity.");
            }
            return Map.of(
                "token",
                "admin@example.invalid".equals(input.get("email"))
                    ? "sdk-test-admin"
                    : "sdk-test-user",
                "expiresIn",
                900);
          }

          @Override
          public void close() {}
        };
    try (AtomicStore store = new NotifyingStore(new MemoryStore());
        ReaderCustody custody = DashboardSecrets.open();
        TimelineService service =
            new TimelineService(
                new Repository(store),
                QualificationTimeline.COORDINATION,
                "dashboard-sdk",
                List.of(),
                new TimelineService.Automatic(60_000, 1),
                true,
                true,
                custody,
                packages)) {
      service.bind();
      service.enableCanonicalGraphs(language, List.of());
      String readerAccount =
          new ConsoleLogin.User("sdk-test-user", "sdk@example.invalid").account();
      var readerKeys =
          new CoreReaderKeys(store, new SecretEnvelope(new byte[32], "myos.better.reader-key/1"));
      var readerKey =
          readerKeys.create(readerAccount, "SDK import reader", "dashboard-import-reader");
      var publisher = new ConsoleTimelines(store, signing).hosted();
      var source = publisher.create("fixture-owner", "dashboard-import-source", "Partner source");
      publisher
          .access()
          .grant(
              "fixture-owner",
              "grant-dashboard-reader",
              new HostedAccess.Permissions(
                  List.of(source.id()), List.of((String) readerKey.get("publicKey"))));
      var original =
          publisher.append(
              "fixture-owner",
              source.id(),
              "dashboard-import-entry",
              language.prepare(
                  Language.Input.json("{\"kind\":\"Partner payment\",\"amount\":17}")));
      var importFixture =
          Map.of(
              "endpoint",
              protocol.provider.at("/endpoint").text(),
              "timelineId",
              source.id().value(),
              "readerKeyId",
              readerKey.get("id"),
              "entryBlueId",
              original.entry().blueId().value());
      var runtime =
          CanonicalRuntime.newBuilder()
              .language(language)
              .enableDocumentDispatch()
              .timelineChannels(service.channels())
              .build();
      try (Core core =
              new Core(
                  store,
                  new RuntimeCatalog(runtime, List.of(runtime), packages),
                  Core.ProjectionPolicy.BOUNDED,
                  SessionCache.Policy.STANDARD,
                  ReadBudget.Limits.STANDARD,
                  true,
                  service.namespace(),
                  service);
          ConsoleServer server =
              new ConsoleServer(
                  core,
                  store,
                  login,
                  Path.of("core-ui/dist"),
                  Path.of("examples"),
                  origin,
                  new InetSocketAddress("127.0.0.1", port),
                  signing,
                  new ConsoleServer.Inputs(service, custody),
                  new WebhookSecrets(new byte[32]));
          TimelineRunner runner = new TimelineRunner(core, service, server.timelineTransport())) {
        server.administrators(CoreAdministrators.fixed(Set.of("admin@example.invalid")));
        String mailbox = System.getenv("MYOS_TEST_INVITATION_OUTBOX");
        if (mailbox != null) {
          Path directory = Path.of(mailbox).toRealPath();
          server.invitationDelivery(
              new CoreInvitationDelivery(
                  store,
                  new SecretEnvelope(new byte[32], "myos.better.invitation/1"),
                  java.time.Clock.systemUTC(),
                  java.net.URI.create("https://console.example"),
                  (recipient, link) -> {
                    String id = link.split("invitationId=|&token=", 3)[1];
                    UUID.fromString(id);
                    try {
                      Files.writeString(
                          directory.resolve(id + ".json"),
                          JsonText.of(Map.of("recipient", recipient, "link", link)));
                    } catch (java.io.IOException failure) {
                      throw new java.io.UncheckedIOException(failure);
                    }
                  },
                  () -> {}));
        }
        runner.start();
        server.start();
        System.out.println("http://127.0.0.1:" + server.port());
        System.out.println(JsonText.of(importFixture));
        System.out.flush();
        System.in.read();
      }
    }
  }
}
