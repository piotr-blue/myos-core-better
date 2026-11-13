package myos.better;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.*;

/** Bounded leased outbox; retries send the same link and never confer membership themselves. */
final class CoreInvitationDelivery implements AutoCloseable {
  @FunctionalInterface
  interface Sender {
    void send(String recipient, String link);

    default void signup(String recipient, String link) {
      send(recipient, link);
    }
  }

  private final AtomicStore store;
  private final SecretEnvelope secrets;
  private final Clock clock;
  private final URI origin;
  private final Sender sender;
  private final Runnable release;
  private final ScheduledExecutorService worker;
  private final Map<String, String> cursors = new HashMap<>();
  private final WorkerHealth health = new WorkerHealth();

  void check() {
    health.check();
  }

  private boolean started;
  private volatile boolean closed;

  CoreInvitationDelivery(
      AtomicStore store,
      SecretEnvelope secrets,
      Clock clock,
      URI origin,
      Sender sender,
      Runnable release) {
    if (!"https".equals(origin.getScheme())
        || origin.getHost() == null
        || origin.getUserInfo() != null
        || origin.getQuery() != null
        || origin.getFragment() != null
        || !Set.of("", "/").contains(origin.getPath())) {
      throw new IllegalArgumentException("Invitation links require the exact HTTPS console origin");
    }
    this.store = store;
    this.secrets = Objects.requireNonNull(secrets);
    this.clock = clock;
    this.origin = origin;
    this.sender = sender;
    this.release = release;
    worker =
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("core-invitation-delivery").factory());
  }

  static CoreInvitationDelivery configured(
      AtomicStore store, WebhookSecrets custody, Map<String, String> environment) {
    var names =
        List.of(
            "CORE_INVITATION_FROM",
            "CORE_INVITATION_CONFIGURATION_SET",
            "CORE_SHARED_PORTAL_ORIGIN");
    if (names.stream().noneMatch(environment::containsKey)) {
      return null;
    }
    for (String name : names) {
      if (environment.getOrDefault(name, "").isBlank()) {
        throw new IllegalArgumentException("Incomplete invitation delivery configuration: " + name);
      }
    }
    if (custody == null) {
      throw new IllegalArgumentException("Pinned invitation custody is required");
    }
    String from = environment.get("CORE_INVITATION_FROM");
    if (from.contains("\r") || from.contains("\n")) {
      throw new IllegalArgumentException("Invalid invitation sender");
    }
    var client =
        SesV2Client.builder()
            .region(Region.of(environment.get("AWS_REGION")))
            .overrideConfiguration(
                c ->
                    c.apiCallAttemptTimeout(Duration.ofSeconds(5))
                        .apiCallTimeout(Duration.ofSeconds(15)))
            .build();
    try {
      return new CoreInvitationDelivery(
          store,
          custody.envelopes("myos.better.invitation/1"),
          Clock.systemUTC(),
          URI.create(environment.get("CORE_SHARED_PORTAL_ORIGIN")),
          new Sender() {
            public void send(String recipient, String link) {
              sendEmail(
                  client,
                  environment,
                  from,
                  recipient,
                  "MyOS account invitation",
                  "You have been invited to a MyOS account. Open this link to sign in and accept: "
                      + link
                      + "\n\nThis invitation expires in seven days.");
            }

            public void signup(String recipient, String link) {
              sendEmail(
                  client,
                  environment,
                  from,
                  recipient,
                  "A document is waiting for you in MyOS",
                  "A MyOS document has been shared with this email address. Sign in or create an"
                      + " account using this address: "
                      + link
                      + "\n\nAccess is granted only after your email address is verified.");
            }
          },
          client::close);
    } catch (RuntimeException | Error failure) {
      client.close();
      throw failure;
    }
  }

  private static void sendEmail(
      SesV2Client client,
      Map<String, String> environment,
      String from,
      String recipient,
      String subject,
      String body) {
    client.sendEmail(
        SendEmailRequest.builder()
            .fromEmailAddress(from)
            .configurationSetName(environment.get("CORE_INVITATION_CONFIGURATION_SET"))
            .destination(Destination.builder().toAddresses(recipient).build())
            .content(
                EmailContent.builder()
                    .simple(
                        Message.builder()
                            .subject(Content.builder().data(subject).charset("UTF-8").build())
                            .body(
                                Body.builder()
                                    .text(Content.builder().data(body).charset("UTF-8").build())
                                    .build())
                            .build())
                    .build())
            .build());
  }

  synchronized void start() {
    if (closed) {
      throw new IllegalStateException("Invitation delivery is closed");
    }
    if (started) {
      throw new IllegalStateException("Invitation delivery already started");
    }
    started = true;
    worker.scheduleWithFixedDelay(
        health.guard(
            () -> {
              try {
                turn();
              } catch (RuntimeException ignored) {
                /* Durable retry state survives; never log recipient, link or provider response. */
              }
            }),
        0,
        1,
        TimeUnit.SECONDS);
  }

  void turn() {
    for (String shard : List.of("CORE_INVITATION_DELIVERY", "CORE_SIGNUP_DELIVERY")) {
      deliverPage(shard);
    }
  }

  private void deliverPage(String shard) {
    if (closed || Thread.currentThread().isInterrupted()) {
      return;
    }
    var page = store.work(shard, cursors.get(shard), 25);
    for (var row : page.rows()) {
      if (closed || Thread.currentThread().isInterrupted()) {
        return;
      }
      var claim = claim((String) row.value().get("invitationId"));
      if (claim == null) {
        continue;
      }
      boolean delivered = false;
      try {
        String id = (String) claim.get("invitationId");
        if ("SIGNUP".equals(claim.get("purpose"))) {
          sender.signup((String) claim.get("recipientEmail"), origin.resolve("/").toString());
        } else {
          String token = secrets.open((String) claim.get("token"), id);
          String link =
              origin.resolve("/")
                  + "#invitation?invitationId="
                  + URLEncoder.encode(id, StandardCharsets.UTF_8)
                  + "&token="
                  + URLEncoder.encode(token, StandardCharsets.UTF_8)
                  + "&revision=1";
          sender.send((String) claim.get("recipientEmail"), link);
        }
        delivered = true;
      } catch (RuntimeException ignored) {
        /* A retry uses the same retained encrypted token. */
      }
      acknowledge(claim, delivered);
    }
    cursors.put(shard, page.after());
  }

  Map<String, Object> claim(String id) {
    for (int attempt = 0; attempt < 8; attempt++) {
      var row = store.get(CoreInvitations.outboxKey(id));
      if (row == null
          || !"PENDING".equals(row.value().get("status"))
          || Repository.number(row.value().get("availableAt")) > clock.millis()
          || Repository.number(row.value().getOrDefault("leaseUntil", 0L)) > clock.millis()) {
        return null;
      }
      boolean signup = "SIGNUP".equals(row.value().get("purpose"));
      var invitationKey =
          signup
              ? CorePendingRecipients.key((String) row.value().get("recipientEmail"))
              : CoreInvitations.key(id);
      var invitation = store.get(invitationKey);
      boolean eligible =
          invitation != null
              && "PENDING".equals(invitation.value().get("status"))
              && (signup
                  ? row.value().get("accountId").equals(invitation.value().get("account"))
                  : Repository.number(invitation.value().get("expiresAt")) > clock.millis());
      var next = new LinkedHashMap<>(row.value());
      if (eligible) {
        next.put("leaseId", UUID.randomUUID().toString());
        next.put("leaseUntil", Math.addExact(clock.millis(), 60_000L));
        next.put("attempts", Math.incrementExact(Repository.number(next.get("attempts"))));
      } else {
        next.put("status", "SKIPPED");
        next.remove("work");
        next.remove("position");
        next.remove("leaseId");
        next.remove("leaseUntil");
      }
      if (store.commit(
          List.of(AtomicStore.Write.put(row.key(), row.version(), next)),
          List.of(
              new AtomicStore.Check(
                  invitationKey, invitation == null ? 0 : invitation.version())))) {
        return eligible ? next : null;
      }
    }
    throw new ConsoleProblem(503, "account_busy", "Invitation delivery is temporarily busy.");
  }

  void acknowledge(Map<String, Object> claim, boolean delivered) {
    String id = (String) claim.get("invitationId");
    for (int attempt = 0; attempt < 8; attempt++) {
      var row = store.get(CoreInvitations.outboxKey(id));
      if (row == null
          || !"PENDING".equals(row.value().get("status"))
          || !claim.get("leaseId").equals(row.value().get("leaseId"))) {
        return;
      }
      var next = new LinkedHashMap<>(row.value());
      next.remove("leaseId");
      next.remove("leaseUntil");
      if (delivered) {
        next.put("status", "DELIVERED");
        next.put("deliveredAt", clock.millis());
        next.remove("work");
        next.remove("position");
      } else {
        next.put(
            "availableAt",
            clock.millis()
                + Math.min(
                    3_600_000L, 1000L << Math.min(12L, Repository.number(next.get("attempts")))));
      }
      if (store.commit(List.of(AtomicStore.Write.put(row.key(), row.version(), next)), List.of())) {
        return;
      }
    }
    throw new ConsoleProblem(503, "account_busy", "Invitation delivery is temporarily busy.");
  }

  @Override
  public synchronized void close() {
    if (closed) {
      return;
    }
    closed = true;
    worker.shutdownNow();
    try {
      if (!worker.awaitTermination(20, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Invitation delivery did not terminate");
      }
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(failure);
    } finally {
      release.run();
    }
  }
}
