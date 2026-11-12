package myos.better;

/** Bounded immutable page caches shared by attempts; contains no mutable document sessions. */
final class OwnerStorage {
  final Repository repository;
  final CanonicalSnapshots snapshots;
  final GraphLineage lineage;
  final CanonicalEmissions emissions;
  final GraphOperationCatalogue operations;
  final CanonicalEvidence evidence;

  OwnerStorage(AtomicStore store) {
    repository = new Repository(store);
    snapshots = new CanonicalSnapshots(repository);
    lineage = new GraphLineage(repository);
    emissions = new CanonicalEmissions(repository);
    operations = new GraphOperationCatalogue(repository);
    evidence = new CanonicalEvidence(repository);
  }

  OwnerRecords records(String account) {
    return new OwnerRecords(this, account);
  }
}
