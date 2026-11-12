package myos.better;

import blue.Contracts;
import java.util.*;

/** Immutable address history published with its graph head, never inferred from caller content. */
final class GraphLineage {
  record Target(Map<String, Long> members) {
    Target {
      members = Map.copyOf(members);
    }
  }

  private final Repository repository;

  GraphLineage(AtomicStore store) {
    this(new Repository(store));
  }

  GraphLineage(Repository repository) {
    this.repository = repository;
  }

  String append(
      String account,
      String root,
      long version,
      Contracts.Reception before,
      Contracts.Reception after) {
    Map<String, String> previous = identities(before);
    Map<String, String> changed = new LinkedHashMap<>();
    identities(after)
        .forEach(
            (member, id) -> {
              if (!id.equals(previous.get(member))) changed.put(member, id);
            });
    return appendMembers(account, root, version, changed);
  }

  /** The protected changed-member set includes adopted descendants without opening siblings. */
  String appendMembers(String account, String root, long version, Map<String, String> identities) {
    Repository.Blobs blobs = repository.blobs(account);
    RadixIndex<Target> index = new RadixIndex<>(blobs, Target.class);
    Map<String, Target> changed = new HashMap<>();
    for (var member : identities.entrySet()) {
      Target found = changed.get(member.getValue());
      if (found == null) found = index.get(root, member.getValue());
      Map<String, Long> members = new TreeMap<>(found == null ? Map.of() : found.members());
      members.putIfAbsent(member.getKey(), version);
      changed.put(member.getValue(), new Target(members));
      if (index.get(root, "member:" + member.getKey()) == null)
        changed.put("member:" + member.getKey(), new Target(Map.of(member.getValue(), version)));
    }
    root = index.update(root, changed, Set.of());
    blobs.flush();
    return root;
  }

  /** Publishes the receiver address index atomically with this exact Core history version. */
  void publish(
      List<AtomicStore.Write> writes,
      Repository.Document document,
      Contracts.Reception before,
      Contracts.Reception after) {
    String previous =
        document.version() == 1
            ? ""
            : root(document.account(), document.id(), document.version() - 1);
    String next = append(document.account(), previous, document.version(), before, after);
    writes.add(
        AtomicStore.Write.put(
            key(document.account(), document.id(), document.version()), 0, Map.of("root", next)));
  }

  String root(String account, String document, long version) {
    var row = repository.store.get(key(account, document, version));
    if (row == null)
      throw new IllegalStateException("Missing canonical receiver history; replay is required");
    return (String) row.value().get("root");
  }

  private static AtomicStore.Key key(String account, String document, long version) {
    return Repository.key(account, document, "RECEIVERS#" + Repository.version(version));
  }

  Target find(String account, String root, String identity) {
    return new RadixIndex<>(repository.blobs(account), Target.class).get(root, identity);
  }

  private static Map<String, String> identities(Contracts.Reception reception) {
    Map<String, String> result = new TreeMap<>();
    if (reception != null)
      reception
          .channels()
          .forEach(c -> result.put(c.receiver().member(), c.receiver().documentId().value()));
    return result;
  }
}
