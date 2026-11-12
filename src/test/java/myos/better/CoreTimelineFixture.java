package myos.better;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.util.List;

/** Isolated publisher and receiving account with managed reader custody. */
final class CoreTimelineFixture {
  final HostedTimelineFixture publisher = new HostedTimelineFixture();
  final HostedTimelines.Created source = publisher.create();
  final NativeTimelineApi api = new NativeTimelineApi(publisher.host, publisher.signing);
  final TimelineTransport transport =
      api.transport(
          (u, r, b, t) -> {
            throw new IOException("Unexpected remote request");
          });
  final JsonBoundaryStore consumer = new JsonBoundaryStore();
  final SecretEnvelope envelope = new SecretEnvelope(new byte[32], "myos.better.reader-key/1");
  final CoreReaderKeys keys = new CoreReaderKeys(consumer, envelope);
  final String key = (String) keys.create("reader", "Imported reader", "create").get("id");
  final String endpoint = publisher.protocol.provider.at("/endpoint").text();

  void grant() {
    publisher
        .host
        .access()
        .grant(
            "alice",
            "grant",
            new HostedAccess.Permissions(
                List.of(source.id()), List.of((String) keys.read("reader", key).get("publicKey"))));
  }

  CoreTimelineDiscovery discovery(TimelineTransport selected) {
    return new CoreTimelineDiscovery(
        TimelineFixture.COORDINATION, PublicPackages.EMPTY, keys, selected);
  }

  TimelineService service() {
    return new TimelineService(
        new Repository(consumer),
        TimelineFixture.COORDINATION,
        "imports",
        List.of(),
        new TimelineService.Automatic(100, 1),
        true,
        true,
        ref -> fail("Managed reader consulted AWS custody"));
  }
}
