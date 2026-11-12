package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Materialized projections are optional caches; exact checkpoint state owns cross-document reads.
 */
class CheckpointProjectionTest {
  @ParameterizedTest
  @ValueSource(longs = {1000, 5000, 10_000, 10_000_000})
  void aCompactSharedGraphCanCommitWithoutExpandingItsOptionalCache(long workLimit) {
    blue.LanguageDocument node = blue.Language.create().readYaml("leaf: 7");
    for (int i = 0; i < 18; i++) {
      blue.Language language = blue.Language.newBuilder().content(node).build();
      String reference = "{\"blueId\":\"" + node.blueId().value() + "\"}";
      node = language.readJson("{\"left\":" + reference + ",\"right\":" + reference + "}");
    }
    CanonicalRuntime runtime =
        CanonicalRuntime.newBuilder()
            .language(
                blue.Language.newBuilder()
                    .content(node)
                    .limits(
                        new blue.Language.Limits(
                            workLimit, 256, 1_000_000, 100_000, 16L << 20, 65_536, 65_536))
                    .build())
            .build();
    JsonBoundaryStore store = new JsonBoundaryStore();
    try (Core core = new Core(store, runtime)) {
      assertEquals(
          "APPLIED", core.create("shared", "root", "create", node.canonicalJson()).status());
      AtomicStore.Row stored = store.get(Repository.key("shared", "root", "STATE"));
      assertFalse(stored.value().containsKey("data"));
      Map<String, Object> before = core.head("shared", "root");
      ReadBudget.Exceeded refused =
          assertThrows(
              ReadBudget.Exceeded.class,
              () -> core.inspect("shared", "root", false, new ReadBudget.Limits(1, 100, 1000, 64)));
      assertEquals("VIEW_LIMIT_EXCEEDED", refused.code());
      assertEquals(before, core.head("shared", "root"));
    }
  }

  @Test
  void anOmittedProjectionIsBoundedDuringConstructionAfterRestart() {
    JsonBoundaryStore store = new JsonBoundaryStore();
    String padding = "large payload ".repeat(6000);
    try (Core core = new Core(store, CanonicalRuntime.create())) {
      assertEquals(
          "APPLIED",
          core.create(
                  "limited",
                  "root",
                  "create",
                  Map.of("name", "Large named document", "padding", padding, "count", 7))
              .status());
      assertFalse(
          store.get(Repository.key("limited", "root", "STATE")).value().containsKey("data"));
    }
    try (Core core = new Core(store, CanonicalRuntime.create())) {
      assertEquals(
          List.of(Map.of("document", "root", "name", "Large named document")),
          new RootDocuments(store).list("limited", null).get("items"));
      Map<String, Object> before = core.head("limited", "root");
      ReadBudget.Exceeded error =
          assertThrows(
              ReadBudget.Exceeded.class,
              () -> core.inspect("limited", "root", false, new ReadBudget.Limits(1, 100, 64, 64)));
      assertEquals("VIEW_LIMIT_EXCEEDED", error.code());
      assertEquals("PROJECTION_LIMIT_EXCEEDED", ((blue.BlueException) error.getCause()).code());
      assertEquals(before, core.head("limited", "root"));
      assertEquals(
          padding, ((Map<?, ?>) core.inspect("limited", "root", false).get("data")).get("padding"));
    }
  }

  @ParameterizedTest
  @EnumSource(Core.ProjectionPolicy.class)
  void aLargeChildRemainsReadableAcrossProjectionPoliciesAndRestart(Core.ProjectionPolicy policy) {
    JsonBoundaryStore store = new JsonBoundaryStore();
    String padding = "large inherited definitions ".repeat(3000);
    Map<String, Object> change =
        Map.of("op", "replace", "path", "/seen", "val", Map.of("$document", "/children/a/amount"));
    Map<String, Object> operation =
        Map.of(
            "type",
            "Contracts/Sequential Workflow Operation",
            "channel",
            "input",
            "steps",
            List.of(Map.of("type", "Contracts/Update Document", "changeset", List.of(change))));
    Map<String, Object> contracts =
        Map.of(
            "input",
            Map.of("type", "Contracts/External Channel"),
            "embedded",
            Map.of("type", "Contracts/Process Embedded", "collectionPaths", List.of("/children")),
            "readChild",
            operation);
    Map<String, Object> source =
        Map.of(
            "children",
            Map.of("a", Map.of("amount", 7, "padding", padding)),
            "seen",
            0,
            "contracts",
            contracts);
    try (Core core = new Core(store, CanonicalRuntime.create(), policy)) {
      Core.Result created = core.create("projection", "root", "create", source);
      assertEquals("APPLIED", created.status());
      assertTrue(
          created.children().isEmpty(),
          "Whole-graph processing does not publish detached children");
    }
    try (Core core = new Core(store, CanonicalRuntime.create())) {
      assertEquals(
          "APPLIED",
          core.call("projection", "root", "read", "input", "readChild", Map.of(), 1).status());
      Map<?, ?> data = (Map<?, ?>) core.inspect("projection", "root", true).get("data");
      assertEquals(BigInteger.valueOf(7), data.get("seen"));
      assertEquals(
          padding, ((Map<?, ?>) ((Map<?, ?>) data.get("children")).get("a")).get("padding"));
    }
  }
}
