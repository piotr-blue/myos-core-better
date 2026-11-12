package myos.better;

/** Bounded, resumable inventory used only for the public-profile projection migration. */
interface PublicDirectoryInventory {
  /** At most 64 source rows; the cursor also advances when a filtered page is empty. */
  AtomicStore.Page publicDirectoryPage(String after);
}
