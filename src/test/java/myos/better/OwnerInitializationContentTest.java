package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import blue.Contracts;
import blue.Language;
import org.junit.jupiter.api.Test;

class OwnerInitializationContentTest {
  @Test
  void aFreshRepositoryRecoversTheCompleteAdmittedInputWithoutTheOriginalProviders() {
    try (var store = new JsonBoundaryStore()) {
      Language language = Language.newBuilder().mount("Contracts", Contracts.publication()).build();
      var base =
          language.readYaml(
              """
              label: inherited
              contracts:
                input: {type: Contracts/External Channel}
              """);
      var document = base.specializeYaml("name: recovered");
      var retained =
          new OwnerInitializationContent(new Repository(store)).retain("alice", document);
      var recovered =
          new OwnerInitializationContent(new Repository(store))
              .restore("alice", retained, document.blueId().value());
      assertEquals(document.blueId(), recovered.blueId());
      try (var original = Contracts.create().open(document);
          var restored = Contracts.create().open(recovered)) {
        var a = original.initialize().requireApplied();
        var b = restored.initialize().requireApplied();
        assertEquals(a.receipt(), b.receipt());
        assertEquals(a.gas(), b.gas());
        assertEquals(original.snapshot(), restored.snapshot());
      }
      assertThrows(
          IllegalStateException.class,
          () ->
              new OwnerInitializationContent(new Repository(store))
                  .restore("alice", retained, base.blueId().value()));
      assertThrows(
          RuntimeException.class,
          () ->
              new OwnerInitializationContent(new Repository(store))
                  .restore("bob", retained, document.blueId().value()));
    }
  }
}
