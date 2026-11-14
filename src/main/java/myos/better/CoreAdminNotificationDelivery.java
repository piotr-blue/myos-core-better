package myos.better;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;

/** Bounded fanout and leased delivery; a crash never changes an event's captured recipients. */
final class CoreAdminNotificationDelivery implements AutoCloseable {
  static final String DELIVERY = "CORE_ADMIN_DELIVERY";

  record Message(
      String deliveryId,
      String recipient,
      String kind,
      String email,
      Long occurredAt,
      long recordedAt) {}

  @FunctionalInterface
  interface Sender {
    void send(Message message);
  }

  private final AtomicStore store;
  private final Clock clock;
  private final Sender sender;
  private final Runnable release;
  private final ScheduledExecutorService worker =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofPlatform().daemon().name("core-admin-notifications").factory());
  private final WorkerHealth health = new WorkerHealth();

  void check() {
    health.check();
  }

  private boolean started;
  private volatile boolean closed;
  private boolean providerReleased;

  CoreAdminNotificationDelivery(AtomicStore store, Clock clock, Sender sender, Runnable release) {
    this.store = Objects.requireNonNull(store);
    this.clock = Objects.requireNonNull(clock);
    this.sender = Objects.requireNonNull(sender);
    this.release = Objects.requireNonNull(release);
  }

  static CoreAdminNotificationDelivery configured(
      AtomicStore store, Map<String, String> environment) {
    if (environment.getOrDefault("CORE_ADMIN_ALLOWLIST_SECRET_ARN", "").isBlank()) {
      return null;
    }
    String from = environment.getOrDefault("CORE_INVITATION_FROM", "");
    String configuration = environment.getOrDefault("CORE_INVITATION_CONFIGURATION_SET", "");
    if (from.isBlank() && configuration.isBlank()) {
      return null;
    }
    if (from.isBlank()
        || configuration.isBlank()
        || from.indexOf('\r') >= 0
        || from.indexOf('\n') >= 0) {
      throw new IllegalArgumentException("Complete administrator mail configuration is required");
    }
    var client =
        SesV2Client.builder()
            .region(Region.of(environment.get("AWS_REGION")))
            .overrideConfiguration(
                c ->
                    c.apiCallAttemptTimeout(Duration.ofSeconds(5))
                        .apiCallTimeout(Duration.ofSeconds(15)))
            .build();
    return new CoreAdminNotificationDelivery(
        store,
        Clock.systemUTC(),
        event ->
            client.sendEmail(
                SendEmailRequest.builder()
                    .fromEmailAddress(from)
                    .configurationSetName(configuration)
                    .destination(Destination.builder().toAddresses(event.recipient()).build())
                    .content(
                        EmailContent.builder()
                            .simple(
                                software.amazon.awssdk.services.sesv2.model.Message.builder()
                                    .subject(
                                        content(
                                            "MyOS user "
                                                + (event.kind().equals("REGISTERED")
                                                    ? "registered"
                                                    : "verified")))
                                    .body(
                                        Body.builder()
                                            .text(
                                                content(
                                                    "User: "
                                                        + event.email()
                                                        + "\nEvent: "
                                                        + event.kind()
                                                        + "\nOccurred at: "
                                                        + (event.occurredAt() == null
                                                            ? "unknown"
                                                            : java.time.Instant.ofEpochMilli(
                                                                event.occurredAt()))
                                                        + "\nObserved at: "
                                                        + java.time.Instant.ofEpochMilli(
                                                            event.recordedAt())
                                                        + "\nDelivery: "
                                                        + event.deliveryId()))
                                            .build())
                                    .build())
                            .build())
                    .build()),
        client::close);
  }

  private static Content content(String text) {
    return Content.builder().charset("UTF-8").data(text).build();
  }

  synchronized void start() {
    if (closed || started) {
      throw new IllegalStateException("Notification worker already started or closed");
    }
    started = true;
    worker.scheduleWithFixedDelay(
        health.guard(
            () -> {
              try {
                turn();
              } catch (RuntimeException unavailable) {
                /* Retain durable work; no recipient or provider payload in logs. */
              }
            }),
        0,
        1,
        TimeUnit.SECONDS);
  }

  void turn() {
    if (closed || Thread.currentThread().isInterrupted()) {
      return;
    }
    for (var event : store.work(CoreAdminNotifications.WORK, 5)) {
      if (closed || Thread.currentThread().isInterrupted()) {
        return;
      }
      fanout(event.key());
    }
    for (var hint : store.work(DELIVERY, 25)) {
      if (closed || Thread.currentThread().isInterrupted()) {
        return;
      }
      var claim = claim(hint.key());
      if (claim == null) {
        continue;
      }
      boolean delivered = false;
      try {
        var event =
            store.get(CoreAdminNotifications.eventKey((String) claim.value().get("eventId")));
        if (event == null) {
          throw new IllegalStateException("Notification event is missing");
        }
        var data = event.value();
        sender.send(
            new Message(
                (String) claim.value().get("deliveryId"),
                (String) claim.value().get("recipient"),
                (String) data.get("kind"),
                (String) data.get("email"),
                data.get("occurredAt") == null ? null : Repository.number(data.get("occurredAt")),
                Repository.number(data.get("recordedAt"))));
        delivered = true;
      } catch (RuntimeException unavailable) {
        /* An ambiguous provider result is retried at least once. */
      } finally {
        finish(claim, delivered);
      }
    }
  }

  void fanout(AtomicStore.Key key) {
    for (int attempt = 0; attempt < 4; attempt++) {
      var row = store.get(key);
      if (row == null || !"PENDING".equals(row.value().get("status"))) {
        return;
      }
      var recipients = (List<?>) row.value().get("recipients");
      int from = ((Number) row.value().getOrDefault("cursor", 0)).intValue();
      int until = Math.min(recipients.size(), from + 25);
      var writes = new ArrayList<AtomicStore.Write>();
      String eventId = (String) row.value().get("eventId");
      for (int i = from; i < until; i++) {
        String recipient = (String) recipients.get(i);
        String id = Json.hash(Json.bytes(List.of("admin-user-delivery-v1", eventId, recipient)));
        var delivery = new AtomicStore.Key("CORE_ADMIN_DELIVERY#" + id, "STATE");
        var value = new LinkedHashMap<String, Object>();
        value.put("deliveryId", id);
        value.put("eventId", eventId);
        value.put("recipient", recipient);
        value.put("status", "PENDING");
        value.put("attempts", 0L);
        value.put("availableAt", clock.millis());
        value.put("work", DELIVERY);
        value.put("position", position(clock.millis()));
        writes.add(AtomicStore.Write.put(delivery, 0, value));
      }
      var next = new LinkedHashMap<>(row.value());
      next.put("cursor", until);
      if (until == recipients.size()) {
        next.put("status", "FANOUT_COMPLETE");
        next.remove("work");
        next.remove("position");
      } else {
        next.put("position", position(clock.millis()));
      }
      writes.add(AtomicStore.Write.put(key, row.version(), next));
      if (store.commit(writes, List.of())) {
        return;
      }
    }
  }

  private AtomicStore.Row claim(AtomicStore.Key key) {
    for (int attempt = 0; attempt < 4; attempt++) {
      var row = store.get(key);
      long now = clock.millis();
      if (row == null
          || !"PENDING".equals(row.value().get("status"))
          || Repository.number(row.value().get("availableAt")) > now
          || Repository.number(row.value().getOrDefault("leaseUntil", 0)) > now) {
        return null;
      }
      var next = new LinkedHashMap<>(row.value());
      next.put("lease", UUID.randomUUID().toString());
      next.put("leaseUntil", Math.addExact(now, 60_000));
      next.put("attempts", Math.addExact(Repository.number(next.get("attempts")), 1));
      next.put("position", position(now + 60_000));
      if (store.commit(List.of(AtomicStore.Write.put(key, row.version(), next)), List.of())) {
        return new AtomicStore.Row(key, row.version() + 1, next);
      }
    }
    return null;
  }

  private void finish(AtomicStore.Row claim, boolean delivered) {
    var current = store.get(claim.key());
    if (current == null
        || !Objects.equals(current.value().get("lease"), claim.value().get("lease"))) {
      return;
    }
    var next = new LinkedHashMap<>(current.value());
    next.remove("lease");
    next.remove("leaseUntil");
    if (delivered) {
      next.put("status", "DELIVERED");
      next.put("deliveredAt", clock.millis());
      next.remove("work");
      next.remove("position");
    } else {
      long delay =
          Math.min(3_600_000, 1000L << Math.min(12, Repository.number(next.get("attempts"))));
      long available = Math.addExact(clock.millis(), delay);
      next.put("availableAt", available);
      next.put("position", position(available));
    }
    store.commit(List.of(AtomicStore.Write.put(current.key(), current.version(), next)), List.of());
  }

  private static String position(long time) {
    return String.format(Locale.ROOT, "%019d", time);
  }

  @Override
  public synchronized void close() {
    closed = true;
    worker.shutdownNow();
    CloseActions.run(this::releaseProvider, this::joinWorker);
  }

  private void releaseProvider() {
    if (!providerReleased) {
      providerReleased = true;
      release.run();
    }
  }

  /** A repeated close must still join after an interrupted or timed-out earlier join. */
  private void joinWorker() {
    try {
      if (!worker.awaitTermination(25, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Notification worker did not stop");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Interrupted while stopping notification worker", interrupted);
    }
  }
}
