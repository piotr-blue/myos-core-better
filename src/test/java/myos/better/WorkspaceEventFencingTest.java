package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkspaceEventFencingTest {
  @Test
  void openingCreditAndDirectoryEntryCommitTogetherAndReplayOnce() {
    try (var store = new PublicDirectoryStore(new MemoryStore(), false)) {
      var workspaces = new CoreWorkspaces(store, Clock.systemUTC());
      var request =
          Map.<String, Object>of("kind", "BUSINESS", "profile", Map.of("displayName", "Studio"));
      var runtime = Map.<String, Object>of("hostExecution", OwnerRecords.EDITION);
      var first = workspaces.create("alice", "create", request, runtime);
      assertEquals(first, workspaces.create("alice", "create", request, runtime));
      var account = (String) first.get("accountId");
      assertEquals(List.of("alice", account), workspaces.accounts("alice"));
      assertEquals(1, store.query("CORE_EVENT_OUTBOX#" + account, "", null, 10).rows().size());
    }
  }
}
