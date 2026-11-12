package myos.better;

import blue.Language;
import blue.LanguageContent;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.NamedParameterSpec;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Public RFC test key, JSON persistence boundary and controlled publication/read faults. */
final class HostedTimelineFixture {

  final Faults store = new Faults();
  final TimelineFixture protocol = new TimelineFixture();
  final ProviderSigning signing =
      new ProviderSigning(TimelineFixture.COORDINATION, protocol.provider, key());
  final AtomicLong clock = new AtomicLong(100);
  final HostedTimelines host = restart();

  HostedTimelines restart() {
    return new HostedTimelines(store, "test", signing, clock::get);
  }

  HostedTimelines.Created create() {
    return host.create("alice", "create", "Personal");
  }

  LanguageContent message(int amount) {
    return Language.create().prepare(Language.Input.yaml("amount: " + amount));
  }

  TimelineArchive.Acquired verify(
      HostedTimelines.Created created,
      HostedTimelines.Checkpoint checkpoint,
      List<HostedTimelines.Published> entries) {
    Language language =
        Language.newBuilder()
            .mount("Coordination", TimelineFixture.COORDINATION)
            .content(protocol.provider)
            .content(created.timeline())
            .build();
    LanguageContent range =
        language.prepare(
            Language.Input.json(
                JsonText.of(
                    Map.of(
                        "type",
                        "Coordination/Timeline Range Evidence",
                        "checkpoint",
                        ProviderSigning.wire(checkpoint.attestation()),
                        "entries",
                        entries.stream().map(e -> ProviderSigning.wire(e.entry())).toList()))));
    return new TimelineArchive(
            new Repository(new JsonBoundaryStore()),
            "reader",
            TimelineFixture.COORDINATION,
            protocol.provider,
            created.timeline())
        .acquire(range.toJson(), Map.of());
  }

  static PrivateKey key() {
    try {
      return KeyFactory.getInstance("Ed25519")
          .generatePrivate(
              new EdECPrivateKeySpec(
                  NamedParameterSpec.ED25519,
                  HexFormat.of()
                      .parseHex(
                          "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")));
    } catch (GeneralSecurityException failure) {
      throw new AssertionError(failure);
    }
  }

  static final class Faults implements AtomicStore {
    final JsonBoundaryStore delegate = new JsonBoundaryStore();
    Runnable beforeCommit;
    boolean loseResponse;
    final Set<String> published = ConcurrentHashMap.newKeySet(),
        unavailable = ConcurrentHashMap.newKeySet();
    long getCalls, contentCalls;
    boolean legacyMetadata;

    public Row get(Key key) {
      getCalls++;
      Row row = delegate.get(key);
      if (legacyMetadata && row != null && key.sort().startsWith("ENTRY#")) {
        Map<String, Object> old = new LinkedHashMap<>(row.value());
        old.remove("timestamp");
        return new Row(key, row.version(), old);
      }
      return row;
    }

    public Page query(String partition, String prefix, String after, int limit) {
      return delegate.query(partition, prefix, after, limit);
    }

    public Page work(String shard, String after, int limit) {
      return delegate.work(shard, after, limit);
    }

    public byte[] content(String account, String id) {
      contentCalls++;
      if (unavailable.contains(id))
        throw new IllegalStateException("Earlier entry body unavailable");
      return delegate.content(account, id);
    }

    public void publishContent(String account, Map<String, byte[]> pages) {
      published.addAll(pages.keySet());
      delegate.publishContent(account, pages);
    }

    public boolean commit(List<Write> writes, List<Check> checks) {
      Runnable race = beforeCommit;
      beforeCommit = null;
      if (race != null) race.run();
      boolean result = delegate.commit(writes, checks);
      if (result && loseResponse) {
        loseResponse = false;
        throw new IllegalStateException("Lost publication response");
      }
      return result;
    }
  }
}
