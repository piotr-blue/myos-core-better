package myos.better;

import blue.BlueId;
import blue.BluePackage;
import blue.LanguageProvider;
import blue.LanguageRecord;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Verified, explicitly deployed public definitions. These grant neither source access nor execution
 * authority.
 */
final class PublicPackages implements LanguageProvider {
  static final PublicPackages EMPTY = new PublicPackages(List.of());
  private final Map<BlueId, LanguageRecord> records;
  private final Set<String> identities;
  private final Map<String, Object> types;
  private final Map<BlueId, BluePackage> publications;

  PublicPackages(Collection<BluePackage> publications) {
    this(publications, Map.of());
  }

  PublicPackages(Collection<BluePackage> publications, Map<String, BluePackage> mounts) {
    Map<String, Object> names = new LinkedHashMap<>();
    mounts.forEach(
        (prefix, publication) ->
            publication
                .exports()
                .forEach(
                    (name, id) ->
                        names.putIfAbsent(
                            id.value(),
                            Map.of(
                                "name",
                                name,
                                "package",
                                prefix,
                                "packageId",
                                publication.blueId().value(),
                                "blueId",
                                id.value()))));
    types = Map.copyOf(names);
    Map<BlueId, BluePackage> roots = new LinkedHashMap<>();
    publications.forEach(publication -> roots.put(publication.blueId(), publication));
    this.publications = Map.copyOf(roots);
    Map<BlueId, LanguageRecord> content = new HashMap<>();
    for (BluePackage publication : publications)
      publication.records().forEach(content::putIfAbsent);
    records = Map.copyOf(content);
    identities =
        records.keySet().stream()
            .map(BlueId::value)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  Map<BlueId, BluePackage> publications() {
    return publications;
  }

  Map<String, Object> types() {
    return types;
  }

  boolean contains(BlueId id) {
    return records.containsKey(id);
  }

  boolean contains(String id) {
    return identities.contains(id);
  }

  Map<BlueId, LanguageRecord> records() {
    return records;
  }

  @Override
  public Optional<LanguageRecord> find(BlueId id) {
    return Optional.ofNullable(records.get(id));
  }
}
