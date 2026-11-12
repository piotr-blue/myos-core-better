package myos.better;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;

/** Verified email changes fence recipient selection without a global directory lock. */
final class CoreUserIdentities {
  private CoreUserIdentities() {}

  static void remember(AtomicStore store, CoreAccounts.Session session) {
    session.principal().requireUser();
    if (session.principal().impersonation() != null) return;
    String email = email(session.principal().email());
    var key = CoreWorkspaces.accountKey(session.userId(), "IDENTITY");
    var value = Map.<String, Object>of("userId", session.userId(), "email", email);
    for (int attempt = 0; attempt < 8; attempt++) {
      var previous = store.get(key);
      boolean pending = CorePendingRecipients.pending(store, email);
      if (previous != null && previous.value().equals(value) && !pending) return;
      var ownership = new CoreOwnership(store);
      if (pending && !ownership.ready())
        throw new ConsoleProblem(
            503,
            "public_directory_building",
            "Recipient ownership is waiting for the identity directory.");
      // Existing identities remain usable during the resumable baseline. New ownership
      // assignment requires that baseline; once complete, every email mutation is fenced.
      var ownershipChange =
          ownership.ready()
              ? ownership.verifiedIdentity(session.userId(), email, previous == null)
              : null;
      var emails = new HashSet<String>();
      emails.add(email);
      if (previous != null) emails.add(email(previous.value().get("email")));
      var writes = new ArrayList<AtomicStore.Write>();
      writes.add(AtomicStore.Write.put(key, previous == null ? 0 : previous.version(), value));
      for (String address : emails) {
        var fence = emailKey(address);
        var before = store.get(fence);
        writes.add(
            AtomicStore.Write.put(
                fence, before == null ? 0 : before.version(), Map.of("format", 1)));
      }
      var checks = new ArrayList<AtomicStore.Check>();
      if (ownershipChange != null && !ownershipChange.stage(writes, checks)) continue;
      if (store.commit(writes, checks)) return;
    }
    throw new ConsoleProblem(503, "account_busy", "User identity is temporarily unavailable.");
  }

  static AtomicStore.Key emailKey(String email) {
    return new AtomicStore.Key("CORE_EMAIL#" + Json.hash(Json.bytes(email(email))), "REVISION");
  }

  static String email(Object raw) {
    if (!(raw instanceof String text)) throw invalidEmail();
    String email = text.strip().toLowerCase(Locale.ROOT);
    if (email.length() > 254 || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
      throw invalidEmail();
    return email;
  }

  private static ConsoleProblem invalidEmail() {
    return new ConsoleProblem(422, "invalid_email", "Provide a valid email address.");
  }
}
