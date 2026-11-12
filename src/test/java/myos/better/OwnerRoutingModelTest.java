package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A simple sequential history model is independent of the scheduler's persistent indexes. */
class OwnerRoutingModelTest {
  private enum Kind {
    TARGET_A,
    TARGET_B,
    EVENT,
    UNKNOWN
  }

  private record Input(int position, Kind kind) {
    String entry() {
      return "entry-" + position;
    }

    OwnerFrontiers.Original original() {
      return new OwnerFrontiers.Original(
          new OwnerFrontiers.Order(position, "shared", entry()), "exact-" + position);
    }
  }

  private static final class Model {
    final List<Input> history = new ArrayList<>();
    int position;
    boolean targetB, observed;

    boolean matches(Input input) {
      return observed
          || input.kind() == Kind.UNKNOWN
          || input.kind() == Kind.TARGET_A
          || targetB && input.kind() == Kind.TARGET_B;
    }

    void refresh() {
      while (position < history.size() && !matches(history.get(position))) position++;
    }

    Input next() {
      return position < history.size() && matches(history.get(position))
          ? history.get(position)
          : null;
    }
  }

  @ParameterizedTest
  @ValueSource(longs = {1, 17, 51966})
  void coldReopenDuplicatesBacklogAndPolicyChangesMatchTheHistoryModel(long seed) {
    try (var store = new JsonBoundaryStore()) {
      var scheduler = new OwnerFrontiers(store, "account");
      scheduler.register(
          "owner",
          "initial",
          List.of(
              new OwnerFrontiers.Source("shared", OwnerFrontiers.Kind.TIMELINE, "shared", 1, 0)));
      configure(scheduler, false, false);
      var model = new Model();
      var random = new Random(seed);
      for (int i = 1; i <= 300; i++) {
        if (i % 31 == 0) scheduler = new OwnerFrontiers(store, "account");
        if (i == 75) {
          model.targetB = true;
          configure(scheduler, true, false);
        }
        if (i == 175) {
          model.observed = true;
          configure(scheduler, true, true);
        }
        Kind kind = i == 75 || i == 175 ? Kind.TARGET_A : Kind.values()[random.nextInt(4)];
        var input = new Input(i, kind);
        model.history.add(input);
        accept(scheduler, input);
        if (model.matches(input)) model.refresh();
        if (i % 7 == 0) scheduler.accept(input.original());
        scheduler.sealLocalBefore(i + 1L);
        if (random.nextInt(3) == 0) completeOne(scheduler, model);
        if (i % 30 == 0) while (model.next() != null) completeOne(scheduler, model);
        assertEquals(
            model.position,
            scheduler.sources("owner").getFirst().position(),
            "source position at seed " + seed + " input " + i);
      }
      while (model.next() != null) completeOne(scheduler, model);
      assertTrue(scheduler.ready(0, 10).isEmpty());
      assertEquals(300, model.position);
    }
  }

  private static void configure(OwnerFrontiers scheduler, boolean targetB, boolean observed) {
    scheduler.change(
        tx -> {
          scheduler.interest(tx, "owner", "shared", observed);
          scheduler.target(tx, "owner", "a");
          if (targetB) scheduler.target(tx, "owner", "b");
          return null;
        });
  }

  private static void accept(OwnerFrontiers scheduler, Input input) {
    scheduler.change(
        tx -> {
          if (input.kind() != Kind.UNKNOWN)
            scheduler.classify(
                tx,
                "shared",
                input.entry(),
                input.kind() != Kind.EVENT,
                input.kind() == Kind.EVENT ? null : input.kind() == Kind.TARGET_A ? "a" : "b");
          scheduler.accept(tx, input.original());
          return null;
        });
  }

  private static void completeOne(OwnerFrontiers scheduler, Model model) {
    var expected = model.next();
    var actual = scheduler.claim("owner", 0, 1000);
    if (expected == null) {
      assertTrue(actual.isEmpty());
      return;
    }
    var claim = actual.orElseThrow();
    assertEquals(expected.entry(), claim.order().entry());
    scheduler.complete(
        claim,
        0,
        "after-" + expected.entry(),
        "receipt-" + expected.entry(),
        false,
        List.of(),
        Set.of());
    model.position = expected.position();
    model.refresh();
  }
}
