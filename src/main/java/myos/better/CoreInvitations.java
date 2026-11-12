package myos.better;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Clock;
import java.util.*;

/** Invitation intent, recipient binding and membership acceptance share atomic publication. */
final class CoreInvitations {
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final long LIFETIME_MILLIS = 7 * 86_400_000L;
  private final AtomicStore store;
  private final SecretEnvelope secrets;
  private final Clock clock;

  CoreInvitations(AtomicStore store, SecretEnvelope secrets, Clock clock) {
    this.store = store;
    this.secrets = secrets;
    this.clock = clock;
  }

  static boolean acceptance(String path, String method) {
    if (!method.equals("POST") || !path.startsWith(CoreDashboardApi.BASE + "invitations/"))
      return false;
    String[] route = path.substring(CoreDashboardApi.BASE.length()).split("/", -1);
    return route.length == 3 && !route[1].isEmpty() && route[2].equals("accept");
  }

  Map<String, Object> issue(
      CoreAccounts.Session caller, String command, Map<String, Object> input) {
    caller.principal().requireUser();
    RuntimeConfiguration.fields(input, Set.of("email", "role", "targetUserId"));
    String email = CoreUserIdentities.email(input.get("email"));
    String role = RuntimeConfiguration.text(input.get("role"), "role");
    if (role.equals("OWNER"))
      throw problem(
          422,
          "owner_invitation_forbidden",
          "Invite another role, then assign Owner after joining.");
    if (!Set.of("ADMIN", "WRITE", "READ_ALL", "READ_SELECTED").contains(role))
      throw problem(422, "invalid_account_input", "Invalid invitation role.");
    String explicit =
        input.get("targetUserId") == null
            ? null
            : Repository.identifier(
                RuntimeConfiguration.text(input.get("targetUserId"), "targetUserId"));
    String account = caller.principal().account();
    var intent = new LinkedHashMap<String, Object>(Map.of("email", email, "role", role));
    if (explicit != null) intent.put("target", explicit);
    String fingerprint = Json.hash(Json.bytes(intent));
    var receipt =
        CoreWorkspaces.accountKey(
            account, "INVITE_COMMAND#" + caller.userId() + "#" + Repository.identifier(command));
    String observed = null;
    boolean resolved = false;
    for (int attempt = 0; attempt < 8; attempt++) {
      var previous = store.get(receipt);
      if (previous != null) return replay(previous, fingerprint);
      var checks = new ArrayList<>(CoreAccountAuthority.administrator(store, caller));
      var emailKey = CoreUserIdentities.emailKey(email);
      var emailVersion = store.get(emailKey);
      checks.add(new AtomicStore.Check(emailKey, version(emailVersion)));
      String target = explicit == null ? formerRecipient(account, email) : explicit;
      if (resolved && !Objects.equals(observed, target))
        throw problem(
            409,
            "invitation_recipient_changed",
            "Recipient changed. Review the roster and try again.");
      observed = target;
      resolved = true;
      AtomicStore.Row member = null;
      if (target != null) {
        var identityKey = CoreWorkspaces.accountKey(target, "IDENTITY");
        var identity = store.get(identityKey);
        if (identity == null
            || !email.equals(CoreUserIdentities.email(identity.value().get("email"))))
          throw problem(403, "invitation_recipient_mismatch", "Verified recipient email differs.");
        checks.add(new AtomicStore.Check(identityKey, identity.version()));
        var memberKey = CoreWorkspaces.accountKey(account, "MEMBER#" + target);
        member = store.get(memberKey);
        if (member != null && "ACTIVE".equals(member.value().get("status"))
            || member == null && target.equals(account))
          throw problem(409, "membership_exists", "Recipient already has an active membership.");
        checks.add(new AtomicStore.Check(memberKey, version(member)));
      }
      if (secrets == null)
        throw problem(
            503, "invitation_custody_unavailable", "Invitation custody is not configured.");
      String id = UUID.randomUUID().toString(), token = token();
      var invitation = new LinkedHashMap<String, Object>();
      invitation.put("invitationId", id);
      invitation.put("accountId", account);
      invitation.put("recipientEmail", email);
      if (target != null) invitation.put("recipientUserId", target);
      invitation.put("role", role);
      invitation.put("tokenDigest", digest(token));
      invitation.put("expiresAt", Math.addExact(clock.millis(), LIFETIME_MILLIS));
      invitation.put("status", "PENDING");
      invitation.put("revision", 1L);
      invitation.put(
          "expectedMembershipGeneration", member == null ? 0L : member.value().get("generation"));
      invitation.put(
          "expectedMembershipRevision", member == null ? 0L : member.value().get("revision"));
      invitation.put("createdBy", caller.userId());
      invitation.put("createdAt", clock.millis());
      var result = metadata(invitation);
      var writes = new ArrayList<AtomicStore.Write>();
      publish(writes, null, invitation);
      var outbox = new LinkedHashMap<String, Object>();
      outbox.put("invitationId", id);
      outbox.put("accountId", account);
      outbox.put("recipientEmail", email);
      outbox.put("token", secrets.seal(token, id));
      outbox.put("status", "PENDING");
      outbox.put("attempts", 0L);
      outbox.put("availableAt", clock.millis());
      outbox.put("work", "CORE_INVITATION_DELIVERY");
      outbox.put("position", id);
      writes.add(AtomicStore.Write.put(outboxKey(id), 0, outbox));
      writes.add(
          AtomicStore.Write.put(receipt, 0, Map.of("fingerprint", fingerprint, "result", result)));
      if (store.commit(writes, distinct(checks))) return result;
    }
    throw busy();
  }

  Map<String, Object> accept(CoreAccounts.Session caller, String id, Map<String, Object> input) {
    caller.principal().requireUser();
    RuntimeConfiguration.fields(input, Set.of("token", "expectedRevision"));
    String token = RuntimeConfiguration.text(input.get("token"), "token");
    long revision = positive(input.get("expectedRevision"));
    if (!token.matches("[A-Za-z0-9_-]{43}"))
      throw problem(422, "invalid_account_input", "Provide an invitation token and revision.");
    String digest = digest(token);
    String fingerprint = Json.hash(Json.bytes(Map.of("tokenDigest", digest, "revision", revision)));
    var receipt =
        new AtomicStore.Key("CORE_INVITATION_ACCEPT#" + Repository.identifier(id), caller.userId());
    CoreUserIdentities.remember(store, caller);
    for (int attempt = 0; attempt < 8; attempt++) {
      var current = require(id);
      var invitation = current.value();
      if (!MessageDigest.isEqual(
          digest.getBytes(StandardCharsets.US_ASCII),
          ((String) invitation.get("tokenDigest")).getBytes(StandardCharsets.US_ASCII)))
        throw problem(403, "invitation_token_invalid", "Invitation token is invalid.");
      var previous = store.get(receipt);
      if (previous != null) return replay(previous, fingerprint);
      if (!"PENDING".equals(invitation.get("status"))
          || Repository.number(invitation.get("expiresAt")) <= clock.millis())
        throw problem(409, "invitation_unavailable", "Invitation is no longer available.");
      if (Repository.number(invitation.get("revision")) != revision) throw stale();
      var identityKey = CoreWorkspaces.accountKey(caller.userId(), "IDENTITY");
      var identity = store.get(identityKey);
      if (identity == null
          || !invitation.get("recipientEmail").equals(identity.value().get("email"))
          || !invitation
              .get("recipientEmail")
              .equals(CoreUserIdentities.email(caller.principal().email()))
          || invitation.containsKey("recipientUserId")
              && !caller.userId().equals(invitation.get("recipientUserId")))
        throw problem(
            403, "invitation_recipient_mismatch", "Invitation belongs to another recipient.");
      String account = (String) invitation.get("accountId");
      var accountKey = CoreWorkspaces.accountKey(account, "ACCOUNT");
      var workspace = store.get(accountKey);
      if (workspace != null && !"ACTIVE".equals(workspace.value().getOrDefault("status", "ACTIVE")))
        throw problem(403, "account_unavailable", "Workspace is unavailable.");
      if (workspace == null && !account.startsWith("user-"))
        throw problem(403, "account_unavailable", "Workspace is unavailable.");
      var memberKey = CoreWorkspaces.accountKey(account, "MEMBER#" + caller.userId());
      var member = store.get(memberKey);
      if (member != null && "ACTIVE".equals(member.value().get("status"))
          || member == null && account.equals(caller.userId()))
        throw problem(409, "membership_exists", "User already has an active membership.");
      long generation = member == null ? 0 : Repository.number(member.value().get("generation"));
      long memberRevision = member == null ? 0 : Repository.number(member.value().get("revision"));
      if (generation != Repository.number(invitation.get("expectedMembershipGeneration"))
          || memberRevision != Repository.number(invitation.get("expectedMembershipRevision")))
        throw problem(409, "membership_stale", "Invitation predates the current membership state.");
      String role = (String) invitation.get("role");
      if (role.equals("OWNER"))
        throw problem(
            422, "owner_invitation_forbidden", "Owner can only be assigned to an active member.");
      var updatedAccount =
          workspace == null
              ? new LinkedHashMap<String, Object>(
                  Map.of(
                      "kind",
                      "PERSONAL",
                      "profile",
                      Map.of("displayName", account),
                      "status",
                      "ACTIVE"))
              : new LinkedHashMap<>(workspace.value());
      updatedAccount.put(
          "activeAdminCount",
          CoreMemberships.administrators(store, account, workspace)
              + (role.equals("ADMIN") ? 1 : 0));
      updatedAccount.put(
          "membershipRevision",
          Math.incrementExact(
              Repository.number(updatedAccount.getOrDefault("membershipRevision", 1L))));
      var result =
          Map.<String, Object>of(
              "invitationId",
              id,
              "accountId",
              account,
              "userId",
              caller.userId(),
              "membershipGeneration",
              Math.incrementExact(generation),
              "membershipRevision",
              Math.incrementExact(memberRevision),
              "role",
              role,
              "receiptId",
              UUID.randomUUID().toString());
      var accepted = new LinkedHashMap<>(invitation);
      accepted.put("status", "ACCEPTED");
      accepted.put("revision", Math.incrementExact(revision));
      accepted.put("acceptedBy", caller.userId());
      accepted.put("acceptedAt", clock.millis());
      var writes = new ArrayList<AtomicStore.Write>();
      publish(writes, current, accepted);
      writes.add(AtomicStore.Write.put(accountKey, version(workspace), updatedAccount));
      writes.add(
          AtomicStore.Write.put(
              memberKey,
              version(member),
              Map.of(
                  "userId",
                  caller.userId(),
                  "accountId",
                  account,
                  "role",
                  role,
                  "status",
                  "ACTIVE",
                  "generation",
                  result.get("membershipGeneration"),
                  "revision",
                  result.get("membershipRevision"))));
      var listingKey =
          new AtomicStore.Key("CORE_USER_ACCOUNTS#" + caller.userId(), "WORKSPACE#" + account);
      writes.add(
          AtomicStore.Write.put(
              listingKey, version(store.get(listingKey)), Map.of("accountId", account)));
      writes.add(
          AtomicStore.Write.put(receipt, 0, Map.of("fingerprint", fingerprint, "result", result)));
      if (store.commit(writes, List.of(new AtomicStore.Check(identityKey, identity.version()))))
        return result;
    }
    throw busy();
  }

  Map<String, Object> revoke(
      CoreAccounts.Session caller, String id, String command, Map<String, Object> input) {
    caller.principal().requireUser();
    RuntimeConfiguration.fields(input, Set.of("expectedRevision"));
    long revision = positive(input.get("expectedRevision"));
    String account = caller.principal().account();
    String fingerprint = Json.hash(Json.bytes(Map.of("invitation", id, "revision", revision)));
    var receipt =
        CoreWorkspaces.accountKey(
            account, "REVOKE_INVITE#" + caller.userId() + "#" + Repository.identifier(command));
    for (int attempt = 0; attempt < 8; attempt++) {
      var previous = store.get(receipt);
      if (previous != null) return replay(previous, fingerprint);
      var checks = CoreAccountAuthority.administrator(store, caller);
      var current = require(id);
      if (!account.equals(current.value().get("accountId"))) throw missing();
      if (!"PENDING".equals(current.value().get("status")))
        throw problem(409, "invitation_unavailable", "Invitation is no longer pending.");
      if (Repository.number(current.value().get("revision")) != revision) throw stale();
      var revoked = new LinkedHashMap<>(current.value());
      revoked.put("status", "REVOKED");
      revoked.put("revision", Math.incrementExact(revision));
      var result = metadata(revoked);
      var writes = new ArrayList<AtomicStore.Write>();
      publish(writes, current, revoked);
      writes.add(
          AtomicStore.Write.put(receipt, 0, Map.of("fingerprint", fingerprint, "result", result)));
      if (store.commit(writes, checks)) return result;
    }
    throw busy();
  }

  private String formerRecipient(String account, String email) {
    String target = null, after = null;
    int scanned = 0;
    do {
      var page =
          store.query(
              CoreWorkspaces.accountKey(account, "ACCOUNT").partition(), "MEMBER#", after, 100);
      for (var row : page.rows()) {
        if (++scanned > 4096)
          throw problem(422, "account_roster_limit", "Account roster exceeds the supported bound.");
        if (!"REMOVED".equals(row.value().get("status"))) continue;
        String user = (String) row.value().get("userId");
        var identity = store.get(CoreWorkspaces.accountKey(user, "IDENTITY"));
        if (identity == null
            || !email.equals(CoreUserIdentities.email(identity.value().get("email")))) continue;
        if (target != null)
          throw problem(
              409,
              "invitation_recipient_ambiguous",
              "Choose Invite this user again from the member list.");
        target = user;
      }
      after = page.after();
    } while (after != null);
    return target;
  }

  AtomicStore.Row require(String id) {
    var row = store.get(key(id));
    if (row == null) throw missing();
    return row;
  }

  private void publish(
      List<AtomicStore.Write> writes, AtomicStore.Row previous, Map<String, Object> invitation) {
    String id = (String) invitation.get("invitationId"),
        account = (String) invitation.get("accountId");
    writes.add(AtomicStore.Write.put(key(id), version(previous), invitation));
    var roster = CoreWorkspaces.accountKey(account, "INVITATION#" + id);
    var before = store.get(roster);
    writes.add(AtomicStore.Write.put(roster, version(before), metadata(invitation)));
  }

  static Map<String, Object> metadata(Map<String, Object> invitation) {
    var result = new LinkedHashMap<String, Object>();
    for (String field :
        List.of(
            "invitationId",
            "accountId",
            "recipientEmail",
            "recipientUserId",
            "role",
            "status",
            "revision",
            "expiresAt",
            "createdAt",
            "acceptedAt",
            "acceptedBy"))
      if (invitation.get(field) != null) result.put(field, invitation.get(field));
    return result;
  }

  static AtomicStore.Key key(String id) {
    return new AtomicStore.Key("CORE_INVITATIONS", Repository.identifier(id));
  }

  static AtomicStore.Key outboxKey(String id) {
    return new AtomicStore.Key("CORE_INVITATION_OUTBOX", Repository.identifier(id));
  }

  private static List<AtomicStore.Check> distinct(List<AtomicStore.Check> checks) {
    var unique = new LinkedHashMap<AtomicStore.Key, AtomicStore.Check>();
    for (var check : checks) {
      var before = unique.putIfAbsent(check.key(), check);
      if (before != null && before.version() != check.version())
        throw problem(409, "membership_stale", "Membership context changed.");
    }
    return List.copyOf(unique.values());
  }

  private static Map<String, Object> replay(AtomicStore.Row row, String fingerprint) {
    if (!fingerprint.equals(row.value().get("fingerprint")))
      throw problem(
          409, "idempotency_conflict", "The invitation command has different parameters.");
    return RuntimeConfiguration.object(row.value().get("result"), "invitation receipt");
  }

  private static String token() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String digest(String token) {
    return Json.hash(token.getBytes(StandardCharsets.US_ASCII));
  }

  private static long positive(Object value) {
    long number = Repository.number(value);
    if (number < 1)
      throw problem(422, "invalid_account_input", "Provide a positive invitation revision.");
    return number;
  }

  private static long version(AtomicStore.Row row) {
    return row == null ? 0 : row.version();
  }

  private static ConsoleProblem busy() {
    return problem(503, "account_busy", "Retry the same invitation command.");
  }

  private static ConsoleProblem stale() {
    return problem(409, "invitation_stale", "Invitation revision changed.");
  }

  private static ConsoleProblem missing() {
    return problem(404, "invitation_not_found", "Invitation was not found.");
  }

  private static ConsoleProblem problem(int status, String code, String message) {
    return new ConsoleProblem(status, code, message);
  }
}
