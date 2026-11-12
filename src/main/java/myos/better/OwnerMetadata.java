package myos.better;

import java.util.List;

/** Owner scheduling metadata retains its protected namespace and explicitly versioned semantics. */
final class OwnerMetadata extends IndexedMetadata {
  static final String BASE_FORMAT = "independent-owner-frontiers/1";
  static final String TIME_FORMAT = "independent-owner-frontiers/2";
  static final String GAS_FORMAT = "independent-owner-frontiers/3";
  static final String INTEGRITY_FORMAT = "independent-owner-frontiers/4";
  private final Repository repository;
  private final String account;
  private final java.time.Clock clock;

  OwnerMetadata(AtomicStore store, String account) {
    this(new Repository(store), account);
  }

  OwnerMetadata(Repository repository, String account) {
    this(repository, account, java.time.Clock.systemUTC());
  }

  OwnerMetadata(Repository repository, String account, java.time.Clock clock) {
    super(
        repository,
        account,
        new AtomicStore.Key(
            "A#" + Repository.identifier(account) + "#OWNER-COORDINATION", "MANIFEST"),
        List.of(BASE_FORMAT, TIME_FORMAT, GAS_FORMAT, INTEGRITY_FORMAT));
    this.repository = repository;
    this.account = account;
    this.clock = java.util.Objects.requireNonNull(clock);
  }

  @Override
  void beforeCommit(Transaction transaction) {
    if (transaction.changed("gas\0")) {
      OwnerGasEvents.stage(account, transaction, clock.instant());
      OwnerGasWork.stage(repository, account, transaction);
    }
  }
}
