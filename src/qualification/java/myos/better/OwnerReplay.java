package myos.better;

import blue.*;
import java.util.*;

/** Read-only, untimed whole-root replay of a pinned published owner history. */
final class OwnerReplay {
  private OwnerReplay() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 3 || !args[1].startsWith("clause-qa-"))
      throw new IllegalArgumentException("Expected table, existing QA account and root");
    var configured =
        RuntimeConfiguration.loadCatalog(
            java.nio.file.Path.of("examples/clause-tutorial/runtime.json"));
    try (var store =
            new DynamoStore(args[0], System.getenv().getOrDefault("AWS_REGION", "eu-west-1"));
        var custody = Objects.requireNonNull(ReaderCustody.configured(System.getenv()));
        var service =
            new TimelineService(
                new Repository(store),
                QualificationTimeline.COORDINATION,
                args[1],
                List.of(),
                null,
                true,
                true,
                custody,
                configured.packages())) {
      System.out.println(
          JsonText.of(verify(store, args[1], args[2], configured.creation().language(), service)));
    }
  }

  static Map<String, Object> verify(
      AtomicStore store, String account, String root, Language language, TimelineService service) {
    OwnerRecords records = new OwnerRecords(store, account);
    long through = records.head(root).version();
    GraphTimelineIngress ingress = service.graphIngress();
    Contracts contracts =
        Contracts.newBuilder().language(language).timelineChannels(ingress.channels()).build();
    Map<String, Object> previous = records.history(root, 1);
    int compared = 0;
    // No source-result reuse, protected preparations or calculation-cache reader is installed.
    try (Contracts.Execution oracle = Contracts.Execution.using(contracts, null)) {
      var whole = oracle.restore(records.snapshots.load(account, (String) previous.get("page")));
      for (long version = 2; version <= through; version++) {
        Map<String, Object> published = records.history(root, version);
        Map<String, Object> input = object(published.get("input"));
        String entry = (String) input.get("entry");
        OwnerFrontiers.Order order = null;
        for (Object raw : (List<?>) input.get("sources")) {
          Object selected = object(raw).get("order");
          if (selected instanceof Map<?, ?> candidate && entry.equals(candidate.get("entry"))) {
            order =
                new OwnerFrontiers.Order(
                    ((Number) candidate.get("timestamp")).longValue(),
                    (String) candidate.get("timeline"),
                    entry);
            break;
          }
        }
        if (order == null) throw new AssertionError("Missing pinned original order for " + version);
        var declarations = ingress.declarations(account, whole, service.ownerSources());
        var before = whole;
        String lineage = (String) previous.get("lineage");
        try (var scope =
            ingress.owner(
                account,
                root,
                version - 1,
                whole,
                declarations,
                order,
                id -> target(records, lineage, before, id))) {
          var result =
              oracle.process(
                  whole,
                  (LanguageContent) scope.request.event(),
                  order.timestamp(),
                  scope.request.evidence());
          Set<String> loaded = new HashSet<>();
          while ("NEEDS_RESOURCES".equals(result.transition().status())) {
            Object diagnostic = result.transition().observations().get("diagnostic");
            if (!(diagnostic instanceof Map<?, ?> details)
                || !(details.get("requiredHistory") instanceof List<?> required)) break;
            boolean progress = false;
            for (Object raw : required) {
              String id = (String) raw;
              if (loaded.size() >= 256 || !loaded.add(id)) continue;
              var retained = records.retained(new BlueId(id));
              if (retained.isPresent()) {
                oracle.restore(retained.orElseThrow());
                progress = true;
              }
            }
            if (!progress) break;
            result =
                oracle.process(
                    whole,
                    (LanguageContent) scope.request.event(),
                    order.timestamp(),
                    scope.request.evidence());
          }
          scope.verified();
          Map<String, Object> actual = object(published.get("result"));
          String receipt = (String) actual.get("receipt");
          var observed =
              records.evidence.load(account, (String) published.get("evidence"), receipt);
          // Native evidence loading verifies the stored receipt. Whole-root and reused
          // completions have different proof boundaries; compare their semantic projections.
          if (((Number) actual.get("gas")).longValue() != result.transition().gas()
              || !JsonText.of(semantic(observed))
                  .equals(JsonText.of(semantic(result.transition().observations())))
              || !Objects.equals(published.get("blueId"), result.after().blueId())) {
            List<String> differences = new ArrayList<>();
            differences(
                semantic(result.transition().observations()),
                semantic(observed),
                "semantic",
                differences);
            differences(result.transition().observations(), observed, "", differences);
            System.out.println(
                JsonText.of(
                    Map.of(
                        "phase",
                        "native-parity-mismatch",
                        "version",
                        version,
                        "mode",
                        Objects.toString(input.get("mode")),
                        "boundary",
                        Objects.toString(observed.get("executionBoundary")),
                        "sameBlueId",
                        Objects.equals(published.get("blueId"), result.after().blueId()),
                        "differences",
                        differences)));
            throw new AssertionError(
                "Whole-root state/gas/events differ at "
                    + version
                    + "; expected gas "
                    + result.transition().gas()
                    + ", stored "
                    + actual.get("gas"));
          }
          whole = result.after();
          previous = published;
          compared++;
        }
      }
    }
    return Map.of(
        "exactWholeRootComparisons",
        compared,
        "comparison",
        "document BlueId, status, gas, gasByMember, ordered events, publicEvents, diagnostic,"
            + " rejection",
        "physicalReceiptEquality",
        false,
        "throughVersion",
        through,
        "sourceResultsReused",
        false,
        "calculationsReused",
        false,
        "protectedPreparationsReused",
        false);
  }

  /**
   * Business equivalence; owner-local receipts intentionally bind a different physical boundary.
   */
  static Map<String, Object> semantic(Map<String, Object> observation) {
    Map<String, Object> selected = new LinkedHashMap<>();
    for (String field :
        List.of("status", "gas", "gasByMember", "events", "publicEvents", "diagnostic", "rejected"))
      selected.put(field, observation.get(field));
    return selected;
  }

  private static void differences(Object expected, Object actual, String path, List<String> found) {
    if (found.size() >= 32 || JsonText.of(expected).equals(JsonText.of(actual))) return;
    if (expected instanceof Map<?, ?> left && actual instanceof Map<?, ?> right) {
      Set<String> keys = new TreeSet<>();
      left.keySet().forEach(key -> keys.add((String) key));
      right.keySet().forEach(key -> keys.add((String) key));
      for (String key : keys) differences(left.get(key), right.get(key), path + "/" + key, found);
    } else if (expected instanceof List<?> left && actual instanceof List<?> right) {
      if (left.size() != right.size())
        found.add(path + " lengths " + left.size() + " / " + right.size());
      for (int i = 0; i < Math.min(left.size(), right.size()) && found.size() < 32; i++)
        differences(left.get(i), right.get(i), path + "/" + i, found);
    } else {
      String value = path + " " + JsonText.of(expected) + " / " + JsonText.of(actual);
      found.add(value.substring(0, Math.min(300, value.length())));
    }
  }

  private static GraphLineage.Target target(
      OwnerRecords records, String lineage, Contracts.Execution.State state, BlueId id) {
    var found = records.lineage.find(records.account, lineage, id.value());
    if (found != null) return found;
    Map<String, Long> members = new LinkedHashMap<>();
    for (var member : state.members()) {
      Object initial = state.rooted(member.key()).read("/contracts/initialized/document");
      if (initial instanceof Map<?, ?> ref && id.value().equals(ref.get("blueId")))
        members.put(member.key(), 1L);
    }
    return members.isEmpty() ? null : new GraphLineage.Target(members);
  }

  private static Map<String, Object> object(Object value) {
    return RuntimeConfiguration.object(value, "published history");
  }
}
