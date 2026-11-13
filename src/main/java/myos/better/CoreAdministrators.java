package myos.better;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

/** Original Core administrator allowlist: background refresh, memory-only human authorization. */
final class CoreAdministrators implements AutoCloseable {
  static final CoreAdministrators NONE = fixed(Set.of());
  private final Supplier<String> fetch;
  private final Runnable release;
  private final ScheduledExecutorService worker;
  private volatile Set<String> emails = Set.of();
  private final WorkerHealth health = new WorkerHealth();

  void check() {
    health.check();
  }

  private boolean started;
  private boolean closed;

  private CoreAdministrators(Set<String> emails) {
    this.emails = normalize(emails);
    fetch = null;
    release = () -> {};
    worker = null;
  }

  CoreAdministrators(Supplier<String> fetch, Runnable release) {
    this.fetch = Objects.requireNonNull(fetch);
    this.release = Objects.requireNonNull(release);
    worker =
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("core-admin-allowlist").factory());
  }

  static CoreAdministrators fixed(Set<String> emails) {
    return new CoreAdministrators(emails);
  }

  static CoreAdministrators configured(Map<String, String> environment) {
    String arn = environment.getOrDefault("CORE_ADMIN_ALLOWLIST_SECRET_ARN", "");
    if (arn.isBlank()) {
      return NONE;
    }
    String region = environment.get("AWS_REGION");
    if (!arn.matches("arn:aws:secretsmanager:[a-z0-9-]+:[0-9]{12}:secret:[A-Za-z0-9/_+=.@-]+")
        || !arn.split(":", 6)[3].equals(region)) {
      throw new IllegalArgumentException(
          "Administrator allowlist must be a Secrets Manager ARN in the service region");
    }
    var client =
        SecretsManagerClient.builder()
            .region(Region.of(region))
            .overrideConfiguration(
                c ->
                    c.apiCallAttemptTimeout(Duration.ofSeconds(5))
                        .apiCallTimeout(Duration.ofSeconds(15)))
            .build();
    return new CoreAdministrators(
        () -> client.getSecretValue(r -> r.secretId(arn)).secretString(), client::close);
  }

  synchronized void start() {
    if (closed) {
      throw new IllegalStateException("Administrator allowlist is closed");
    }
    if (worker != null && !started) {
      started = true;
      worker.scheduleWithFixedDelay(health.guard(() -> refresh()), 0, 600, TimeUnit.SECONDS);
    }
  }

  synchronized boolean refresh() {
    if (closed) {
      return false;
    }
    if (fetch == null) {
      return true;
    }
    try {
      String body = fetch.get();
      if (body == null || body.getBytes(StandardCharsets.UTF_8).length > 65_536) {
        throw new IllegalArgumentException();
      }
      var parsed = Json.object(body.getBytes(StandardCharsets.UTF_8));
      if (!parsed.keySet().equals(Set.of("emails"))
          || !(parsed.get("emails") instanceof List<?> values)) {
        throw new IllegalArgumentException();
      }
      emails = normalize(values);
      return true;
    } catch (RuntimeException unavailable) {
      // Match Core: first-load failure denies all; later failure retains the last accepted list.
      return false;
    }
  }

  Map<String, Object> session(CoreAccounts.Session session) {
    if (session.principal().impersonation() != null
        || !session.principal().user()
        || !emails.contains(session.principal().email().strip().toLowerCase(Locale.ROOT))) {
      throw new ConsoleProblem(
          403, "ADMIN_REQUIRED", "Sign in with an authorized administrator account.");
    }
    if (session.expiresAt() <= System.currentTimeMillis()) {
      throw new ConsoleProblem(401, "session_expired", "Sign in again.");
    }
    return Map.of(
        "userId",
        session.userId(),
        "email",
        session.principal().email(),
        "sessionContext",
        session.guard(false));
  }

  private static Set<String> normalize(Collection<?> values) {
    if (values.size() > 100) {
      throw new IllegalArgumentException("Administrator allowlist exceeds its bound");
    }
    var normalized = new HashSet<String>();
    for (Object value : values) {
      if (!(value instanceof String email)
          || email.codePoints().anyMatch(Character::isISOControl)) {
        throw new IllegalArgumentException("Invalid administrator allowlist");
      }
      email = email.strip().toLowerCase(Locale.ROOT);
      if (email.length() > 254 || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
        throw new IllegalArgumentException("Invalid administrator allowlist");
      }
      normalized.add(email);
    }
    return Set.copyOf(normalized);
  }

  @Override
  public synchronized void close() {
    if (worker == null || closed) {
      return;
    }
    closed = true;
    worker.shutdownNow();
    release.run();
  }
}
