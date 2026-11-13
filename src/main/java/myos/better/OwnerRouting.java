package myos.better;

import static myos.better.FrontierRows.get;
import static myos.better.FrontierRows.key;
import static myos.better.FrontierRows.prefix;
import static myos.better.FrontierRows.put;
import static myos.better.FrontierRows.scan;
import static myos.better.FrontierRows.sequence;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import myos.better.FrontierRows.Binding;
import myos.better.OwnerFrontiers.Kind;
import myos.better.OwnerFrontiers.Order;
import myos.better.OwnerFrontiers.Source;

/** Transaction-local index of verified event classifications and subscribing owner bindings. */
final class OwnerRouting {
  private OwnerRouting() {}

  record Reverse(String owner, String binding) {}

  enum Interest {
    RAW_EVENTS("true"),
    OPERATIONS("false"),
    COVERAGE("coverage");

    final String stored;

    Interest(String stored) {
      this.stored = stored;
    }
  }

  private record Input(boolean operation, String target) {}

  static boolean classify(
      OwnerMetadata.Transaction tx,
      String timeline,
      String entry,
      boolean operation,
      String target) {
    String key = key("classification", timeline, entry);
    Input prior = get(tx, key, Input.class);
    if (prior != null
        && prior.target() != null
        && target != null
        && !prior.target().equals(target)) {
      throw new IllegalStateException("Contradicting verified operation target");
    }
    Input value =
        new Input(
            prior == null ? operation : prior.operation() && operation,
            prior != null && prior.target() != null ? prior.target() : target);
    if (!value.operation() && value.target() != null) {
      throw new IllegalStateException("Contradicting verified classification");
    }
    if (value.equals(prior)) {
      return false;
    }
    put(tx, key, value);
    return true;
  }

  static void interest(
      OwnerMetadata.Transaction tx, String owner, String binding, Interest interest) {
    if (!interest.stored.equals(tx.get(key("interest", owner, binding)))) {
      tx.put(
          key("interest-sequence", owner, binding),
          Long.toString(sequence(tx, "accepted-sequence")));
    }
    tx.put(key("interest", owner, binding), interest.stored);
    indexInterest(tx, owner, binding);
  }

  static void target(OwnerMetadata.Transaction tx, String owner, String identity) {
    if (tx.get(key("target", owner, identity)) == null) {
      tx.put(key("target-sequence", owner), Long.toString(sequence(tx, "accepted-sequence")));
    }
    tx.put(key("target", owner, identity), "1");
    tx.put(key("target-owner", identity, owner), owner);
  }

  static boolean relevant(OwnerMetadata.Transaction tx, String owner, String binding, Order order) {
    String interest = tx.get(key("interest", owner, binding));
    if (interest == null || Boolean.parseBoolean(interest)) {
      return true;
    }
    Input input = get(tx, key("classification", order.timeline(), order.entry()), Input.class);
    if (input == null) {
      return true;
    }
    if (!input.operation()) {
      return false;
    }
    return input.target() == null || tx.get(key("target", owner, input.target())) != null;
  }

  static void unindexInterest(OwnerMetadata.Transaction tx, String owner, Source source) {
    if (source.kind() == Kind.TIMELINE) {
      tx.remove(key("timeline-observer", source.source(), owner, source.id()));
    }
  }

  static void indexInterest(OwnerMetadata.Transaction tx, String owner, String binding) {
    Binding selected = get(tx, key("source", owner, binding), Binding.class);
    if (selected == null || selected.source().kind() != Kind.TIMELINE) {
      return;
    }
    String interest = tx.get(key("interest", owner, binding));
    String key = key("timeline-observer", selected.source().source(), owner, binding);
    if (interest == null || Boolean.parseBoolean(interest)) {
      put(tx, key, new Reverse(owner, binding));
    } else {
      tx.remove(key);
    }
  }

  static List<Reverse> consumers(OwnerMetadata.Transaction tx, Order order) {
    Input input = get(tx, key("classification", order.timeline(), order.entry()), Input.class);
    if (input == null || input.operation() && input.target() == null) {
      return scan(tx, prefix("timeline-consumer", order.timeline()), Reverse.class);
    }
    Set<Reverse> selected =
        new LinkedHashSet<>(scan(tx, prefix("timeline-observer", order.timeline()), Reverse.class));
    if (input.operation()) {
      String after = null;
      while (true) {
        var page = tx.page(prefix("target-owner", input.target()), after, 128);
        for (var row : page) {
          selected.addAll(
              scan(tx, prefix("timeline-consumer", order.timeline(), row.value()), Reverse.class));
        }
        if (page.size() < 128) {
          break;
        }
        after = page.getLast().key();
      }
    }
    return List.copyOf(selected);
  }
}
