package myos.better;

import static myos.better.FrontierRows.*;

import java.util.*;
import myos.better.OwnerFrontiers.Coverage;
import myos.better.OwnerFrontiers.Kind;
import myos.better.OwnerFrontiers.Source;

/**
 * Maintains distinct ordering authorities per owner in the same transaction as its frontier. Counts
 * retain an authority until the last binding releases it. Only a change to the distinct set
 * propagates to parents. Bounds are exclusive.
 */
final class OwnerCoverage {
  private OwnerCoverage() {}

  // These tags are part of the durable index. A local seal covers all local timelines.
  static final long UNBOUNDED = Long.MAX_VALUE;
  private static final String LOCAL = "L";
  private static final String EXTERNAL_PREFIX = "T";

  static Coverage forTimeline(OwnerMetadata.Transaction tx, String timeline) {
    Coverage result = get(tx, key("source-coverage", timeline), Coverage.class);
    return result == null ? new Coverage(UNBOUNDED, true) : result;
  }

  /** Resolve each distinct external authority once, regardless of its embedding multiplicity. */
  static Coverage forOwner(OwnerMetadata.Transaction tx, String owner) {
    long before = UNBOUNDED;
    boolean local = false;
    for (String dependency : dependencies(tx, owner)) {
      if (dependency.equals(LOCAL)) local = true;
      else {
        Coverage source = forTimeline(tx, dependency.substring(EXTERNAL_PREFIX.length()));
        before = Math.min(before, source.before());
        local |= source.local();
      }
    }
    return new Coverage(before, local);
  }

  static boolean hasLocal(OwnerMetadata.Transaction tx, String owner) {
    String value = tx.get(key("coverage-local", owner));
    if (value == null) {
      dependencies(tx, owner);
      value = tx.get(key("coverage-local", owner));
    }
    return Boolean.parseBoolean(value);
  }

  static boolean covers(OwnerMetadata.Transaction tx, String owner, long time) {
    if (hasLocal(tx, owner) && time >= completeness(tx)) return false;
    String external = tx.get(key("coverage-external", owner));
    if ("0".equals(external)) return true;
    if (tx.get("coverage-catalog") != null) {
      var minimum = tx.page(prefix("external-bound"), null, 1);
      if (minimum.isEmpty() || time < Long.parseLong(minimum.getFirst().value())) return true;
    }
    // A lagging unrelated provider never blocks this owner: the account-wide minimum
    // is only a sufficient fast certificate, not a necessary condition.
    Coverage coverage = forOwner(tx, owner);
    return time < coverage.before() && (!coverage.local() || time < completeness(tx));
  }

  static void ensureCoverageCatalog(OwnerMetadata.Transaction tx) {
    if (tx.get("coverage-catalog") != null) return;
    String prefix = prefix("source-coverage"), after = null;
    while (true) {
      var page = tx.page(prefix, after, 128);
      for (var row : page)
        updateCoverageCatalog(
            tx, row.key().substring(prefix.length()), decode(row.value(), Coverage.class));
      if (page.size() < 128) break;
      after = page.getLast().key();
    }
    tx.put("coverage-catalog", "1");
  }

  static void updateCoverageCatalog(
      OwnerMetadata.Transaction tx, String timeline, Coverage coverage) {
    String location = key("external-bound-key", timeline);
    String old = tx.get(location);
    if (old != null) tx.remove(old);
    tx.remove(location);
    if (!coverage.local()) {
      String next = key("external-bound", number(coverage.before()), timeline);
      tx.put(next, Long.toString(coverage.before()));
      tx.put(location, next);
    }
  }

  private static List<String> dependencies(OwnerMetadata.Transaction tx, String owner) {
    String stored = tx.get(key("coverage-set", owner));
    if (stored == null) {
      // One-time migration of a pre-indexed owner, within the same conditional cut.
      for (Binding binding : scan(tx, prefix("source", owner), Binding.class))
        refreshBinding(tx, owner, binding.source().id());
      publishDependencies(tx, owner);
      stored = tx.get(key("coverage-set", owner));
    }
    return strings(stored);
  }

  private static List<String> strings(String value) {
    if (value == null) return List.of();
    return Arrays.asList(decode(value, String[].class));
  }

  /** Merge the highest timestamp each external provider needs to certify across selected owners. */
  static void collectMissing(
      OwnerMetadata.Transaction tx, String owner, long time, Map<String, Long> missing) {
    for (String authority : dependencies(tx, owner)) {
      if (authority.equals(LOCAL)) continue;
      String timeline = authority.substring(EXTERNAL_PREFIX.length());
      Coverage coverage = forTimeline(tx, timeline);
      if (!coverage.local() && coverage.before() <= time) missing.merge(timeline, time, Math::max);
    }
  }

  private static void adjustDependency(
      OwnerMetadata.Transaction tx, String owner, String dependency, int delta) {
    String key = key("coverage-reference", owner, dependency);
    String previous = tx.get(key);
    long next = Math.addExact(previous == null ? 0 : Long.parseLong(previous), delta);
    if (next < 0) throw new IllegalStateException("Negative coverage reference count");
    if (next == 0) tx.remove(key);
    else tx.put(key, Long.toString(next));
    if (previous == null || next == 0) tx.put(key("coverage-dirty", owner), "1");
  }

  static void removeBinding(OwnerMetadata.Transaction tx, String owner, String binding) {
    replaceCoverage(tx, owner, binding, List.of());
  }

  private static void replaceCoverage(
      OwnerMetadata.Transaction tx, String owner, String binding, List<String> selected) {
    // Legacy projections may still be present in old histories; remove on replacement.
    String old = tx.get(key("coverage-key", owner, binding));
    if (old != null) tx.remove(old);
    tx.remove(key("coverage-key", owner, binding));
    tx.remove(key("local-coverage", owner, binding));

    String saved = key("coverage-binding", owner, binding);
    List<String> previous = strings(tx.get(saved));
    if (previous.equals(selected)) return;
    Set<String> added = new HashSet<>(selected);
    for (String dependency : previous)
      if (!added.remove(dependency)) adjustDependency(tx, owner, dependency, -1);
    for (String dependency : added) adjustDependency(tx, owner, dependency, 1);
    if (selected.isEmpty()) tx.remove(saved);
    else put(tx, saved, selected);
  }

  static void refreshBinding(OwnerMetadata.Transaction tx, String owner, String id) {
    Binding binding = get(tx, key("source", owner, id), Binding.class);
    if (binding == null || required(tx, owner).closed()) {
      removeBinding(tx, owner, id);
      return;
    }
    Source source = binding.source();
    if (source.kind() == Kind.TIMER
        || (source.kind() == Kind.OWNER && source.source().equals(owner))) {
      removeBinding(tx, owner, id);
      return;
    }
    List<String> selected =
        source.kind() == Kind.OWNER
            ? dependencies(tx, source.source())
            : List.of(
                forTimeline(tx, source.source()).local()
                    ? LOCAL
                    : EXTERNAL_PREFIX + source.source());
    replaceCoverage(tx, owner, id, selected);
  }

  static boolean publishDependencies(OwnerMetadata.Transaction tx, String owner) {
    String key = key("coverage-set", owner);
    if (tx.get(key("coverage-dirty", owner)) == null && tx.contains(key)) return false;
    String before = tx.get(key);
    List<String> selected = new ArrayList<>();
    String prefix = prefix("coverage-reference", owner), after = null;
    while (true) {
      var page = tx.page(prefix, after, 128);
      for (var entry : page) selected.add(entry.key().substring(prefix.length()));
      if (page.size() < 128) break;
      after = page.getLast().key();
    }
    String next = JsonText.of(selected);
    tx.put(key, next);
    boolean local = selected.contains(LOCAL);
    tx.put(key("coverage-local", owner), Boolean.toString(local));
    tx.put(key("coverage-external", owner), Integer.toString(selected.size() - (local ? 1 : 0)));
    tx.remove(key("coverage-dirty", owner));
    return !Objects.equals(before, next);
  }
}
