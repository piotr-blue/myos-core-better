package myos.better;

import blue.BlueId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Protected, account-scoped identity membership. Publication shares the document transaction;
 * neither content availability nor an arbitrary BlueId establishes a managed document lineage.
 */
final class DocumentLineage {
  private static final String FORMAT = "myos.document-lineage/1";
  private final Repository repository;
  private final AtomicStore store;

  record Publication(Repository.Document document, BlueId initial) {
    Publication {
      Objects.requireNonNull(document);
      if (document.version() == 1 != (initial != null)) {
        throw new IllegalArgumentException("Only a new document supplies its initial identity");
      }
    }
  }

  record Membership(
      String document, BlueId identity, long introducedAt, String snapshot, String edition) {}

  DocumentLineage(Repository repository) {
    this.repository = Objects.requireNonNull(repository);
    store = repository.store;
  }

  /** Adds immutable index facts only beside their exact STATE and historical publication. */
  boolean appendTo(List<AtomicStore.Write> writes, List<Publication> publications) {
    Map<AtomicStore.Key, Publication> roots = new LinkedHashMap<>();
    Map<AtomicStore.Key, Membership> memberships = new LinkedHashMap<>();
    for (Publication publication : publications) {
      Repository.Document document = publication.document();
      AtomicStore.Key state = Repository.key(document.account(), document.id(), "STATE");
      AtomicStore.Key history =
          Repository.key(
              document.account(), document.id(), "V#" + Repository.version(document.version()));
      boolean stateFound = false, historyFound = false;
      for (AtomicStore.Write write : writes) {
        if (write.key().equals(state)) {
          stateFound =
              write.next() != null
                  && write.next().version() == document.version()
                  && Repository.number(write.next().value().get("documentVersion"))
                      == document.version()
                  && document.checkpoint().equals(write.next().value().get("checkpoint"));
        }
        if (write.key().equals(history)) {
          historyFound =
              write.expected() == 0
                  && write.next() != null
                  && Repository.number(write.next().value().get("documentVersion"))
                      == document.version()
                  && document.checkpoint().equals(write.next().value().get("checkpoint"));
        }
      }
      if (!stateFound || !historyFound) {
        throw new IllegalArgumentException(
            "Lineage requires the exact state and history publication");
      }
      AtomicStore.Key root = rootKey(document.account(), document.id());
      if (roots.putIfAbsent(root, publication) != null) {
        throw new IllegalArgumentException("Duplicate document publication");
      }
      List<BlueId> identities = new ArrayList<>();
      if (publication.initial() != null) {
        identities.add(publication.initial());
      }
      identities.add(new BlueId(document.localBlueId()));
      for (BlueId identity : identities) {
        Membership member =
            new Membership(
                document.id(),
                identity,
                document.version(),
                (String) document.checkpoint().get("identity"),
                (String) document.checkpoint().get("edition"));
        memberships.put(memberKey(document.account(), document.id(), identity), member);
      }
    }
    Set<AtomicStore.Key> keys = new LinkedHashSet<>(roots.keySet());
    keys.addAll(memberships.keySet());
    Map<AtomicStore.Key, AtomicStore.Row> existing = store.getAll(List.copyOf(keys));
    List<AtomicStore.Write> additions = new ArrayList<>();
    for (var item : roots.entrySet()) {
      Repository.Document document = item.getValue().document();
      AtomicStore.Row found = existing.get(item.getKey());
      if (document.version() == 1) {
        if (found != null) {
          return false;
        }
        additions.add(
            AtomicStore.Write.put(
                item.getKey(),
                0,
                Map.of(
                    "format",
                    FORMAT,
                    "initial",
                    item.getValue().initial().value(),
                    "snapshot",
                    document.checkpoint().get("identity"),
                    "edition",
                    document.checkpoint().get("edition"))));
      } else {
        if (found == null) {
          throw new IllegalStateException(
              "Document has no established lineage; explicit migration is required");
        }
        validateRoot(found, item.getKey());
        if (found.version() > document.version() - 1) {
          return false;
        }
        if (found.version() != document.version() - 1) {
          throw new IllegalStateException(
              "Lineage coverage has a history gap; explicit migration is required");
        }
        additions.add(AtomicStore.Write.put(item.getKey(), found.version(), found.value()));
      }
    }
    for (var item : memberships.entrySet()) {
      AtomicStore.Row found = existing.get(item.getKey());
      Membership proposed = item.getValue();
      if (found == null) {
        additions.add(AtomicStore.Write.put(item.getKey(), 0, value(proposed)));
      } else {
        Membership retained = read(found, item.getKey());
        if (!retained.document().equals(proposed.document())
            || !retained.identity().equals(proposed.identity())) {
          throw new IllegalStateException("Conflicting lineage membership");
        }
        if (retained.introducedAt() > proposed.introducedAt()) {
          return false;
        }
      }
    }
    writes.addAll(additions);
    return true;
  }

  /** Resolves only established local history at or before this receiver version. */
  Optional<Membership> find(String account, String document, long version, BlueId identity) {
    return view(account, document, version).find(identity);
  }

  /** One receiving cut: coverage is read before membership, and later versions stay excluded. */
  View view(String account, String document, long version) {
    if (version < 1) {
      throw new IllegalArgumentException("Positive receiver version required");
    }
    AtomicStore.Key key = rootKey(account, document);
    AtomicStore.Row root = store.get(key);
    if (root != null) {
      validateRoot(root, key);
      if (root.version() < version) {
        throw new IllegalStateException("Requested receiver history is not fully indexed");
      }
    }
    return new View(account, document, version, root);
  }

  final class View {
    private final String account, document;
    private final long version;
    private final AtomicStore.Row root;
    private final Map<BlueId, Optional<Membership>> members = new HashMap<>();
    private final Map<Long, Repository.Document> publications = new HashMap<>();

    private View(String account, String document, long version, AtomicStore.Row root) {
      this.account = account;
      this.document = document;
      this.version = version;
      this.root = root;
    }

    Optional<Membership> find(BlueId identity) {
      return members.computeIfAbsent(Objects.requireNonNull(identity), this::resolve);
    }

    private Optional<Membership> resolve(BlueId identity) {
      if (root == null) {
        return Optional.empty();
      }
      AtomicStore.Key key = memberKey(account, document, identity);
      // Coverage was read first. A later membership can only be accepted if it belongs to
      // this frozen version; absence cannot be combined with a newer coverage observation.
      AtomicStore.Row row = store.get(key);
      if (row == null) {
        return Optional.empty();
      }
      Membership member = read(row, key);
      if (member.introducedAt() > version) {
        return Optional.empty();
      }
      Repository.Document published =
          publications.computeIfAbsent(
              member.introducedAt(), v -> repository.load(account, document, v));
      verifyPublication(member, root, published);
      return Optional.of(member);
    }
  }

  /** Identity discovery is scoped to the managed account, not the content store. */
  boolean known(String account, BlueId identity) {
    AtomicStore.Page page = store.query(partition(account, identity), "D#", null, 1);
    if (page.rows().isEmpty()) {
      return false;
    }
    AtomicStore.Row row = page.rows().getFirst();
    Membership member = read(row, memberKey(account, row.key().sort().substring(2), identity));
    AtomicStore.Key rootKey = rootKey(account, member.document());
    AtomicStore.Row root = store.get(rootKey);
    if (root == null) {
      throw new IllegalStateException("Membership has no document lineage");
    }
    validateRoot(root, rootKey);
    if (root.version() < member.introducedAt()) {
      throw new IllegalStateException("Membership exceeds indexed document history");
    }
    verifyPublication(account, member, root);
    return member.identity().equals(identity);
  }

  /** Bounded discovery hints only; receivers still verify their own membership and permissions. */
  Optional<Set<String>> notificationTargets(
      String account, Collection<BlueId> identities, int maximum) {
    Set<String> documents = new HashSet<>();
    for (BlueId identity : new HashSet<>(identities)) {
      String after = null;
      do {
        AtomicStore.Page page = store.query(partition(account, identity), "D#", after, maximum + 1);
        for (AtomicStore.Row row : page.rows()) {
          Membership member =
              read(row, memberKey(account, row.key().sort().substring(2), identity));
          documents.add(member.document());
          if (documents.size() > maximum) {
            return Optional.empty();
          }
        }
        after = page.after();
      } while (after != null);
    }
    return Optional.of(Set.copyOf(documents));
  }

  BlueId initial(String account, String document) {
    AtomicStore.Key key = rootKey(account, document);
    AtomicStore.Row root = store.get(key);
    if (root == null) {
      throw new IllegalStateException("Missing established document lineage");
    }
    validateRoot(root, key);
    Repository.Document first = repository.load(account, document, 1);
    if (first.version() != 1
        || !first.checkpoint().get("identity").equals(root.value().get("snapshot"))
        || !first.checkpoint().get("edition").equals(root.value().get("edition"))) {
      throw new IllegalStateException("Lineage root disagrees with its publication");
    }
    return new BlueId((String) root.value().get("initial"));
  }

  private void verifyPublication(String account, Membership member, AtomicStore.Row root) {
    Repository.Document published =
        repository.load(account, member.document(), member.introducedAt());
    verifyPublication(member, root, published);
  }

  private static void verifyPublication(
      Membership member, AtomicStore.Row root, Repository.Document published) {
    if (published.version() != member.introducedAt()
        || !published.checkpoint().get("identity").equals(member.snapshot())
        || !published.checkpoint().get("edition").equals(member.edition())) {
      throw new IllegalStateException("Lineage membership disagrees with its history/runtime");
    }
    if (published.localBlueId().equals(member.identity().value())) {
      return;
    }
    if (member.introducedAt() != 1
        || !root.value().get("initial").equals(member.identity().value())
        || !root.value().get("snapshot").equals(member.snapshot())
        || !root.value().get("edition").equals(member.edition())) {
      throw new IllegalStateException("Identity did not belong to the recorded publication");
    }
  }

  private static void validateRoot(AtomicStore.Row row, AtomicStore.Key key) {
    Map<String, Object> value = row.value();
    if (!row.key().equals(key)
        || !FORMAT.equals(value.get("format"))
        || !value.keySet().equals(Set.of("format", "initial", "snapshot", "edition"))
        || !(value.get("snapshot") instanceof String snapshot)
        || snapshot.isEmpty()
        || !(value.get("edition") instanceof String edition)
        || edition.isEmpty()) {
      throw new IllegalStateException("Corrupt document lineage");
    }
    new BlueId((String) value.get("initial"));
  }

  private static Membership read(AtomicStore.Row row, AtomicStore.Key expected) {
    Map<String, Object> value = row.value();
    if (!row.key().equals(expected)
        || row.version() != 1
        || !FORMAT.equals(value.get("format"))
        || !value
            .keySet()
            .equals(
                Set.of("format", "document", "identity", "introducedAt", "snapshot", "edition"))) {
      throw new IllegalStateException("Corrupt lineage membership");
    }
    Membership result =
        new Membership(
            Repository.identifier((String) value.get("document")),
            new BlueId((String) value.get("identity")),
            Repository.number(value.get("introducedAt")),
            (String) value.get("snapshot"),
            (String) value.get("edition"));
    if (result.introducedAt() < 1
        || result.snapshot() == null
        || result.snapshot().isEmpty()
        || result.edition() == null
        || result.edition().isEmpty()
        || !expected.sort().equals("D#" + result.document())
        || !expected.partition().endsWith("#L#" + result.identity())) {
      throw new IllegalStateException("Misbound lineage membership");
    }
    return result;
  }

  private static Map<String, Object> value(Membership member) {
    return Map.of(
        "format",
        FORMAT,
        "document",
        member.document(),
        "identity",
        member.identity().value(),
        "introducedAt",
        member.introducedAt(),
        "snapshot",
        member.snapshot(),
        "edition",
        member.edition());
  }

  private static AtomicStore.Key rootKey(String account, String document) {
    return Repository.key(account, document, "LINEAGE");
  }

  private static String partition(String account, BlueId identity) {
    return "A#" + Repository.identifier(account) + "#L#" + Objects.requireNonNull(identity);
  }

  private static AtomicStore.Key memberKey(String account, String document, BlueId identity) {
    return new AtomicStore.Key(
        partition(account, identity), "D#" + Repository.identifier(document));
  }
}
