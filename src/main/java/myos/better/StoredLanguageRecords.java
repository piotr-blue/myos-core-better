package myos.better;

import blue.BlueId;
import java.util.Objects;
import java.util.function.Function;

/** Links to committed exact records in one account; callers still select the required closure. */
record StoredLanguageRecords(String account, AtomicStore store, Function<BlueId, Location> find) {
  record Location(String address, boolean blocks, String inline) {
    Location(String address, boolean blocks) {
      this(address, blocks, null);
    }

    Location {
      if (inline == null) {
        if (address == null || !address.matches("[a-f0-9]{64}"))
          throw new IllegalArgumentException("Invalid immutable record address");
      } else if (address != null || blocks) {
        throw new IllegalArgumentException("A packed record has no separate block address");
      }
    }

    static Location inline(String record) {
      return new Location(null, false, Objects.requireNonNull(record));
    }

    String link(ContentBlocks destination) {
      if (inline != null)
        return destination.put(inline.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return blocks ? address : destination.wrap(address);
    }

    byte[] read(Repository.Blobs blobs) {
      if (inline != null) return inline.getBytes(java.nio.charset.StandardCharsets.UTF_8);
      return blocks ? new ContentBlocks(blobs).get(address) : blobs.get(address);
    }
  }

  StoredLanguageRecords {
    Repository.identifier(account);
    Objects.requireNonNull(store);
    Objects.requireNonNull(find);
  }

  static StoredLanguageRecords empty(String account, AtomicStore store) {
    return new StoredLanguageRecords(account, store, id -> null);
  }

  void requireDestination(String expected, AtomicStore destination) {
    if (!account.equals(expected) || store != destination)
      throw new IllegalArgumentException("Record storage scope mismatch");
  }

  boolean contains(BlueId id) {
    return find.apply(id) != null;
  }

  blue.LanguageProvider provider() {
    Repository.Blobs blobs = new Repository(store).blobs(account);
    return id -> {
      Location location = find.apply(id);
      if (location == null) return java.util.Optional.empty();
      byte[] body = location.read(blobs);
      return java.util.Optional.of(
          blue.LanguageRecord.fromJson(new String(body, java.nio.charset.StandardCharsets.UTF_8)));
    };
  }
}
