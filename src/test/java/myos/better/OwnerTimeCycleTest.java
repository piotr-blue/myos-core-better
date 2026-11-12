package myos.better;

import static myos.better.OwnerTimeIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import blue.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Same-time Time inputs in cyclic ownership units retain native whole-graph semantics. */
class OwnerTimeCycleTest {
  @BeforeEach
  void enableOwners() {
    System.setProperty("myos.owners.fast", "true");
  }

  @AfterEach
  void disableOwners() {
    System.clearProperty("myos.owners.fast");
  }

  @ParameterizedTest
  @ValueSource(ints = {4, 8})
  void aCycleWithChordsHasOneOrderedFiringPerMemberAcrossRestarts(int count) {
    var definitions = new ArrayList<Language.Input>();
    for (int n = 0; n < count; n++) {
      var value =
          LANGUAGE.readYaml(
              """
              number: %d
              count: 0
              contracts:
                embedded: {type: Contracts/Process Embedded, paths: [/next, /chord]}
                clock: {type: Time/Timer Channel}
                init: {type: Contracts/Lifecycle Event Channel}
                start:
                  type: Contracts/Sequential Workflow
                  channel: init
                  steps:
                    - type: Time/Schedule Timer
                      request: {channel: clock, timestamp: 10}
                tick:
                  type: Contracts/Sequential Workflow
                  channel: clock
                  steps:
                    - type: Contracts/Update Document
                      changeset: [{op: replace, path: /count, val: {$add: [{$document: /count}, 1]}}]
                    - type: Contracts/Trigger Event
                      event: {kind: Tick, number: %d}
              """
                  .formatted(n, n));
      var exact = Json.object(Json.bytes(ProviderSigning.wire(value)));
      exact.put("next", Map.of("blueId", "this#" + (n + 1) % count));
      exact.put("chord", Map.of("blueId", "this#" + (n + 2) % count));
      definitions.add(Language.Input.exactJson(JsonText.of(exact)));
    }
    var document = LANGUAGE.finalizeSet(definitions, Language.Goal.VALUE).members().getFirst();
    var selected = new AtomicReference<OwnerTimers.Registration>();
    var aliases = new HashMap<String, String>();
    try (var oracleFixture = new Fixture(new JsonBoundaryStore())) {
      var oracleConfiguration =
          Contracts.newBuilder()
              .language(LANGUAGE)
              .timelineChannels(oracleFixture.service.graphIngress().channels())
              .timeBindings(
                  TimeBindings.verifiedBy(
                      TIME,
                      request -> {
                        var expected = Objects.requireNonNull(selected.get());
                        assertEquals(expected.timestamp(), request.timestamp());
                        assertEquals(expected.payload().message(), request.eventId().value());
                        var receiver =
                            request.receivers().stream()
                                .filter(
                                    r ->
                                        aliases
                                                .getOrDefault(r.member(), r.member())
                                                .equals(expected.member())
                                            && r.channel().equals(expected.channel())
                                            && r.generation() == expected.generation())
                                .findFirst()
                                .orElseThrow();
                        return new TimeBindings.Firing(receiver, new BlueId(expected.id()));
                      }))
              .build();
      var store = new JsonBoundaryStore();
      try (var oracle = Contracts.Execution.using(oracleConfiguration, null)) {
        var initialized = oracle.initialize(oracle.prepare(ROOT, document)).requireApplied();
        var expected = initialized.after();
        List<OwnerTimers.Registration> registrations;
        try (var f = new Fixture(store)) {
          f.engine.create(ACCOUNT, ROOT, "create", document);
          var records = f.records();
          expected.members().forEach(m -> aliases.put(m.key(), records.owner(m.key())));
          registrations =
              expected.members().stream()
                  .flatMap(m -> OwnerTimers.pending(records.read, records.owner(m.key())).stream())
                  .sorted(Comparator.comparing(r -> r.order().key()))
                  .toList();
          assertEquals(count, registrations.size());
          assertEquals(
              1, registrations.stream().map(OwnerTimers.Registration::owner).distinct().count());
          f.activate(10);
        }
        for (int i = 0; i < registrations.size(); i++) {
          var registration = registrations.get(i);
          selected.set(registration);
          try (var f = new Fixture(store)) {
            var records = f.records();
            var message =
                LANGUAGE.toBuilder()
                    .provider(
                        records.emissions.provider(ACCOUNT, registration.payload().catalogue()))
                    .build()
                    .read(new BlueId(registration.payload().message()));
            var result =
                oracle
                    .process(
                        expected,
                        message,
                        10,
                        AdmissionEvidence.of(
                            TIME.export("Timer Channel"), Map.of("expected", registration.id())))
                    .requireApplied();
            expected = result.after();
            assertEquals(TimelineIngress.Status.APPLIED, f.engine.step(ACCOUNT, ROOT).status());
            var current = f.records();
            var head = current.head(ROOT);
            assertEquals(expected.blueId(), head.value().get("blueId"));
            var actual = RuntimeConfiguration.object(head.value().get("result"), "result");
            assertEquals(result.transition().receipt(), actual.get("receipt"));
            assertEquals(result.transition().gas(), Repository.number(actual.get("gas")));
            for (var member : expected.members()) {
              assertEquals(
                  member.blueId().value(),
                  current.head(OwnerGraphs.address(member.key())).value().get("blueId"));
            }
            assertEquals(
                OwnerTimers.Status.FIRED,
                OwnerTimers.registration(current.read, registration.id()).status());
          }
        }
        try (var f = new Fixture(store)) {
          f.drain(0);
          for (var member : expected.members()) {
            assertEquals(1, ((Number) expected.rooted(member.key()).read("/count")).intValue());
            assertTrue(
                OwnerTimers.pending(f.records().read, f.records().owner(member.key())).isEmpty());
          }
        }
      }
    }
  }
}
