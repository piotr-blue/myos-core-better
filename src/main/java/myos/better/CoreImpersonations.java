package myos.better;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.*;

/**
 * Independently revocable one-hour user sessions; bearer secrets are never stored in plain text.
 */
final class CoreImpersonations {
  static final String PREFIX = "core_i_";
  static final long LIFETIME_MILLIS = 3_600_000;
  private static final SecureRandom RANDOM = new SecureRandom();
  private final AtomicStore store;
  private final CoreAdministrators administrators;
  private final SecretEnvelope secrets;
  private final CoreAccessBarrier access;
  private final Clock clock;

  record Identity(String digest, Map<String, Object> metadata) {
    Identity {
      metadata = Map.copyOf(metadata);
    }

    String userId() {
      return (String) metadata.get("userId");
    }
  }

  CoreImpersonations(
      AtomicStore store,
      CoreAdministrators administrators,
      SecretEnvelope secrets,
      CoreAccessBarrier access,
      Clock clock) {
    this.store = Objects.requireNonNull(store);
    this.administrators = Objects.requireNonNull(administrators);
    this.secrets = secrets;
    this.access = Objects.requireNonNull(access);
    this.clock = Objects.requireNonNull(clock);
  }

  Map<String, Object> start(CoreAccounts.Session actor, String user, String command) {
    administrators.session(actor);
    Repository.identifier(user);
    Repository.identifier(command);
    if (secrets == null)
      throw new ConsoleProblem(
          503, "impersonation_unavailable", "Session encryption is not configured.");
    var issuance =
        new AtomicStore.Key(
            "CORE_IMPERSONATION_ISSUER#" + actor.tokenDigest(), "REQUEST#" + command);
    for (int attempt = 0; attempt < 8; attempt++) {
      administrators.session(actor);
      var previous = store.get(issuance);
      if (previous != null) {
        if (!user.equals(previous.value().get("userId")))
          throw new ConsoleProblem(
              409, "idempotency_conflict", "The impersonation target changed.");
        var retained = store.get(key((String) previous.value().get("digest")));
        if (!active(retained, clock.millis()))
          throw new ConsoleProblem(
              409, "impersonation_expired", "Start a new impersonation session.");
        return Json.object(
            secrets
                .open((String) previous.value().get("sealed"), context(issuance))
                .getBytes(StandardCharsets.UTF_8));
      }
      var profileKey = CoreWorkspaces.accountKey(user, "USER");
      var identityKey = CoreWorkspaces.accountKey(user, "IDENTITY");
      var rows = store.getAll(List.of(profileKey, identityKey));
      var profile = rows.get(profileKey);
      var identity = rows.get(identityKey);
      if (profile == null
          || identity == null
          || !(identity.value().get("email") instanceof String email)
          || email.isBlank())
        throw new ConsoleProblem(
            404, "user_not_found", "A known user with a verified email is required.");
      byte[] random = new byte[32];
      RANDOM.nextBytes(random);
      String token = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
      String digest = digest(token);
      long now = clock.millis(), expires = Math.addExact(now, LIFETIME_MILLIS);
      var metadata =
          Map.<String, Object>of(
              "actorUserId",
              actor.userId(),
              "actorEmail",
              actor.principal().email(),
              "userId",
              user,
              "email",
              email,
              "createdAt",
              now,
              "expiresAt",
              expires,
              "sessionContext",
              new CoreAccounts.Session(
                      new ConsolePrincipal(user, email, true, expires), digest, expires)
                  .guard(false));
      var result =
          Map.<String, Object>of(
              "token",
              token,
              "userId",
              user,
              "email",
              email,
              "expiresAt",
              expires,
              "impersonation",
              metadata);
      var session =
          Map.<String, Object>of(
              "format",
              "myos.impersonation/1",
              "status",
              "ACTIVE",
              "metadata",
              metadata,
              "issuerPartition",
              issuance.partition(),
              "issuerSort",
              issuance.sort(),
              "expiresAt",
              expires);
      var receipt =
          Map.<String, Object>of(
              "userId",
              user,
              "digest",
              digest,
              "expiresAt",
              expires,
              "sealed",
              secrets.seal(JsonText.of(result), context(issuance)));
      if (store.commit(
          List.of(
              AtomicStore.Write.put(key(digest), 0, session),
              AtomicStore.Write.put(issuance, 0, receipt)),
          List.of(
              new AtomicStore.Check(profileKey, profile.version()),
              new AtomicStore.Check(identityKey, identity.version()))))
        return Json.object(Json.bytes(result));
    }
    throw new ConsoleProblem(503, "session_conflict", "Retry with the same idempotency key.");
  }

  ConsolePrincipal verify(String token, boolean allowEnded) {
    if (token == null || !token.matches("core_i_[A-Za-z0-9_-]{43}")) throw expired();
    String digest = digest(token);
    var row = store.get(key(digest));
    if (row == null || !allowEnded && !active(row, clock.millis())) throw expired();
    var metadata = metadata(row);
    var identity = new Identity(digest, metadata);
    return new ConsolePrincipal(
        identity.userId(),
        (String) metadata.get("email"),
        true,
        Repository.number(metadata.get("expiresAt")),
        null,
        identity);
  }

  /**
   * Hold before the account lease so ending a session drains requests in every selected workspace.
   */
  CoreAccessBarrier.Lease enter(ConsolePrincipal principal, boolean ending) {
    var identity = principal.impersonation();
    return identity == null ? null : access.enter("impersonation-" + identity.digest(), ending);
  }

  Map<String, Object> current(CoreAccounts.Session session) {
    var identity = requireIdentity(session);
    guard(store, identity.digest(), clock.millis());
    return identity.metadata();
  }

  @SuppressWarnings("try")
  Map<String, Object> end(CoreAccounts.Session session) {
    var identity = requireIdentity(session);
    try (var ignored = enter(session.principal(), true)) {
      for (int attempt = 0; attempt < 8; attempt++) {
        var row = store.get(key(identity.digest()));
        if (row == null || !identity.metadata().equals(metadata(row))) throw expired();
        if ("ENDED".equals(row.value().get("status"))) return Map.of("ended", true);
        var next = new LinkedHashMap<>(row.value());
        next.put("status", "ENDED");
        next.put("endedAt", clock.millis());
        if (store.commit(List.of(AtomicStore.Write.put(row.key(), row.version(), next)), List.of()))
          return Map.of("ended", true);
      }
    }
    throw new ConsoleProblem(503, "session_conflict", "Retry ending this session.");
  }

  static AtomicStore.Check guard(AtomicStore store, String digest, long now) {
    var row = store.get(key(digest));
    if (!active(row, now)) throw expired();
    return new AtomicStore.Check(row.key(), row.version());
  }

  private static Identity requireIdentity(CoreAccounts.Session session) {
    var identity = session.principal().impersonation();
    if (identity == null
        || !identity.digest().equals(session.tokenDigest())
        || !identity.userId().equals(session.userId()))
      throw new ConsoleProblem(
          404, "impersonation_not_found", "This is not an impersonation session.");
    return identity;
  }

  private static boolean active(AtomicStore.Row row, long now) {
    return row != null
        && "ACTIVE".equals(row.value().get("status"))
        && Repository.number(row.value().get("expiresAt")) > now;
  }

  private static Map<String, Object> metadata(AtomicStore.Row row) {
    if (!"myos.impersonation/1".equals(row.value().get("format")))
      throw new IllegalStateException("Unsupported impersonation record");
    return RuntimeConfiguration.object(row.value().get("metadata"), "impersonation metadata");
  }

  static AtomicStore.Key key(String digest) {
    return new AtomicStore.Key("CORE_IMPERSONATION#" + digest, "SESSION");
  }

  static String digest(String token) {
    return Json.hash(token.getBytes(StandardCharsets.UTF_8));
  }

  private static String context(AtomicStore.Key key) {
    return key.partition() + ":" + key.sort();
  }

  private static ConsoleProblem expired() {
    return new ConsoleProblem(401, "impersonation_expired", "This impersonation session ended.");
  }
}
