package myos.better;

import blue.Contracts;
import java.util.*;

/** Durable owner identity is separate from a member key in a canonical rooted invocation. */
final class OwnerAliases {
  record Bindings(Map<String, String> members, Map<String, String> births) {
    String owner(String member) {
      return members.getOrDefault(member, member);
    }

    void publish(OwnerMetadata.Transaction tx) {
      members.forEach(
          (member, owner) -> {
            put(tx, "owner-alias", member, owner);
            put(tx, "owner-address", OwnerGraphs.address(member), OwnerGraphs.address(owner));
          });
      births.forEach((identity, owner) -> put(tx, "owner-birth", identity, owner));
    }

    private static void put(OwnerMetadata.Transaction tx, String kind, String id, String value) {
      String key = OwnerRecords.key(kind, id);
      String previous = tx.get(key);
      if (previous != null && !previous.equals(value))
        throw new IllegalStateException("Conflicting durable owner identity");
      tx.put(key, value);
    }
  }

  static Bindings fresh(
      OwnerRecords records, Contracts.Execution.Outcome outcome, Contracts.Embedding edge) {
    Map<String, String> aliases = new HashMap<>();
    for (var member : outcome.after().members()) {
      var owner = records.owner(member.key());
      if (!owner.equals(member.key())) aliases.put(member.key(), owner);
    }
    String birth =
        JsonText.of(
            List.of(records.owner(edge.source()), edge.path(), edge.generation(), outcome.event()));
    return new Bindings(Map.copyOf(aliases), Map.of(birth, edge.target()));
  }

  static Bindings prepare(OwnerRecords records, Contracts.Execution.Outcome outcome) {
    Map<String, String> aliases = new HashMap<>();
    Set<String> previous = new HashSet<>();
    outcome.before().members().forEach(member -> previous.add(member.key()));
    for (var member : outcome.after().members()) {
      String owner = records.owner(member.key());
      if (!owner.equals(member.key())) aliases.put(member.key(), owner);
    }
    String cause = outcome.event() == null ? outcome.before().identity() : outcome.event();
    var edges = outcome.after().embeddings();
    for (int pass = 0; pass <= edges.size(); pass++) {
      boolean changed = false;
      for (var edge : edges) {
        if (previous.contains(edge.target())) continue;
        String identity =
            JsonText.of(
                List.of(
                    aliases.getOrDefault(edge.source(), edge.source()),
                    edge.path(),
                    edge.generation(),
                    cause));
        String owner = records.read.get(OwnerRecords.key("owner-birth", identity));
        if (owner != null && !owner.equals(aliases.getOrDefault(edge.target(), edge.target()))) {
          aliases.put(edge.target(), owner);
          changed = true;
        }
      }
      if (!changed) break;
      if (pass == edges.size()) throw new IllegalStateException("Unstable owner identity");
    }
    Map<String, String> births = new HashMap<>();
    for (var edge : edges) {
      if (previous.contains(edge.target())) continue;
      String identity =
          JsonText.of(
              List.of(
                  aliases.getOrDefault(edge.source(), edge.source()),
                  edge.path(),
                  edge.generation(),
                  cause));
      births.put(identity, aliases.getOrDefault(edge.target(), edge.target()));
    }
    return new Bindings(Map.copyOf(aliases), Map.copyOf(births));
  }
}
