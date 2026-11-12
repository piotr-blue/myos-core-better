package myos.better;

import java.util.*;

/** Source acquisition recovery cannot silently authorize native recovery after invalid evidence. */
final class OwnerSourceFailures {
  static final String DIAGNOSTIC = "SOURCE_INTEGRITY_FAILURE";

  static final class Failure extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    Failure() {
      super(DIAGNOSTIC);
    }
  }

  static void failed(OwnerMetadata.Transaction tx, String source) {
    tx.requireAtLeastFormat(OwnerMetadata.INTEGRITY_FORMAT);
    var prior = failure(tx, source);
    if (prior != null && Boolean.TRUE.equals(prior.get("active"))) return;
    tx.put(key(source), JsonText.of(Map.of("active", true, "token", UUID.randomUUID().toString())));
  }

  static void recovered(OwnerMetadata.Transaction tx, String source) {
    var prior = failure(tx, source);
    if (prior == null || !Boolean.TRUE.equals(prior.get("active"))) return;
    tx.put(key(source), JsonText.of(Map.of("active", false, "token", prior.get("token"))));
  }

  static boolean blocked(
      OwnerMetadata.Transaction tx, String owner, List<OwnerFrontiers.Source> sources) {
    // Ordinary accounts retain the existing hot path and metadata format: no extra store reads.
    if (!tx.supportsFormat(OwnerMetadata.INTEGRITY_FORMAT)) return false;
    for (var source : sources) {
      if (source.kind() != OwnerFrontiers.Kind.TIMELINE) continue;
      var failure = failure(tx, source.source());
      if (failure != null
          && (Boolean.TRUE.equals(failure.get("active"))
              || !failure.get("token").equals(tx.get(ack(owner, source))))) return true;
    }
    return false;
  }

  static void attach(OwnerMetadata.Transaction tx, String owner, OwnerFrontiers.Source source) {
    if (source.kind() != OwnerFrontiers.Kind.TIMELINE) return;
    var failure = failure(tx, source.source());
    // A newly subscribed lifetime does not inherit a fault that was already repaired.
    if (failure != null && !Boolean.TRUE.equals(failure.get("active")))
      tx.put(ack(owner, source), (String) failure.get("token"));
  }

  static void retry(
      OwnerMetadata.Transaction tx, String owner, List<OwnerFrontiers.Source> sources) {
    sources.forEach(source -> attach(tx, owner, source));
  }

  private static Map<String, Object> failure(OwnerMetadata.Transaction tx, String source) {
    if (!tx.supportsFormat(OwnerMetadata.INTEGRITY_FORMAT)) return null;
    String value = tx.get(key(source));
    return value == null ? null : Json.object(value);
  }

  private static String key(String source) {
    return "source-integrity\0" + source;
  }

  private static String ack(String owner, OwnerFrontiers.Source source) {
    return "source-integrity-ack\0" + owner + "\0" + source.id() + "\0" + source.generation();
  }
}
