package myos.better;

import blue.Contracts;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Immutable display descriptions published with a graph; never execution or admission authority.
 */
final class GraphOperationCatalogue {
  private static final String FORMAT = "myos.operation-catalogue/1";
  private final Repository repository;

  GraphOperationCatalogue(AtomicStore store) {
    this(new Repository(store));
  }

  GraphOperationCatalogue(Repository repository) {
    this.repository = repository;
  }

  String publish(
      String account,
      String root,
      Contracts.Graph before,
      Contracts.Graph after,
      Contracts.Reception previousReception,
      Contracts.Reception reception,
      Function<String, List<Contracts.Operation>> operations) {
    var previous = identities(before);
    var current = identities(after);
    var previousChannels = group(previousReception);
    var channels = group(reception);
    var blobs = repository.blobs(account);
    var index = new RadixIndex<>(blobs, String.class);
    var blocks = new ContentBlocks(blobs);
    Map<String, String> changes = new HashMap<>();
    for (var entry : current.entrySet()) {
      String member = entry.getKey();
      var selectedChannels = channels.getOrDefault(member, List.of());
      if (!root.isEmpty()
          && entry.getValue().equals(previous.get(member))
          && selectedChannels.equals(previousChannels.getOrDefault(member, List.of()))) continue;
      var value =
          describe(entry.getValue(), operations.apply(member), describeChannels(selectedChannels));
      byte[] bytes = Json.bytes(Map.of("format", FORMAT, "view", value));
      // A display projection must not narrow the set of valid canonical documents. Oversized
      // descriptions keep the ordinary inspection path instead of blocking publication.
      if (bytes.length > 16 * 1024 * 1024)
        bytes =
            Json.bytes(
                Map.of(
                    "format",
                    FORMAT,
                    "view",
                    Map.of("target", entry.getValue(), "deferred", true)));
      changes.put(member, blocks.put(bytes));
    }
    Set<String> removed = new HashSet<>(previous.keySet());
    removed.removeAll(current.keySet());
    String result = index.update(root, changes, removed);
    blobs.flush();
    return result;
  }

  String updateViews(String account, String root, Map<String, Object> views) {
    var blobs = repository.blobs(account);
    var blocks = new ContentBlocks(blobs);
    Map<String, String> changed = new HashMap<>();
    views.forEach(
        (member, view) ->
            changed.put(member, blocks.put(Json.bytes(Map.of("format", FORMAT, "view", view)))));
    String next = new RadixIndex<>(blobs, String.class).update(root, changed, Set.of());
    blobs.flush();
    return next;
  }

  Map<String, Object> read(String account, String root, String member, String expected) {
    var blobs = repository.blobs(account);
    String record = new RadixIndex<>(blobs, String.class).get(root, member);
    if (record == null)
      throw new IllegalStateException("Published operation catalogue is incomplete");
    var envelope = Json.object(new ContentBlocks(blobs).get(record));
    if (!FORMAT.equals(envelope.get("format")))
      throw new IllegalStateException("Unsupported operation catalogue format");
    var value = RuntimeConfiguration.object(envelope.get("view"), "view");
    if (!expected.equals(value.get("target")))
      throw new IllegalStateException("Operation catalogue differs from its published member");
    if (Boolean.TRUE.equals(value.get("deferred"))) return null;
    return RecordBodies.immutable(value);
  }

  static Map<String, Object> describe(
      String target, List<Contracts.Operation> operations, List<Map<String, Object>> channels) {
    List<Object> items = new ArrayList<>();
    for (var op : operations) {
      Map<String, Object> item =
          new LinkedHashMap<>(Map.of("name", op.name(), "channel", op.channel()));
      op.request().ifPresent(value -> item.put("request", value));
      op.description().ifPresent(value -> item.put("description", value));
      items.add(item);
    }
    return Map.of("target", target, "native", true, "items", items, "channels", channels);
  }

  static List<Map<String, Object>> channels(Contracts.Reception reception, String member) {
    return describeChannels(
        reception.channels().stream()
            .filter(channel -> member.equals(channel.receiver().member()))
            .toList());
  }

  private static List<Map<String, Object>> describeChannels(
      List<Contracts.ReceivingChannel> channels) {
    List<Map<String, Object>> result = new ArrayList<>();
    for (var channel : channels) {
      Map<String, Object> item =
          new LinkedHashMap<>(
              Map.of(
                  "name",
                  channel.receiver().channel(),
                  "selectors",
                  channel.selectors(),
                  "observed",
                  channel.observed()));
      channel.headerIdentities().forEach((name, identity) -> item.put(name, identity.value()));
      item.put("generation", channel.receiver().generation());
      result.add(item);
    }
    return result;
  }

  private static Map<String, List<Contracts.ReceivingChannel>> group(
      Contracts.Reception reception) {
    Map<String, List<Contracts.ReceivingChannel>> result = new HashMap<>();
    if (reception != null)
      for (var channel : reception.channels())
        result
            .computeIfAbsent(channel.receiver().member(), ignored -> new ArrayList<>())
            .add(channel);
    return result;
  }

  private static Map<String, String> identities(Contracts.Graph graph) {
    Map<String, String> result = new HashMap<>();
    if (graph != null)
      graph.members().forEach(member -> result.put(member.key(), member.blueId().value()));
    return result;
  }
}
