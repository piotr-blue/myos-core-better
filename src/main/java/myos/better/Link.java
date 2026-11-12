package myos.better;

/** A pinned child version and attachment generation, independent of the parent's sibling count. */
record Link(String document, long version, String generation) {
  Link {
    if (document == null
        || document.isBlank()
        || version < 0
        || generation == null
        || generation.isBlank()) {
      throw new IllegalArgumentException("Invalid embedding link");
    }
  }
}
