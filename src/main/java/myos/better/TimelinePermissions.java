package myos.better;

import blue.BlueId;
import blue.LanguageDocument;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Requirements come from the frozen receiver; facts come from independently verified control
 * history.
 */
final class TimelinePermissions implements AutoCloseable {
  private final TimelineAccessHistory history = new TimelineAccessHistory();
  private final TimelineReadAccess.Cache policies = new TimelineReadAccess.Cache();
  private LocalHistory local;

  @FunctionalInterface
  interface LocalHistory {
    Optional<TimelineAccessHistory.Result> at(
        TimelineArchive control, BlueId grant, TimelineInputs.Order order);
  }

  /** Installed by the host before workers start, never selected by a document or request. */
  void useLocalHistory(LocalHistory value) {
    if (local != null) {
      throw new IllegalStateException("Local access history already installed");
    }
    local = Objects.requireNonNull(value);
  }

  static final class Unavailable extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    final TimelineIngress.Status status;

    Unavailable(String message) {
      this(TimelineIngress.Status.WAITING, message);
    }

    Unavailable(TimelineIngress.Status status, String message) {
      super(message);
      this.status = status;
    }
  }

  record Binding(
      TimelineReadAccess.Requirement requirement,
      TimelineArchive control,
      TimelineArchive.Discovery discovery) {}

  record Selection(
      LanguageDocument document,
      List<TimelineReadAccess.Requirement> requirements,
      List<Binding> bindings) {
    List<TimelineArchive> controls() {
      return bindings.stream().map(Binding::control).distinct().toList();
    }
  }

  record Decision(TimelineIngress.Status status, String reason, List<Map<String, Object>> facts) {}

  Selection select(
      LanguageDocument document,
      List<TimelineArchive> privateSources,
      List<TimelineArchive> available) {
    return select(document, privateSources, available, new HashMap<>());
  }

  /** One frozen admission may reuse the same signed source discovery across its members. */
  Selection select(
      LanguageDocument document,
      List<TimelineArchive> privateSources,
      List<TimelineArchive> available,
      Map<TimelineArchive, TimelineArchive.Discovery> discoveries) {
    List<TimelineReadAccess.Requirement> requirements = policies.read(document);
    Map<BlueId, TimelineArchive> sources = new HashMap<>();
    privateSources.forEach(a -> sources.put(a.source().timeline(), a));
    Map<BlueId, TimelineReadAccess.Requirement> bySource = new HashMap<>();
    requirements.forEach(r -> bySource.put(r.timeline(), r));
    if (!sources.keySet().equals(bySource.keySet())) {
      throw new Unavailable(
          "Every private receiving source needs one document read-access requirement");
    }
    List<Binding> bindings = new ArrayList<>();
    for (TimelineReadAccess.Requirement requirement : requirements) {
      TimelineArchive source = sources.get(requirement.timeline());
      TimelineArchive.Discovery discovery =
          discoveries.computeIfAbsent(
              source,
              archive ->
                  archive
                      .discovery()
                      .orElseThrow(
                          () -> new Unavailable("Signed provider discovery is not established")));
      TimelineArchive control =
          available.stream()
              .filter(
                  a ->
                      a.source().account().equals(source.source().account())
                          && a.source().provider().equals(source.source().provider())
                          && a.source().timeline().equals(discovery.control())
                          && a.source().publicRead())
              .findFirst()
              .orElseThrow(() -> new Unavailable("Reserved public control source is not adopted"));
      bindings.add(new Binding(requirement, control, discovery));
    }
    return new Selection(document, requirements, List.copyOf(bindings));
  }

  Decision check(Selection selection, TimelineInputs.Plan plan) {
    TimelineInputs.Order order = plan.order().orElseThrow();
    Map<String, TimelineInputs.Cut> cuts = new HashMap<>();
    plan.coverage().forEach(c -> cuts.put(c.timeline(), c));
    List<Map<String, Object>> facts = new ArrayList<>();
    for (Binding binding : selection.bindings()) {
      // A public control occurrence never depends on the private permission it may change.
      if (!binding.requirement().timeline().value().equals(order.timeline())) {
        continue;
      }
      TimelineInputs.Cut cut = cuts.get(binding.control().source().timeline().value());
      if (cut == null || cut.completeBefore() <= order.timestamp()) {
        return new Decision(
            TimelineIngress.Status.WAITING, "Control history does not close this input", List.of());
      }
      Set<String> allowed = new HashSet<>();
      List<Map<String, Object>> grants = new ArrayList<>();
      for (BlueId grant :
          binding.requirement().grants().stream()
              .sorted(Comparator.comparing(BlueId::value))
              .toList()) {
        Optional<TimelineAccessHistory.Result> protectedState =
            local == null ? Optional.empty() : local.at(binding.control(), grant, order);
        TimelineAccessHistory.Result result;
        if (protectedState.isPresent()) {
          result = protectedState.orElseThrow();
        } else {
          LanguageDocument initial = TimelineReadAccess.grant(selection.document(), grant);
          result =
              history.at(binding.control(), binding.discovery(), initial, cut.position(), order);
        }
        if (!result.caughtUp()) {
          return new Decision(
              TimelineIngress.Status.RETRY, "Verified access history is catching up", List.of());
        }
        if (result.state().isPresent()) {
          TimelineAccessHistory.State state = result.state().orElseThrow();
          if (state.timelines().contains(binding.requirement().timeline())) {
            allowed.addAll(state.keys());
          }
          grants.add(Map.of("initial", grant.value(), "executed", state.document().value()));
        } else {
          grants.add(Map.of("initial", grant.value(), "published", false));
        }
      }
      facts.add(
          Map.of(
              "timeline",
              binding.requirement().timeline().value(),
              "control",
              Map.of(
                  "timeline",
                  cut.timeline(),
                  "position",
                  java.math.BigInteger.valueOf(cut.position()),
                  "evidence",
                  cut.evidence(),
                  "completeBefore",
                  java.math.BigInteger.valueOf(cut.completeBefore())),
              "grants",
              grants,
              "requiredKeys",
              binding.requirement().keys().stream().sorted().toList()));
      if (!allowed.containsAll(binding.requirement().keys())) {
        return new Decision(
            TimelineIngress.Status.PENDING_ACCESS,
            "Required readers lack established access at this original input",
            List.copyOf(facts));
      }
    }
    return new Decision(
        TimelineIngress.Status.APPLIED, "Historical read access established", List.copyOf(facts));
  }

  @Override
  public void close() {
    history.close();
    policies.clear();
  }
}
