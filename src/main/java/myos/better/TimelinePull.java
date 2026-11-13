package myos.better;

import blue.BlueId;
import blue.Language;
import blue.LanguageContent;
import blue.LanguageException;
import blue.LanguageRecord;
import blue.TimelineReader;
import blue.TimelineRequests;
import blue.TimelineVerifier;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Bounded reads with source-local discovery reuse and optional source-bound reader signing. Call
 * serially for one source; each attempt verifies fresh pagination before publishing an ordinary
 * range or successor proof.
 */
final class TimelinePull {
  private static final long DISCOVERY_NANOS = Duration.ofMinutes(5).toNanos();
  private static final int CHUNK_PAGES = 8;
  private static final int CONTENT_FETCHES = 256;

  record Limits(int pageSize, int pages, int entries, int records, int bytes, Duration duration) {
    static final Limits STANDARD =
        new Limits(256, 1024, 100_000, 100_000, 16 * 1024 * 1024, Duration.ofSeconds(30));

    Limits {
      if (pageSize < 1
          || pageSize > 10_000
          || pages < 1
          || pages > 4096
          || entries < 1
          || entries > 100_000
          || records < 1
          || records > 100_000
          || bytes < 1
          || bytes > 16 * 1024 * 1024
          || duration.isNegative()
          || duration.isZero()
          || duration.compareTo(Duration.ofMinutes(1)) > 0) {
        throw new IllegalArgumentException("Invalid Timeline acquisition limits");
      }
    }
  }

  private final TimelineArchive archive;
  private final TimelineTransport transport;
  private final Limits limits;
  private final LongSupplier clock;
  private final TimelineReaderSigning signing;
  private final boolean demandDriven;
  private Discovery discovery;
  private boolean caughtUp;

  private record Discovery(TimelineReader reader, long verifiedAt) {}

  TimelinePull(TimelineArchive archive, TimelineTransport transport) {
    this(archive, transport, Limits.STANDARD, System::nanoTime);
  }

  TimelinePull(
      TimelineArchive archive, TimelineTransport transport, Limits limits, LongSupplier clock) {
    this(archive, transport, limits, clock, null);
  }

  TimelinePull(
      TimelineArchive archive, TimelineTransport transport, TimelineReaderSigning signing) {
    this(archive, transport, Limits.STANDARD, System::nanoTime, signing);
  }

  TimelinePull(
      TimelineArchive archive,
      TimelineTransport transport,
      Limits limits,
      LongSupplier clock,
      TimelineReaderSigning signing) {
    this(archive, transport, limits, clock, signing, false);
  }

  static TimelinePull automatic(
      TimelineArchive archive, TimelineTransport transport, TimelineReaderSigning signing) {
    return new TimelinePull(archive, transport, Limits.STANDARD, System::nanoTime, signing, true);
  }

  private TimelinePull(
      TimelineArchive archive,
      TimelineTransport transport,
      Limits limits,
      LongSupplier clock,
      TimelineReaderSigning signing,
      boolean demandDriven) {
    this.demandDriven = demandDriven;
    this.archive = Objects.requireNonNull(archive);
    this.transport = Objects.requireNonNull(transport);
    this.limits = Objects.requireNonNull(limits);
    this.clock = Objects.requireNonNull(clock);
    this.signing = signing;
    if (signing != null) {
      signing.requireSource(archive.source());
    }
  }

  TimelineArchive.Acquired acquire() throws IOException, InterruptedException {
    return acquire(demandDriven);
  }

  /** Ask a remote provider for its current frontier, even when existing coverage meets demand. */
  TimelineArchive.Acquired refresh() throws IOException, InterruptedException {
    return acquire(false);
  }

  private TimelineArchive.Acquired acquire(boolean requestCoverage)
      throws IOException, InterruptedException {
    if (!archive.source().publicRead() && signing == null) {
      throw new IllegalStateException("Private Timeline acquisition requires reader authorization");
    }
    caughtUp = false;
    try {
      try {
        return new Attempt(requestCoverage).execute();
      } catch (HostedTimelines.BoundUnavailable unavailable) {
        if (!requestCoverage) {
          throw unavailable;
        }
        return new Attempt(false).execute();
      } catch (TimelineReader.Failure unavailable) {
        if (!requestCoverage || !"BOUND_UNAVAILABLE".equals(unavailable.code())) {
          throw unavailable;
        }
        // One future demand must not prevent acquisition of coverage already available to
        // earlier inputs. The lower verified prefix never satisfies the outstanding demand.
        return new Attempt(false).execute();
      }
    } catch (IOException | InterruptedException | RuntimeException failure) {
      // A changed page limit, outage or invalid response requires fresh discovery next time.
      discovery = null;
      throw failure;
    }
  }

  /** Whether the last successful attempt reached its signed checkpoint instead of a successor. */
  boolean caughtUp() {
    return caughtUp;
  }

  /** All temporary records, counters and timing belong to this invocation, never another source. */
  private final class Attempt {
    final long started = clock.getAsLong();
    final Map<BlueId, LanguageRecord> records = new LinkedHashMap<>();
    final Map<BlueId, Boolean> established = new HashMap<>();
    final Set<BlueId> fetched = new HashSet<>();
    final LocalSourceContent local =
        transport instanceof LocalTimelineTransport selected
            ? selected.localContent(archive)
            : LocalSourceContent.empty(archive);
    final TimelineArchive.ReadContext context = archive.readContext(records);
    final Language language = language();
    TimelineReader contentReader;
    Exception contentFailure;
    int contentDepth;
    long wireBytes, recordBytes;
    BlueId deferredHead;
    final boolean requestedCoverage;

    Attempt(boolean requestedCoverage) {
      this.requestedCoverage = requestedCoverage;
      if (local.validation() != null) {
        local.validation().shareValidationWith(context.verifier());
      }
    }

    private Language language() {
      Language.Builder builder = context.language().toBuilder();
      builder.provider(local.provider());
      return builder.provider(this::findContent).build();
    }

    TimelineArchive.Acquired execute() throws IOException, InterruptedException {
      try {
        return run();
      } catch (RuntimeException failure) {
        checkContentFailure();
        throw failure;
      }
    }

    /** Resume the current validation at its missing record instead of restarting the whole page. */
    private Optional<LanguageRecord> findContent(BlueId target) {
      if (contentReader == null || contentFailure != null) {
        return Optional.empty();
      }
      try {
        if (++contentDepth > 16) {
          throw new IllegalStateException("Timeline acquisition content nesting limit exceeded");
        }
        fetchMissing(contentReader, target);
        return Optional.ofNullable(records.get(target));
      } catch (IOException | InterruptedException | RuntimeException failure) {
        // Language reports provider failures as unavailable evidence. Restore this original
        // transport/signature/resource failure at the acquisition boundary, never retry it.
        if (contentFailure == null) {
          contentFailure = failure;
        }
        return Optional.empty();
      } finally {
        contentDepth--;
      }
    }

    private void checkContentFailure() throws IOException, InterruptedException {
      if (contentFailure instanceof IOException failure) {
        throw failure;
      }
      if (contentFailure instanceof InterruptedException failure) {
        throw failure;
      }
      if (contentFailure instanceof RuntimeException failure) {
        throw failure;
      }
    }

    @SuppressWarnings("try")
    private TimelineReader reader() throws IOException, InterruptedException {
      if (discovery != null) {
        long age = clock.getAsLong() - discovery.verifiedAt();
        if (age >= 0 && age < DISCOVERY_NANOS) {
          return discovery.reader();
        }
      }
      discovery = null;
      try (var timing = archive.metrics.time(TimelineMetrics.Phase.DISCOVERY)) {
        TimelineTransport.Reply reply = exchange("/provider", null);
        if (reply.status() != 200) {
          throw new IOException("Provider discovery HTTP " + reply.status());
        }
        LanguageContent descriptor = parse(reply);
        TimelineReader reader = TimelineReader.adopt(context.verifier(), descriptor);
        remember(descriptor);
        archive.discover(descriptor);
        discovery = new Discovery(reader, clock.getAsLong());
        return reader;
      }
    }

    @SuppressWarnings("try")
    TimelineArchive.Acquired run() throws IOException, InterruptedException {
      TimelineReader reader = reader();
      contentReader = reader;
      BigInteger size =
          reader
              .maximumPageSize()
              .min(BigInteger.valueOf(Math.min(limits.pageSize(), limits.entries())));
      Map<String, Object> fields = new LinkedHashMap<>();
      fields.put("limit", size);
      if (requestedCoverage) {
        long minimum = Math.max(context.prefix().completeBefore(), archive.demandedCoverage());
        if (minimum > 0) {
          fields.put("minimumCompleteBefore", BigInteger.valueOf(minimum));
        }
      }
      context.prefix().headId().ifPresent(id -> fields.put("afterEntry", reference(id)));
      LanguageContent request = request(fields);
      TimelineReader.Read read = reader.begin(context.prefix(), request);
      List<Object> entries = new ArrayList<>();
      for (int pageNumber = 0; pageNumber < limits.pages(); pageNumber++) {
        remaining();
        remember(reader, request);
        TimelineTransport.Reply reply;
        try (var timing = archive.metrics.time(TimelineMetrics.Phase.READ_HTTP)) {
          reply = exchange("/timelines/read", request.toJson());
        }
        LanguageContent response;
        TimelineReader.Page page;
        try (var timing = archive.metrics.time(TimelineMetrics.Phase.VERIFY_PAGE)) {
          response = parse(reply);
          TimelineReader.Read outstanding = read;
          page = resolving(reader, () -> outstanding.accept(response));
        }
        // Signed failures are reported by accept; an HTTP error cannot publish a success body.
        if (reply.status() != 200) {
          throw new IOException("Timeline read HTTP " + reply.status());
        }
        if (page.entries().size() > limits.entries() - entries.size()) {
          throw new IllegalStateException("Timeline acquisition entry limit exceeded");
        }
        // The checkpoint may name an unread entry. Only the chosen range or successor proof is
        // published; ordinary archive verification must resolve its required content.
        deferredHead = page.completedPrefix().isEmpty() ? page.claimedHeadId().orElse(null) : null;
        remember(reader, response);
        for (TimelineVerifier.Entry entry : page.entries()) {
          entries.add(reference(entry.blueId()));
        }
        if (page.completedPrefix().isPresent()) {
          TimelineArchive.Acquired result =
              publish(
                  reader, "Timeline Range Evidence", "checkpoint", page.checkpointId(), entries);
          caughtUp = true;
          return result;
        }
        if (page.nextCursor().isEmpty()) {
          throw new IllegalStateException("Timeline read did not establish a complete prefix");
        }
        // A separately attested accepted successor closes all earlier collected entries. Publish
        // that ordinary proof, then resume from the durable head on the next attempt or restart.
        // Keep at least one closed entry: repeatedly authenticating only the pending successor
        // would otherwise make no progress for providers advertising a one-entry page limit.
        if (entries.size() > 1 && chunkFull(pageNumber + 1, entries.size(), size.intValueExact())) {
          LanguageContent acceptance = acceptance(reader, page);
          return publish(
              reader, "Timeline Successor Evidence", "successor", acceptance.blueId(), entries);
        }
        request =
            request(
                Map.of(
                    "limit",
                    size,
                    "cursor",
                    Json.object(
                        page.nextCursor()
                            .orElseThrow()
                            .toJson()
                            .getBytes(StandardCharsets.UTF_8))));
        read = page.continueWith(request);
      }
      throw new IllegalStateException("Timeline acquisition page limit exceeded");
    }

    private boolean chunkFull(int pages, int entries, int pageSize) {
      return pages >= Math.min(CHUNK_PAGES, limits.pages())
          || entries > limits.entries() - pageSize
          || wireBytes >= limits.bytes() / 2L
          || recordBytes >= limits.bytes() / 2L
          || records.size() >= limits.records() / 2
          || clock.getAsLong() - started >= limits.duration().toNanos() / 2;
    }

    @SuppressWarnings("try")
    private LanguageContent acceptance(TimelineReader reader, TimelineReader.Page page)
        throws IOException, InterruptedException {
      LanguageContent request =
          request(
              "Fetch Timeline Entry Request",
              Map.of("entry", reference(page.entries().getLast().blueId())));
      TimelineReader.EntryRead read = page.fetchLastEntry(request);
      remember(reader, request);
      TimelineTransport.Reply reply;
      try (var timing = archive.metrics.time(TimelineMetrics.Phase.READ_ENTRY_HTTP)) {
        reply = exchange("/timelines/entry", request.toJson());
      }
      LanguageContent response, acceptance;
      try (var timing = archive.metrics.time(TimelineMetrics.Phase.VERIFY_ENTRY)) {
        response = parse(reply);
        acceptance = resolving(reader, () -> read.accept(response));
      }
      if (reply.status() != 200) {
        throw new IOException("Timeline entry HTTP " + reply.status());
      }
      remember(reader, response);
      return acceptance;
    }

    private TimelineArchive.Acquired publish(
        TimelineReader reader, String type, String field, BlueId attestation, List<Object> entries)
        throws IOException, InterruptedException {
      Map<String, Object> proof = new LinkedHashMap<>();
      proof.put("type", "Coordination/" + type);
      proof.put(field, reference(attestation));
      proof.put("entries", entries);
      context.prefix().headId().ifPresent(id -> proof.put("after", reference(id)));
      LanguageContent evidence = language.prepare(Language.Input.json(JsonText.of(proof)));
      remaining();
      return resolving(reader, () -> archive.acquire(evidence.toJson(), records, local.records()));
    }

    /**
     * Retry only missing exact content; signature, schema and resource failures remain failures.
     */
    private <T> T resolving(TimelineReader reader, java.util.function.Supplier<T> operation)
        throws IOException, InterruptedException {
      while (true) {
        remaining();
        try {
          return operation.get();
        } catch (LanguageException unavailable) {
          checkContentFailure();
          if (!unavailable.category().equals("ProviderUnavailable")) {
            throw unavailable;
          }
          BlueId target = new BlueId(unavailable.diagnostic().at("/detail").text());
          if (fetched.contains(target)) {
            throw unavailable;
          }
          fetchMissing(reader, target);
        }
      }
    }

    private void fetchMissing(TimelineReader reader, BlueId target)
        throws IOException, InterruptedException {
      if (!fetched.add(target)) {
        throw new IllegalStateException("Timeline acquisition repeated unresolved content");
      }
      if (fetched.size() > CONTENT_FETCHES) {
        throw new IllegalStateException("Timeline acquisition content-fetch limit exceeded");
      }
      fetch(reader, target);
    }

    @SuppressWarnings("try")
    private void fetch(TimelineReader reader, BlueId target)
        throws IOException, InterruptedException {
      LanguageContent request =
          request("Resolve Timeline Content Request", Map.of("content", reference(target)));
      TimelineReader.ContentRead read = reader.content(request);
      TimelineTransport.Reply reply;
      try (var timing = archive.metrics.time(TimelineMetrics.Phase.READ_CONTENT_HTTP)) {
        reply = exchange("/timelines/content", request.toJson());
      }
      LanguageContent content;
      try (var timing = archive.metrics.time(TimelineMetrics.Phase.VERIFY_CONTENT)) {
        content = read.accept(parse(reply));
      }
      if (reply.status() != 200) {
        throw new IOException("Timeline content HTTP " + reply.status());
      }
      // Only exact content is retained. The signed reader envelope never enters the archive.
      // Its references are resolved as demanded by ordinary validation, within this same budget.
      add(target, LanguageRecord.json(content.toJson()));
    }

    @SuppressWarnings("try")
    private void remember(TimelineReader reader, LanguageContent content)
        throws IOException, InterruptedException {
      remaining();
      resolving(
          reader,
          () -> {
            try (var timing = archive.metrics.time(TimelineMetrics.Phase.COLLECT_RECORDS)) {
              collect(content);
            }
            return null;
          });
    }

    private LanguageContent request(Map<String, Object> fields) {
      return request("Read Timeline Request", fields);
    }

    private LanguageContent request(String type, Map<String, Object> fields) {
      Map<String, Object> request = new LinkedHashMap<>(fields);
      request.put("type", "Coordination/" + type);
      request.put("timeline", reference(archive.source().timeline()));
      return language.prepare(Language.Input.json(JsonText.of(request)));
    }

    private TimelineTransport.Reply exchange(String route, String request)
        throws IOException, InterruptedException {
      // Only the transport receives the credential-bearing envelope. Prefix verification and
      // retained evidence keep the original inner request, never a replayable signed credential.
      if (request != null && signing != null) {
        TimelineRequests.Operation operation =
            switch (route) {
              case "/timelines/read" -> TimelineRequests.Operation.READ;
              case "/timelines/entry" -> TimelineRequests.Operation.ENTRY;
              case "/timelines/content" -> TimelineRequests.Operation.CONTENT;
              default ->
                  throw new IllegalArgumentException("Reader signing is restricted to read routes");
            };
        request =
            signing.sign(operation, language.prepare(Language.Input.exactJson(request))).toJson();
      }
      if (request != null) {
        wireBytes = Math.addExact(wireBytes, request.getBytes(StandardCharsets.UTF_8).length);
      }
      long available = limits.bytes() - wireBytes;
      if (available < 1) {
        throw new IllegalStateException("Timeline acquisition wire limit exceeded");
      }
      int maximum = (int) Math.min(available, 4 * 1024 * 1024);
      TimelineTransport.Reply reply =
          transport.exchange(URI.create(context.endpoint() + route), request, maximum, remaining());
      if (reply.byteSize() > maximum) {
        throw new IOException("Timeline response exceeds requested bound");
      }
      wireBytes += reply.byteSize();
      remaining();
      return reply;
    }

    private LanguageContent parse(TimelineTransport.Reply response) throws IOException {
      // The local transport owns both representations of this exact message. The reader still
      // verifies its signature, request binding and permissions through the ordinary accept path.
      if (response.content() != null) {
        return language.prepare(response.content());
      }
      String exact =
          StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(response.body())).toString();
      return language.prepare(Language.Input.exactJson(exact));
    }

    @SuppressWarnings("try")
    private void remember(LanguageContent content) throws InterruptedException {
      remaining();
      try (var timing = archive.metrics.time(TimelineMetrics.Phase.COLLECT_RECORDS)) {
        collect(content);
      }
    }

    private void collect(LanguageContent content) {
      Map<BlueId, LanguageRecord> additions =
          content.records(
              id ->
                  id.equals(deferredHead)
                      || records.containsKey(id)
                      || local.records().contains(id)
                      || established.computeIfAbsent(id, context.available()::test));
      if (additions.size() > limits.records() - records.size()) {
        throw new IllegalStateException("Timeline acquisition record limit exceeded");
      }
      for (var entry : additions.entrySet()) {
        add(entry.getKey(), entry.getValue());
      }
    }

    private void add(BlueId id, LanguageRecord record) {
      if (records.containsKey(id)) {
        return;
      }
      if (records.size() >= limits.records()) {
        throw new IllegalStateException("Timeline acquisition record limit exceeded");
      }
      recordBytes += record.toJson().getBytes(StandardCharsets.UTF_8).length;
      if (recordBytes > limits.bytes()) {
        throw new IllegalStateException("Timeline acquisition content limit exceeded");
      }
      records.put(id, record);
    }

    private Duration remaining() throws InterruptedException {
      if (Thread.currentThread().isInterrupted()) {
        throw new InterruptedException("Timeline acquisition interrupted");
      }
      long nanos = limits.duration().toNanos() - (clock.getAsLong() - started);
      if (nanos <= 0) {
        throw new IllegalStateException("Timeline acquisition deadline exceeded");
      }
      return Duration.ofNanos(nanos);
    }
  }

  private static Map<String, String> reference(BlueId id) {
    return Map.of("blueId", id.value());
  }
}
