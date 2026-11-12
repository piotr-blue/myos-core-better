package myos.better;

import static org.junit.jupiter.api.Assertions.*;

import blue.*;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real published access BEX, signed provider evidence and conditional document execution. */
class PrivateTimelineIngressTest {
  @Nested
  class HistoricalAuthority {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void laterRevocationCannotReplaceTheEstablishedStateAtAnEarlierInput(boolean directory)
        throws Exception {
      try (PrivateTimelineFixture f = new PrivateTimelineFixture()) {
        f.append(200, 42);
        f.pullPrivate(250);
        f.permissions(300, List.of());
        f.pullControl(400);
        f.start(directory);
        f.create("order");
        assertEquals("APPLIED", f.step("order"));
        assertEquals(BigInteger.valueOf(42), f.count("order"));
        assertEquals(2, f.repository.load("reader", "order").version());
        assertEquals(
            0,
            f.publisher
                .host
                .access()
                .get(f.grant.initial().blueId())
                .current()
                .at("/allowedKeys")
                .size());
      }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void laterRestorationDoesNotRetroactivelyAuthorizeAnInputDuringRevocation(boolean directory)
        throws Exception {
      try (PrivateTimelineFixture f = new PrivateTimelineFixture()) {
        f.permissions(200, List.of());
        f.append(300, 9);
        f.permissions(400, List.of(f.key));
        f.pullPrivate(500);
        f.pullControl(600);
        f.start(directory);
        f.create("order");
        assertEquals("PENDING_ACCESS", f.step("order"));
        assertEquals(BigInteger.ZERO, f.count("order"));
        assertEquals(1, f.repository.load("reader", "order").version());
        assertEquals("PENDING_ACCESS", f.step("order"));
        assertEquals(
            0,
            f.repository
                .store
                .query(Repository.partition("reader", "order"), "TIMELINE_INPUT#", null, 10)
                .rows()
                .size());
      }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void everyRequiredReaderMustBeGrantedAndSeparateAccessDocumentsContributeAUnion(
        boolean directory) throws Exception {
      try (PrivateTimelineFixture f = new PrivateTimelineFixture()) {
        String second = "22".repeat(32);
        f.publisher.clock.set(150);
        HostedAccess.Access extra =
            f.publisher
                .host
                .access()
                .create(
                    "alice",
                    "second",
                    new HostedAccess.Permissions(List.of(f.source.id()), List.of(second)));
        f.grants.add(extra.initial());
        f.requiredKeys.add(second);
        f.append(200, 7);
        f.pullPrivate(300);
        f.pullControl(400);
        f.start(directory);
        f.create("order");
        assertEquals("APPLIED", f.step("order"));
        assertEquals(BigInteger.valueOf(7), f.count("order"));
      }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingControlCoverageWaitsAndAColdHostReplaysTheSameHistory(boolean directory)
        throws Exception {
      try (PrivateTimelineFixture f = new PrivateTimelineFixture()) {
        f.append(200, 5);
        f.pullPrivate(300);
        f.start(directory);
        f.create("order");
        assertEquals("WAITING", f.step("order"));
        f.pullControl(400);
        assertEquals("APPLIED", f.step("order"));
        f.core.close();
        f.start(directory);
        f.append(500, 6);
        f.pullPrivate(600);
        f.pullControl(700);
        assertEquals("APPLIED", f.step("order"));
        assertEquals(BigInteger.valueOf(11), f.count("order"));
        assertEquals("IDLE", f.step("order"));
      }
    }
  }
}
