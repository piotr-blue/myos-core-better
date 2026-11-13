package myos.better;

import blue.Contracts;
import blue.NativeChannel;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Original-source positions carried with an explicitly authenticated, immutable document fork. */
final class TimelineCheckpoints {
  private static final String INPUT = "myos.timeline-inputs/1";
  private static final String FORK = "myos.timeline-checkpoint-fork/1";

  /**
   * A protected checkpoint has no corresponding immutable host history; operator repair is
   * required.
   */
  static final class MissingHistory extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    MissingHistory(String document) {
      super("Missing original Timeline checkpoint history for " + document);
    }
  }

  private TimelineCheckpoints() {}

  static TimelineInputs.Order find(
      Repository repository,
      String account,
      String document,
      long version,
      long revision,
      NativeChannel.Checkpoint checkpoint) {
    AtomicStore.Row indexed =
        repository.store.get(
            Repository.key(account, document, "TIMELINE_EVENT#" + checkpoint.eventId()));
    if (indexed == null) {
      return inherited(repository, account, document, checkpoint);
    }
    if (indexed.version() != 1
        || !INPUT.equals(indexed.value().get("format"))
        || !checkpoint.eventId().value().equals(indexed.value().get("inputIdentity"))) {
      throw new IllegalStateException("Invalid original Timeline checkpoint history");
    }
    long position = Repository.number(indexed.value().get("revision"));
    long published = Repository.number(indexed.value().get("documentVersion"));
    if (position < 1 || position > revision || published < 1 || published > version) {
      throw new IllegalStateException("Timeline checkpoint belongs to a different receiving view");
    }
    AtomicStore.Row history =
        repository.store.get(
            Repository.key(account, document, "TIMELINE_INPUT#" + Repository.version(position)));
    if (history == null || history.version() != 1 || !history.value().equals(indexed.value())) {
      throw new IllegalStateException("Timeline checkpoint disagrees with its publication");
    }
    return order(indexed.value(), checkpoint);
  }

  static void inherit(
      Repository repository,
      List<AtomicStore.Write> writes,
      Repository.Document source,
      Repository.Document target,
      Contracts.Reception reception) {
    if (!source.account().equals(target.account())
        || target.version() != 1
        || !source.localBlueId().equals(target.localBlueId())) {
      throw new IllegalArgumentException("Timeline history requires the exact same-account fork");
    }
    Set<NativeChannel.Checkpoint> checkpoints = new HashSet<>();
    reception
        .channels()
        .forEach(channel -> channel.receiver().checkpoint().ifPresent(checkpoints::add));
    for (NativeChannel.Checkpoint checkpoint : checkpoints) {
      TimelineInputs.Order original =
          find(
              repository,
              source.account(),
              source.id(),
              source.version(),
              Long.MAX_VALUE,
              checkpoint);
      writes.add(
          AtomicStore.Write.put(
              Repository.key(
                  target.account(), target.id(), "TIMELINE_FORK#" + checkpoint.eventId()),
              0,
              Map.of(
                  "format",
                  FORK,
                  "inputIdentity",
                  checkpoint.eventId().value(),
                  "input",
                  original,
                  "initialBlueId",
                  target.localBlueId(),
                  "source",
                  source.id(),
                  "sourceVersion",
                  source.version())));
    }
  }

  private static TimelineInputs.Order inherited(
      Repository repository, String account, String document, NativeChannel.Checkpoint checkpoint) {
    AtomicStore.Row fork =
        repository.store.get(
            Repository.key(account, document, "TIMELINE_FORK#" + checkpoint.eventId()));
    if (fork == null
        || fork.version() != 1
        || !FORK.equals(fork.value().get("format"))
        || !checkpoint.eventId().value().equals(fork.value().get("inputIdentity"))) {
      throw new MissingHistory(document);
    }
    Repository.Document initial = repository.load(account, document, 1);
    if (!initial.localBlueId().equals(fork.value().get("initialBlueId"))) {
      throw new IllegalStateException("Timeline fork disagrees with its initial document");
    }
    return order(fork.value(), checkpoint);
  }

  private static TimelineInputs.Order order(
      Map<String, Object> value, NativeChannel.Checkpoint checkpoint) {
    TimelineInputs.Order order =
        Json.MAPPER.convertValue(value.get("input"), TimelineInputs.Order.class);
    if (order.timestamp() != checkpoint.timestamp()) {
      throw new IllegalStateException("Timeline checkpoint timestamp mismatch");
    }
    return order;
  }
}
