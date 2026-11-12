package myos.better;

import blue.*;
import java.util.*;

/** Explicit consuming-document policy, separate from the published channel's native header. */
final class TimelineReadAccess {
  static final String PROFILE = "myos.timeline-access-at-input/1";
  static final String POINTER = "/timelineReadAccess";

  record Requirement(BlueId timeline, Set<String> keys, Set<BlueId> grants) {
    Requirement {
      keys = Set.copyOf(keys);
      grants = Set.copyOf(grants);
    }
  }

  private TimelineReadAccess() {}

  /** Pure decoded policy data. Historical grants and reader authorization are never cached here. */
  static final class Cache {
    private static final long MAXIMUM_BYTES = 1024 * 1024;

    private record Entry(List<Requirement> requirements, long weight) {}

    private record Reference(BlueId policy, long weight) {}

    private final LinkedHashMap<BlueId, Entry> entries = new LinkedHashMap<>(16, .75f, true);
    // Exact document -> exact policy is pure content metadata. Never retain documents,
    // providers, grant states or authorization decisions in this bounded index.
    private final LinkedHashMap<BlueId, Reference> documents = new LinkedHashMap<>(16, .75f, true);
    private long bytes;
    private long referenceBytes;

    List<Requirement> read(LanguageDocument document) {
      BlueId documentId = document.blueId();
      synchronized (this) {
        Reference reference = documents.get(documentId);
        if (reference != null) {
          if (reference.policy() == null) return List.of();
          Entry found = entries.get(reference.policy());
          if (found != null) return found.requirements();
        }
      }
      // Capture the selected node once: separate view observations would reopen a cold
      // member body independently even when all documents share one immutable policy.
      LanguageContent selected = document.at(POINTER).content().orElse(null);
      if (selected == null || !selected.resolve().exists()) {
        remember(documentId, null);
        return List.of();
      }
      BlueId identity = selected.blueId();
      synchronized (this) {
        Entry found = entries.get(identity);
        if (found != null) {
          remember(documentId, identity);
          return found.requirements();
        }
      }
      List<Requirement> decoded = parse(selected.resolve());
      long weight = 256;
      for (Requirement requirement : decoded) {
        weight += 256 + 2L * requirement.timeline().value().length();
        for (String key : requirement.keys()) weight += 64 + 2L * key.length();
        for (BlueId grant : requirement.grants()) weight += 64 + 2L * grant.value().length();
      }
      synchronized (this) {
        Entry previous = entries.remove(identity);
        if (previous != null) bytes -= previous.weight();
        if (weight <= MAXIMUM_BYTES) {
          while (bytes + weight > MAXIMUM_BYTES || entries.size() >= 128)
            bytes -= entries.pollFirstEntry().getValue().weight();
          entries.put(identity, new Entry(decoded, weight));
          bytes += weight;
          remember(documentId, identity);
        }
      }
      return decoded;
    }

    synchronized void clear() {
      entries.clear();
      documents.clear();
      bytes = 0;
      referenceBytes = 0;
    }

    private synchronized void remember(BlueId document, BlueId policy) {
      long weight =
          192
              + 2L * document.value().length()
              + (policy == null ? 0 : 2L * policy.value().length());
      Reference previous = documents.remove(document);
      if (previous != null) referenceBytes -= previous.weight();
      if (weight > MAXIMUM_BYTES) return;
      while (referenceBytes > MAXIMUM_BYTES - weight || documents.size() >= 4096)
        referenceBytes -= documents.pollFirstEntry().getValue().weight();
      documents.put(document, new Reference(policy, weight));
      referenceBytes += weight;
    }
  }

  static List<Requirement> requirements(LanguageDocument document) {
    LanguageView policy = document.at(POINTER);
    if (!policy.exists()) return List.of();
    return parse(policy);
  }

  /** Resolve only the selected grant through the document's retained lazy provider. */
  static LanguageDocument grant(LanguageDocument document, BlueId expected) {
    LanguageView requirements = document.at(POINTER + "/requirements");
    int count = bounded(requirements, "requirements");
    for (int i = 0; i < count; i++) {
      LanguageView grants = requirements.at("/items/" + i + "/grants");
      int size = bounded(grants, "grants");
      for (int j = 0; j < size; j++) {
        LanguageView candidate = grants.at("/items/" + j);
        if (!id(candidate).equals(expected)) continue;
        LanguageContent content = candidate.content().orElseThrow();
        return Language.newBuilder().content(content).build().read(expected);
      }
    }
    throw new IllegalArgumentException("Grant is absent from the receiving policy");
  }

  private static List<Requirement> parse(LanguageView policy) {
    if (!new HashSet<>(policy.keys()).equals(Set.of("profile", "requirements"))
        || !PROFILE.equals(policy.at("/profile").text()))
      throw new IllegalArgumentException("Unsupported document Timeline read-access policy");
    LanguageView list = policy.at("/requirements");
    int size = bounded(list, "requirements");
    Set<BlueId> sources = new HashSet<>();
    List<Requirement> result = new ArrayList<>();
    Set<BlueId> allGrants = new HashSet<>();
    for (int i = 0; i < size; i++) {
      LanguageView item = list.at("/items/" + i);
      if (!new HashSet<>(item.keys()).equals(Set.of("timeline", "keys", "grants")))
        throw new IllegalArgumentException("Read access requires timeline, keys and grants");
      BlueId timeline = id(item.at("/timeline"));
      if (!sources.add(timeline)) throw new IllegalArgumentException("Duplicate access timeline");
      LanguageView keys = item.at("/keys"), grants = item.at("/grants");
      int keyCount = bounded(keys, "keys"), grantCount = bounded(grants, "grants");
      Set<String> selectedKeys = new LinkedHashSet<>();
      for (int j = 0; j < keyCount; j++) {
        String key = keys.at("/items/" + j).text();
        if (!key.matches("[0-9a-f]{64}") || !selectedKeys.add(key))
          throw new IllegalArgumentException("Distinct canonical Ed25519 reader keys required");
      }
      Set<BlueId> selectedGrants = new LinkedHashSet<>();
      for (int j = 0; j < grantCount; j++)
        if (!selectedGrants.add(id(grants.at("/items/" + j))))
          throw new IllegalArgumentException("Duplicate access grant");
      result.add(new Requirement(timeline, selectedKeys, selectedGrants));
      allGrants.addAll(selectedGrants);
      if (allGrants.size() > 32)
        throw new IllegalArgumentException("At most 32 distinct access grants per document");
    }
    return List.copyOf(result);
  }

  static String identity(List<Requirement> requirements) {
    return Json.hash(
        Json.bytes(
            requirements.stream()
                .sorted(Comparator.comparing(r -> r.timeline().value()))
                .map(
                    r ->
                        Map.of(
                            "timeline",
                            r.timeline().value(),
                            "keys",
                            r.keys().stream().sorted().toList(),
                            "grants",
                            r.grants().stream().map(BlueId::value).sorted().toList()))
                .toList()));
  }

  private static int bounded(LanguageView list, String field) {
    int size = list.size();
    if (size < 1 || size > 32)
      throw new IllegalArgumentException("Read-access " + field + " requires 1..32 values");
    return size;
  }

  private static BlueId id(LanguageView value) {
    return new BlueId(value.observation("exactId").text());
  }
}
