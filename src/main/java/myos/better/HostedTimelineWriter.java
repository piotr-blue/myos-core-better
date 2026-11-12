package myos.better;

import blue.LanguageContent;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable writer identity; a request body never supplies publication authority. */
final class HostedTimelineWriter {
  static final String REPRESENTED_FORMAT = "myos.hosted-timeline/2";

  static Map<String, Object> human(String user) {
    return Map.of("kind", "HUMAN_REPRESENTATIVE", "userId", Repository.identifier(user));
  }

  static LanguageContent actor(
      ProviderSigning signing, String account, Map<String, Object> policy) {
    if (policy.isEmpty()) return signing.typed("Principal Actor", Map.of("account", account));
    validate(policy);
    return signing.typed(
        "Principal Actor",
        Map.of("myosAccountId", account, "representativeId", policy.get("userId")));
  }

  static void validate(Map<String, Object> policy) {
    if (!policy.keySet().equals(Set.of("kind", "userId"))
        || !"HUMAN_REPRESENTATIVE".equals(policy.get("kind"))
        || !(policy.get("userId") instanceof String user)
        || !Repository.identifier(user).equals(user))
      throw new IllegalStateException("Unsupported Timeline writer policy");
  }

  static List<AtomicStore.Check> authorize(
      AtomicStore store, String account, Map<String, Object> policy, CoreAccounts.Session session) {
    if (policy.isEmpty()) return List.of();
    validate(policy);
    if (session == null
        || !account.equals(session.principal().account())
        || !policy.get("userId").equals(session.userId())
        || !session.principal().user()
            && (session.principal().credential() == null
                || session.principal().credential().owner() == null))
      throw new ConsoleProblem(
          403,
          "representative_writer_required",
          "This Timeline requires its authenticated representative.");
    return CoreCommandAuthority.verify(
        store, account, CoreCommandAuthority.capture(session), "timelines/entries");
  }
}
