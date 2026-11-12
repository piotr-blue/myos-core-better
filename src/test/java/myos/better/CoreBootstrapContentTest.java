package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import blue.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;

class CoreBootstrapContentTest {
  @Test
  void preparedBootstrapRetainsPrivateReferencedContentAcrossPreparationAndExecution()
      throws Exception {
    var language =
        Language.newBuilder()
            .mount("Contracts", Contracts.publication())
            .mount("Coordination", TimelineFixture.COORDINATION)
            .build();
    try (var f = new ConsoleInputFixture(language, PublicPackages.EMPTY, 60_000, true)) {
      var runtime = new CoreRuntime(f.core, f.timelines);
      var contents = new CoreContents(f.store, f.core.language(), runtime.packages());
      var stored =
          contents.create("owner", Map.of("content", "name: Private bootstrap value\nnumber: 123"));
      String id = (String) ((Map<?, ?>) stored.get("contentRef")).get("blueId");
      var documents = new ConsoleDocuments(f.core, f.store, Path.of("examples"), f.timelines);
      var bootstrap = new CoreBootstrap(documents, contents, runtime);
      var input =
          Map.<String, Object>of(
              "document",
              "name: Private reference bootstrap\nprivateValue: {blueId: '"
                  + id
                  + "'}\ncontracts:\n  owner: {type: Coordination/Timeline Channel}\n",
              "participantMappings",
              List.of(Map.of("channelName", "owner", "accountId", "owner")));
      var prepared = bootstrap.prepare("owner", input);
      assertTrue(((List<?>) f.timelines.list("owner", Map.of()).get("items")).isEmpty());
      // Recovery must use the frozen content, even when the mutable input catalogue is unavailable.
      var catalogue = f.store.get(new AtomicStore.Key("CORE_CONTENTS#owner", "HEAD"));
      assertTrue(f.store.commit(List.of(AtomicStore.Write.delete(catalogue)), List.of()));
      var reopened =
          new CoreBootstrap(
              documents, new CoreContents(f.store, f.core.language(), runtime.packages()), runtime);
      var created = reopened.execute("owner", "private-reference", prepared);
      String document = (String) created.get("sessionId");
      assertEquals(
          123,
          ((Number) f.service.graphs().readValue("owner", document, "/privateValue/number"))
              .intValue());
      assertEquals(created, reopened.execute("owner", "private-reference", prepared));
      assertEquals(
          1,
          ((List<?>)
                  reopened
                      .recipients(
                          "owner",
                          "admission",
                          Map.of("kind", "DOCUMENT_BOOTSTRAP", "details", created))
                      .get("recipients"))
              .size());
      assertThrows(ConsoleProblem.class, () -> contents.read("other", new BlueId(id)));
    }
  }
}
