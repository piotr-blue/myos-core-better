package myos.better;

import static myos.better.OwnerGraphs.address;

import blue.Contracts;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Required source cuts for one tentative owner plan. Existing bindings keep their consumed
 * positions; fresh bindings start at the proven birth/checkpoint cut. No frontier is committed
 * here.
 */
record OwnerSourceBindings(
    Map<String, List<OwnerFrontiers.Source>> required,
    Map<String, Set<String>> observed,
    Map<String, Set<String>> targets) {
  static OwnerSourceBindings prepare(
      OwnerRecords records,
      Contracts.Execution.State after,
      OwnerPartition partition,
      OwnerAliases.Bindings aliases,
      Set<String> owned,
      OwnerFrontiers.Order birth,
      GraphTimelineIngress.OwnerDeclarations declarations) {
    Map<String, String> units = partition.units();
    Map<String, List<OwnerFrontiers.Source>> bindings = new LinkedHashMap<>();
    Map<String, Set<String>> observers = new LinkedHashMap<>(), targets = new LinkedHashMap<>();
    var timelineSources = declarations.sourcesByOwner(units);
    for (String representative : new TreeSet<>(owned.stream().map(units::get).toList())) {
      Set<String> members = new TreeSet<>(partition.members().get(representative));
      String owner = address(representative);
      var group = timelineSources.group(representative);
      observers.put(owner, group.observed());
      Set<String> identities = new HashSet<>();
      for (String member : members) {
        identities.add(after.rooted(member).blueId());
        var initial =
            after.member(member).at("/contracts/initialized/document").observation("exactId");
        if (!initial.isMissing()) {
          identities.add(initial.text());
        }
      }
      targets.put(owner, Set.copyOf(identities));
      Map<String, OwnerFrontiers.Source> existing = new HashMap<>();
      if (records.frontiers.find(records.read, owner) != null) {
        records.frontiers.sources(records.read, owner).forEach(s -> existing.put(s.id(), s));
      }
      List<OwnerFrontiers.Source> required = new ArrayList<>();
      for (var binding : group.sources().entrySet()) {
        String timeline = binding.getValue().source().timeline().value();
        String id = binding.getKey();
        var priorSource = existing.get(id);
        required.add(
            priorSource != null
                ? priorSource
                : new OwnerFrontiers.Source(
                    id,
                    OwnerFrontiers.Kind.TIMELINE,
                    timeline,
                    0,
                    Math.max(
                        records.frontiers.positionAt(timeline, birth),
                        group.checkpointPosition(id, records.frontiers))));
      }
      for (var edge : partition.outgoing().getOrDefault(representative, List.of())) {
        String child = address(units.get(edge.target()));
        String id = "embedding:" + edge.source() + ":" + edge.path() + ":" + edge.generation();
        var unchanged = existing.get(id);
        if (unchanged != null) {
          if (!child.equals(unchanged.source())) {
            throw new IllegalStateException("An unchanged embedding changed its owner");
          }
          required.add(unchanged);
          continue;
        }
        long position = 0;
        var view = records.view(aliases.owner(edge.target()), after.rooted(edge.target()).blueId());
        if (view != null) {
          position = Repository.number(view.getOrDefault("position", 0));
        }
        required.add(
            new OwnerFrontiers.Source(
                id, OwnerFrontiers.Kind.OWNER, child, edge.generation(), position));
      }
      bindings.put(owner, List.copyOf(required));
    }
    return new OwnerSourceBindings(
        Map.copyOf(bindings), Map.copyOf(observers), Map.copyOf(targets));
  }
}
