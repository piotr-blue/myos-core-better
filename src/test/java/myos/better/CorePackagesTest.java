package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import blue.BluePackage;
import blue.Language;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Account authoring choices must not mutate native execution or existing canonical content. */
class CorePackagesTest {
  private static final BluePackage PAYMENT =
      BluePackage.open(Path.of("examples/clause-tutorial/packages/payment.bluepkg"));
  private static final BluePackage MANUAL =
      BluePackage.open(Path.of("examples/clause-tutorial/packages/manualpayments.bluepkg"));
  private static final CoreRuntime RUNTIME =
      new CoreRuntime(
          new PublicPackages(
              List.of(PAYMENT, MANUAL), Map.of("Payment", PAYMENT, "ManualPayments", MANUAL)),
          null,
          "blue.canonical-graph/1-preview");
  private static final String PAYMENT_ID = PAYMENT.blueId().value(),
      MANUAL_ID = MANUAL.blueId().value();

  @Nested
  class Installation {
    @Test
    void dependenciesManagedBindingsAndAccountIsolationSurviveRestart() {
      var store = new JsonBoundaryStore();
      var packages = new CorePackages(store, RUNTIME);
      assertEquals(
          "package_in_use",
          assertThrows(
                  ConsoleProblem.class,
                  () -> packages.change("alice", "remove-payment", PAYMENT_ID, false))
              .code);
      assertEquals(
          "managed_package",
          assertThrows(
                  ConsoleProblem.class,
                  () -> packages.change("alice", "remove-native", "Contracts", false))
              .code);
      var removed = packages.change("alice", "remove-manual", MANUAL_ID, false);
      assertEquals(false, removed.get("installed"));
      assertEquals(List.of(MANUAL_ID), available(packages, "alice"));
      assertTrue(available(packages, "bob").isEmpty());
      packages.change("alice", "remove-payment-2", PAYMENT_ID, false);
      var restored = new CorePackages(store, RUNTIME);
      assertEquals(
          "missing_package_dependencies",
          assertThrows(
                  ConsoleProblem.class,
                  () -> restored.change("alice", "install-manual", MANUAL_ID, true))
              .code);
      restored.change("alice", "install-payment", PAYMENT_ID, true);
      restored.change("alice", "install-manual", MANUAL_ID, true);
      assertTrue(available(restored, "alice").isEmpty());
      assertEquals(removed, restored.change("alice", "remove-manual", MANUAL_ID, false));
      assertTrue(
          available(restored, "alice").isEmpty(), "Replaying an old removal cannot undo reinstall");
      assertEquals(
          "idempotency_conflict",
          assertThrows(
                  ConsoleProblem.class,
                  () -> restored.change("alice", "remove-manual", MANUAL_ID, true))
              .code);
    }

    @Test
    void explicitBootstrapSelectionRestoresItsWholeExactClosure() {
      var store = new JsonBoundaryStore();
      var packages = new CorePackages(store, RUNTIME);
      packages.change("alice", "remove-manual", MANUAL_ID, false);
      packages.change("alice", "remove-payment", PAYMENT_ID, false);
      packages.installSelection("alice", "bootstrap-one", List.of(MANUAL_ID));
      assertTrue(available(packages, "alice").isEmpty());
      packages.change("alice", "remove-again", MANUAL_ID, false);
      packages.installSelection("alice", "bootstrap-one", List.of(MANUAL_ID));
      assertEquals(List.of(MANUAL_ID), available(packages, "alice"));
      assertThrows(
          ConsoleProblem.class,
          () -> packages.installSelection("alice", "unknown", List.of("unknown")));
      assertThrows(
          IllegalArgumentException.class,
          () -> packages.installSelection("alice", "duplicate", List.of(MANUAL_ID, MANUAL_ID)));
    }
  }

  @Test
  void concurrentInstallCannotRacePastADependencyRemoval() {
    var store = new RacingStore();
    var packages = new CorePackages(store, RUNTIME);
    packages.change("alice", "remove-manual", MANUAL_ID, false);
    store.beforeCommit = () -> packages.change("alice", "install-manual", MANUAL_ID, true);
    assertEquals(
        "package_in_use",
        assertThrows(
                ConsoleProblem.class,
                () -> packages.change("alice", "remove-payment", PAYMENT_ID, false))
            .code);
    assertTrue(available(new CorePackages(store, RUNTIME), "alice").isEmpty());
  }

  @Nested
  class SourceAuthoring {
    @Test
    void removedAliasesFailButRetainedExactDocumentsStayValid() {
      var store = new JsonBoundaryStore();
      var packages = new CorePackages(store, RUNTIME);
      Language language = Language.newBuilder().provider(RUNTIME.packages()).build();
      var contents = new CoreContents(store, language, RUNTIME.packages(), packages);
      String name = MANUAL.exports().keySet().stream().sorted().findFirst().orElseThrow();
      String yaml = "type: ManualPayments / " + name;
      var before = contents.prepare("alice", yaml, "source", "definition");
      contents.retain("alice", before, true);
      packages.change("alice", "remove", MANUAL_ID, false);
      assertEquals(
          "type_not_installed",
          assertThrows(
                  CoreSourceInputs.Invalid.class,
                  () -> contents.prepare("alice", yaml, "source", "definition"))
              .code);
      assertEquals(before.blueId(), contents.prepare("bob", yaml, "source", "definition").blueId());
      assertEquals(
          before.blueId(),
          contents.prepare("alice", ProviderSigning.wire(before), "exact", "definition").blueId());
      assertEquals(ProviderSigning.wire(before), contents.read("alice", before.blueId()));
      assertEquals(
          before.blueId(),
          contents
              .prepareSelected("alice", yaml, "source", "definition", List.of(MANUAL_ID))
              .blueId());
      assertEquals(List.of(MANUAL_ID), available(packages, "alice"), "Preparation is read-only");
      packages.change("alice", "install", MANUAL_ID, true);
      assertEquals(
          before.blueId(), contents.prepare("alice", yaml, "source", "definition").blueId());
    }

    @Test
    void nestedTypesResolveWithoutRewritingNamesOrOrdinaryStrings() {
      var packages = new CorePackages(new JsonBoundaryStore(), RUNTIME);
      String name = PAYMENT.exports().keySet().stream().sorted().findFirst().orElseThrow();
      String alias = "Payment/" + name;
      var source =
          Map.of(
              "name",
              alias,
              "message",
              alias,
              "nested",
              List.of(Map.of("itemType", alias), Map.of("keyType", alias)));
      var expected =
          Map.of(
              "name",
              alias,
              "message",
              alias,
              "nested",
              List.of(
                  Map.of("itemType", Map.of("blueId", PAYMENT.exports().get(name).value())),
                  Map.of("keyType", Map.of("blueId", PAYMENT.exports().get(name).value()))));
      assertEquals(expected, packages.source("alice", source, List.of()));
      Object tooDeep = 0;
      for (int i = 0; i < 130; i++) {
        tooDeep = List.of(tooDeep);
      }
      Object invalid = tooDeep;
      assertThrows(
          CoreSourceInputs.Invalid.class, () -> packages.source("alice", invalid, List.of()));
    }
  }

  @Test
  void exactSourceReferencesNeedNoAccountPackageRead() {
    var store = new RacingStore();
    var packages = new CorePackages(store, RUNTIME);
    store.denyReads = true;
    var input =
        Map.of(
            "type",
            Map.of("blueId", PAYMENT.exports().values().iterator().next().value()),
            "value",
            1);
    assertEquals(input, packages.source("alice", input, List.of()));
  }

  private static List<String> available(CorePackages packages, String account) {
    return ((List<?>) packages.catalog(account).get("available"))
        .stream().map(p -> (String) ((Map<?, ?>) p).get("id")).sorted().toList();
  }

  private static final class RacingStore implements AtomicStore {
    private final AtomicStore delegate = new JsonBoundaryStore();
    Runnable beforeCommit;
    boolean denyReads;

    @Override
    public Row get(Key key) {
      if (denyReads) {
        throw new AssertionError("Unexpected account package read");
      }
      return delegate.get(key);
    }

    @Override
    public Page query(String partition, String prefix, String after, int limit) {
      return delegate.query(partition, prefix, after, limit);
    }

    @Override
    public Page work(String shard, String after, int limit) {
      return delegate.work(shard, after, limit);
    }

    @Override
    public boolean commit(List<Write> writes, List<Check> checks) {
      Runnable action = beforeCommit;
      if (action != null) {
        beforeCommit = null;
        action.run();
      }
      return delegate.commit(writes, checks);
    }

    @Override
    public byte[] content(String account, String identity) {
      return delegate.content(account, identity);
    }

    @Override
    public void publishContent(String account, Map<String, byte[]> pages) {
      delegate.publishContent(account, pages);
    }
  }
}
