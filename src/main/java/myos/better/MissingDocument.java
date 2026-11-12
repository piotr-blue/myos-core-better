package myos.better;

/** An absent tenant-scoped document is a client-visible miss, not a processing failure. */
final class MissingDocument extends IllegalArgumentException {
  private static final long serialVersionUID = 1L;

  MissingDocument() {
    super("Unknown document");
  }
}
