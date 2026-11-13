package myos.better;

import static myos.better.OwnerTimeIntegrationTest.ROOT;
import static org.junit.jupiter.api.Assertions.assertEquals;

import blue.BluePackage;
import blue.PackageLoader;
import blue.TimeBindings;
import java.util.stream.Stream;
import myos.better.OwnerTimeIntegrationTest.Fixture;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class OwnerTimeContextEditionTest {
  private static final BluePackage CONTEXT_TIME =
      PackageLoader.create()
          .resource(
              OwnerTimeContextEditionTest.class.getClassLoader(),
              "time/context.bluepkg",
              TimeBindings.CONTEXT_PUBLICATION);

  static Stream<Arguments> contexts() {
    return Stream.of(
        Arguments.of("7", "7"),
        Arguments.of("\"hello\"", "\"hello\""),
        Arguments.of("true", "true"),
        Arguments.of("[1,2]", "[1,2]"),
        Arguments.of("{\"value\":\"hello\"}", "\"hello\""),
        Arguments.of("{\"note\":7}", "{\"note\":7}"));
  }

  @ParameterizedTest
  @MethodSource("contexts")
  void contextSurvivesDurableRegistrationRestartAndExactFiring(String context, String expected) {
    var store = new JsonBoundaryStore();
    String registration;
    try (var fixture = new Fixture(store, () -> 1000, CONTEXT_TIME)) {
      fixture.create(
          """
          seen: 0
          contracts:
            timer: {type: Time/Timer Channel}
            init: {type: Contracts/Lifecycle Event Channel}
            start:
              type: Contracts/Sequential Workflow
              channel: init
              steps:
                - type: Time/Schedule Timer
                  request: {channel: timer, timestamp: 100, context: %s}
            tick:
              type: Contracts/Sequential Workflow
              channel: timer
              steps:
                - type: Contracts/Update Document
                  changeset:
                    - {op: replace, path: /seen, val: {$event: /request/context}}
          """
              .formatted(context));
      registration = OwnerTimers.pending(fixture.records().read, ROOT).getFirst().id();
      fixture.activate(99);
      fixture.drain(0);
    }
    try (var fixture = new Fixture(store, () -> 1000, CONTEXT_TIME)) {
      fixture.activate(100);
      fixture.drain(1);
      var records = fixture.records();
      try (var processor = records.processor(fixture.contracts)) {
        var state = records.restore(processor, (String) records.head(ROOT).value().get("page"));
        assertEquals(
            Json.object("{\"value\":" + expected + "}"),
            Json.object("{\"value\":" + JsonText.of(state.read("/seen")) + "}"));
      }
      assertEquals(
          OwnerTimers.Status.FIRED, OwnerTimers.registration(records.read, registration).status());
      fixture.activate(1000);
      fixture.drain(0);
      assertEquals(2, fixture.records().head(ROOT).version());
    }
  }
}
