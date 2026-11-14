package myos.better;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CloseActionsTest {
  @Test
  void allCancellationsRunAfterAnErrorAndTheSameFailureCannotSuppressItself() {
    var first = new AssertionError("first resource failed");
    var last = new IllegalStateException("last resource failed");
    var closed = new ArrayList<Integer>();
    assertSame(
        first,
        assertThrows(
            AssertionError.class,
            () ->
                CloseActions.run(
                    () -> {
                      closed.add(1);
                      throw first;
                    },
                    () -> {
                      closed.add(2);
                      throw first;
                    },
                    () -> {
                      closed.add(3);
                      throw last;
                    },
                    () -> closed.add(4))));
    assertEquals(List.of(1, 2, 3, 4), closed);
    assertEquals(List.of(last), List.of(first.getSuppressed()));
  }
}
