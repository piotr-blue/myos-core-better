package myos.better;

/** Tenant-scoped immutable content; staged pages become visible through an atomic root update. */
interface BlobStore {
  // At 128 KiB per page, a full batch is bounded to 12.5 MiB before SDK overhead.
  int READ_BATCH_SIZE = 100;

  /** Optional reuse of verified String index pages; never supplies a current root or authority. */
  default RadixIndex.Page<String> indexPage(String identity) {
    return null;
  }

  default void indexPage(String identity, RadixIndex.Page<String> page, int encodedBytes) {}

  /** Verified committed ordered pages; never a substitute for selecting a current root. */
  default OrderedIndex.Page orderedPage(String identity) {
    return null;
  }

  default void orderedPage(String identity, OrderedIndex.Page page, int encodedBytes) {}

  String put(byte[] value);

  byte[] get(String identity);

  /** Optional bounded read-ahead; content still passes ordinary checks when it is opened. */
  default void prefetch(java.util.List<String> identities) {}
}
