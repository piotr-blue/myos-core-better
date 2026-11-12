package myos.better;

import blue.Contracts;
import java.util.*;
import java.util.function.UnaryOperator;

/** Invocation-local ownership indexes; canonical member identities and edge order are retained. */
record OwnerPartition(
    Map<String, String> units,
    Map<String, List<String>> members,
    Map<String, List<Contracts.Embedding>> outgoing) {
  static OwnerPartition of(
      List<List<String>> components,
      List<Contracts.Embedding> embeddings,
      UnaryOperator<String> owner) {
    Map<String, String> units = new HashMap<>();
    Map<String, List<String>> members = new HashMap<>();
    for (List<String> component : components) {
      String representative = component.stream().map(owner).min(String::compareTo).orElseThrow();
      members.computeIfAbsent(representative, ignored -> new ArrayList<>()).addAll(component);
      for (String member : component) units.put(member, representative);
    }
    members.replaceAll((representative, group) -> group.stream().sorted().toList());
    Map<String, List<Contracts.Embedding>> outgoing = new HashMap<>();
    for (Contracts.Embedding edge : embeddings) {
      String source = Objects.requireNonNull(units.get(edge.source()));
      String target = Objects.requireNonNull(units.get(edge.target()));
      if (!source.equals(target))
        outgoing.computeIfAbsent(source, ignored -> new ArrayList<>()).add(edge);
    }
    outgoing.replaceAll((unit, edges) -> List.copyOf(edges));
    return new OwnerPartition(Map.copyOf(units), Map.copyOf(members), Map.copyOf(outgoing));
  }
}
