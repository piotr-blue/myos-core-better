package myos.better;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Account-visible registrations are independent of shared native document execution. */
final class CoreRegistrations {
  private final AtomicStore store;
  private final ConsoleDocuments documents;
  private final Set<AtomicStore.Key> remembered = new LinkedHashSet<>();

  CoreRegistrations(AtomicStore store, ConsoleDocuments documents) {
    this.store = store;
    this.documents = documents;
  }

  /** A repeated definition attaches to its existing instance; initialization is never replayed. */
  String register(String account, String command, blue.LanguageDocument source, Object name) {
    return register(account, command, source, name, null);
  }

  String register(
      String account,
      String command,
      blue.LanguageDocument source,
      Object name,
      String requestedEdition) {
    String identity = source.blueId().value();
    String fingerprint =
        Json.hash(
            Json.bytes(
                requestedEdition == null
                    ? List.of(identity, Objects.toString(name, ""))
                    : List.of(identity, Objects.toString(name, ""), requestedEdition)));
    var receipt = key(account, "COMMAND#" + Json.hash(Json.bytes(command)));
    var completed = store.get(receipt);
    if (completed != null) return replay(completed, fingerprint);
    var identityKey = identityKey(account, identity);
    var selected = store.get(identityKey);
    String creation = "core-definition-" + identity;
    if (selected == null) {
      String instance = documents.creationId(creation);
      store.commit(
          List.of(
              AtomicStore.Write.put(
                  identityKey, 0, Map.of("instance", instance, "creation", creation))),
          List.of());
      selected = store.get(identityKey);
      if (selected == null)
        throw new IllegalStateException("Registration identity was not retained");
    }
    String instance = (String) selected.value().get("instance");
    Map<String, Object> head;
    try {
      head = documents.head(account, instance);
    } catch (MissingDocument absent) {
      if (!(selected.value().get("creation") instanceof String pendingCreation)) throw absent;
      if (!instance.equals(documents.creationId(pendingCreation)))
        throw new ConsoleProblem(
            409,
            "execution_selection_conflict",
            "The pending registration belongs to a different execution mode. Resume it with that"
                + " mode.");
      // The deterministic command recovers an interrupted initialization. Existing instances
      // are inspected first: changing the default mode must never initialize a second graph.
      try {
        documents.create(account, pendingCreation, source);
        head = documents.head(account, instance);
      } catch (ConsoleProblem waiting) {
        if (!Set.of(
                "initialization_waiting",
                "initialization_resources",
                "initialization_failed",
                "initialization_paused")
            .contains(waiting.code)) throw waiting;
        var pending = new CoreInitializations(store).pending(account, instance);
        if (pending == null) head = documents.head(account, instance);
        else {
          if (!identity.equals(pending.intent().document())) throw waiting;
          head = Map.of("edition", OwnerRecords.EDITION);
        }
      }
    }
    if (requestedEdition != null && !requestedEdition.equals(head.get("edition")))
      throw new ConsoleProblem(
          409,
          "execution_selection_conflict",
          "This definition is already registered with a different execution selection.");
    var linkedKey = key(account, "ACTIVE#" + instance);
    String candidate = "registration-" + Json.hash(Json.bytes(List.of(account, command)));
    for (int attempt = 0; attempt < 8; attempt++) {
      var before = store.getAll(List.of(receipt, linkedKey));
      if (before.containsKey(receipt)) return replay(before.get(receipt), fingerprint);
      var linked = before.get(linkedKey);
      String handle = linked == null ? instance : (String) linked.value().get("handle");
      var root = store.get(RootDocuments.root(account, handle));
      List<AtomicStore.Write> writes = new ArrayList<>();
      List<AtomicStore.Check> checks = new ArrayList<>();
      if (root != null && !Boolean.TRUE.equals(root.value().get("deleted"))) {
        checks.add(new AtomicStore.Check(root.key(), root.version()));
      } else {
        handle = candidate;
        writes.add(
            AtomicStore.Write.put(
                RootDocuments.root(account, handle),
                0,
                Map.of(
                    "document",
                    handle,
                    "instance",
                    instance,
                    "name",
                    name == null ? instance : RuntimeConfiguration.text(name, "name"))));
        if (root != null) checks.add(new AtomicStore.Check(root.key(), root.version()));
      }
      writes.add(
          AtomicStore.Write.put(receipt, 0, Map.of("handle", handle, "fingerprint", fingerprint)));
      if (linked == null || !handle.equals(linked.value().get("handle")))
        writes.add(
            AtomicStore.Write.put(
                linkedKey, linked == null ? 0 : linked.version(), Map.of("handle", handle)));
      else checks.add(new AtomicStore.Check(linkedKey, linked.version()));
      if (store.commit(writes, checks)) return handle;
    }
    throw new ConsoleProblem(503, "storage_conflict", "Retry with the same Idempotency-Key");
  }

  void remember(String account, String identity, String handle) {
    String instance = documents.nativeId(account, handle);
    var key = identityKey(account, identity);
    synchronized (remembered) {
      if (remembered.contains(key)) return;
    }
    if (store.get(key) == null
        && !store.commit(
            List.of(AtomicStore.Write.put(key, 0, Map.of("instance", instance))), List.of()))
      return;
    synchronized (remembered) {
      remembered.add(key);
      while (remembered.size() > 2048) remembered.remove(remembered.iterator().next());
    }
  }

  private static String replay(AtomicStore.Row receipt, String fingerprint) {
    if (!fingerprint.equals(receipt.value().get("fingerprint")))
      throw new ConsoleProblem(
          409, "IDEMPOTENCY_CONFLICT", "The registration has different parameters");
    return (String) receipt.value().get("handle");
  }

  private static AtomicStore.Key identityKey(String account, String identity) {
    return key(account, "IDENTITY#" + new blue.BlueId(identity));
  }

  String find(String account, String identity) {
    var pinned = store.get(identityKey(account, identity));
    if (pinned == null) throw new MissingDocument();
    String instance = (String) pinned.value().get("instance");
    var active = store.get(key(account, "ACTIVE#" + instance));
    String handle = active == null ? instance : (String) active.value().get("handle");
    requireVisible(account, handle);
    return handle;
  }

  private static AtomicStore.Key key(String account, String sort) {
    return new AtomicStore.Key("CORE_REGISTRATIONS#" + Repository.identifier(account), sort);
  }

  boolean visible(String account, String document) {
    var root = store.get(RootDocuments.root(account, document));
    return root == null || !Boolean.TRUE.equals(root.value().get("deleted"));
  }

  void requireVisible(String account, String document) {
    if (!visible(account, document)) throw new MissingDocument();
  }

  boolean canUnlink(String account, String document) {
    return store.get(RootDocuments.root(account, document)) != null;
  }

  void requireRoot(String account, String document) {
    try {
      documents.head(account, document);
    } catch (MissingDocument missing) {
      if (new CoreInitializations(store).pending(account, documents.nativeId(account, document))
          == null) throw missing;
    }
    if (!canUnlink(account, document))
      throw new ConsoleProblem(409, "root_required", "Only root registrations can be removed");
  }

  Map<String, Object> unlink(String account, String document) {
    requireRoot(account, document);
    var key = RootDocuments.root(account, document);
    for (int attempt = 0; attempt < 4; attempt++) {
      var root = store.get(key);
      if (Boolean.TRUE.equals(root.value().get("deleted"))) return result(document);
      var removed = new LinkedHashMap<>(root.value());
      removed.put("deleted", true);
      if (store.commit(List.of(AtomicStore.Write.put(key, root.version(), removed)), List.of()))
        return result(document);
    }
    throw new ConsoleProblem(503, "storage_conflict", "Retry with the same Idempotency-Key");
  }

  private static Map<String, Object> result(String document) {
    return Map.of("sessionId", document, "processingChanged", false);
  }
}
