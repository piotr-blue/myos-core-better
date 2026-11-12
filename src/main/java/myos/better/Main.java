package myos.better;

import java.util.Map;

/** Launches the private service or a synthetic workload against the selected durable store. */
public final class Main {
  private Main() {}

  public static void main(String[] args) throws Exception {
    String configuration = System.getenv("MYOS_RUNTIME");
    Core.ProjectionPolicy projections =
        Core.ProjectionPolicy.valueOf(
            System.getenv()
                .getOrDefault("MYOS_PROJECTIONS", "bounded")
                .toUpperCase(java.util.Locale.ROOT));
    SessionCache.Policy sessions =
        new SessionCache.Policy(
            Integer.parseInt(System.getenv().getOrDefault("MYOS_SESSION_ENTRIES", "64")),
            Math.multiplyExact(
                Long.parseLong(System.getenv().getOrDefault("MYOS_SESSION_MIB", "128")),
                1024L * 1024),
            Integer.parseInt(System.getenv().getOrDefault("MYOS_SESSION_USES", "32")));
    if (sessions.maximumBytes() > Runtime.getRuntime().maxMemory() / 2) {
      throw new IllegalArgumentException("Session cache budget must not exceed half the JVM heap");
    }
    ReadBudget.Limits reads =
        new ReadBudget.Limits(
            Integer.parseInt(System.getenv().getOrDefault("MYOS_WORKFLOW_READ_DOCUMENTS", "256")),
            Integer.parseInt(System.getenv().getOrDefault("MYOS_WORKFLOW_READ_VALUES", "262144")),
            Integer.parseInt(
                System.getenv().getOrDefault("MYOS_WORKFLOW_READ_CHARACTERS", "4194304")),
            Integer.parseInt(System.getenv().getOrDefault("MYOS_WORKFLOW_READ_DEPTH", "64")));
    String table = System.getenv("MYOS_TABLE");
    boolean combineAcknowledgements =
        switch (System.getenv().getOrDefault("MYOS_COMBINE_ACKS", "true")) {
          case "true" -> true;
          case "false" -> false;
          default -> throw new IllegalArgumentException("MYOS_COMBINE_ACKS must be true or false");
        };
    PublicDirectoryStore publicDirectory =
        new PublicDirectoryStore(
            table == null
                ? new MemoryStore()
                : new DynamoStore(table, System.getenv().getOrDefault("AWS_REGION", "eu-west-1")));
    AtomicStore store = new NotifyingStore(new MeasuredStore(publicDirectory));
    RuntimeConfiguration.Loaded configured;
    Core core;
    try {
      String namespace = System.getenv("MYOS_ADMISSION_NAMESPACE");
      configured =
          configuration == null
              ? new RuntimeConfiguration.Loaded(
                  RuntimeCatalog.single(CanonicalRuntime.create()),
                  java.util.Optional.empty(),
                  namespace)
              : RuntimeConfiguration.load(java.nio.file.Path.of(configuration), store, namespace);
      core =
          new Core(
              store,
              configured.catalog(),
              projections,
              sessions,
              reads,
              combineAcknowledgements,
              configured.admissionNamespace(),
              configured.timelines().orElse(null));
    } catch (RuntimeException | Error failure) {
      try {
        store.close();
      } catch (RuntimeException close) {
        failure.addSuppressed(close);
      }
      throw failure;
    }
    try (core) {
      String mode = args.length == 0 ? "serve" : args[0];
      switch (mode) {
        case "reader-key" -> {
          if (args.length != 4) {
            throw new IllegalArgumentException("Expected reader-key ACCOUNT NAME COMMAND");
          }
          WebhookSecrets custody = WebhookSecrets.configured(System.getenv());
          if (custody == null) {
            throw new IllegalStateException("Pinned application custody is required");
          }
          var readers = new CoreReaderKeys(store, custody.envelopes("myos.better.reader-key/1"));
          System.out.println(JsonText.of(readers.create(args[1], args[2], args[3])));
        }
        case "benchmark", "probe", "probe-links", "populate" -> {
          int orders = args.length > 1 ? Integer.parseInt(args[1]) : 16;
          int payments = args.length > 2 ? Integer.parseInt(args[2]) : 3;
          int operations =
              args.length > 3 ? Integer.parseInt(args[3]) : Math.min(16, orders * payments);
          String account = args.length > 4 ? args[4] : "bench-" + java.util.UUID.randomUUID();
          Map<String, Object> result =
              switch (mode) {
                case "populate" -> Scenario.populate(core, account, orders, payments);
                case "probe" ->
                    Scenario.probe(
                        core,
                        account,
                        orders,
                        payments,
                        operations,
                        args.length > 5 ? Integer.parseInt(args[5]) : orders);
                case "probe-links" ->
                    Scenario.probeStored(
                        core,
                        account,
                        orders,
                        payments,
                        operations,
                        args.length > 5 ? Integer.parseInt(args[5]) : orders);
                default -> Scenario.benchmark(core, account, orders, payments, operations);
              };
          System.out.println(JsonText.of(result));
        }
        case "serve" -> {
          try (ConsoleServer console =
                  ConsoleServer.configured(core, store, configured.timelines().orElse(null));
              Server server =
                  new Server(
                      core,
                      Integer.parseInt(System.getenv().getOrDefault("PORT", "8080")),
                      table != null,
                      Integer.parseInt(System.getenv().getOrDefault("MYOS_WORKERS", "2")),
                      configured.timelines().orElse(null),
                      console == null
                          ? new TimelineHttpTransport()
                          : console.timelineTransport())) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> closeServers(server, console)));
            server.start();
            publicDirectory.start();
            if (console != null) {
              console.start();
            }
            System.out.println(
                JsonText.of(
                    Map.of(
                        "status",
                        "ready",
                        "edition",
                        configured.catalog().creation().edition(),
                        "port",
                        server.port(),
                        "durable",
                        table != null)));
            server.await(
                () -> {
                  publicDirectory.check();
                  if (console != null) {
                    console.check();
                  }
                });
          }
        }
        default ->
            throw new IllegalArgumentException("Expected serve, benchmark, probe or populate");
      }
    }
  }

  private static void closeServers(Server server, ConsoleServer console) {
    try {
      if (console != null) {
        console.close();
      }
    } finally {
      server.close();
    }
  }
}
