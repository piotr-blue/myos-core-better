package myos.better;

import static myos.better.ProviderSigning.ref;
import static myos.better.ProviderSigning.wire;

import blue.BlueId;
import blue.Language;
import blue.LanguageContent;
import blue.TimelineRequests;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Signed, checkpoint-bound read assembly after an owner-scoped lookup. This component does not
 * authenticate remote readers. A transport must check current permission separately on every call.
 */
final class HostedTimelineReads {
  private final HostedTimelines timelines;
  private final ProviderSigning signing;
  private final int pageSize, maximumBytes;

  HostedTimelineReads(
      HostedTimelines timelines, ProviderSigning signing, int pageSize, int maximumBytes) {
    if (pageSize < 1 || pageSize > 256 || maximumBytes < 1024 || maximumBytes > 4 * 1024 * 1024) {
      throw new IllegalArgumentException("Invalid provider read limits");
    }
    this.timelines = Objects.requireNonNull(timelines);
    this.signing = Objects.requireNonNull(signing);
    this.pageSize = pageSize;
    this.maximumBytes = maximumBytes;
  }

  LanguageContent read(String account, BlueId id, LanguageContent request) {
    HostedTimelines.Head owned = timelines.head(account, id);
    TimelineRequests.Read query = parser(owned).read(request);
    HostedTimelines.Snapshot snapshot =
        timelines.snapshot(
            account, id, query.checkpoint().orElse(null), query.minimumCompleteBefore());
    if (query.limit() == 0) {
      return bounded(
          response(
              request,
              snapshot.checkpoint().attestation(),
              null,
              context(owned, request, snapshot)));
    }
    long lower =
        query.afterEntry().isPresent()
            ? snapshot.position(query.afterEntry().orElseThrow())
            : query.afterTimestamp().isPresent()
                ? snapshot.afterTimestamp(query.afterTimestamp().getAsLong())
                : 0;
    LanguageContent predecessor = lower == 0 ? null : snapshot.entry(lower).entry();
    List<LanguageContent> selected = new ArrayList<>();
    long bytes = predecessor == null ? 0 : bytes(predecessor);
    long available = Math.min(query.limit(), snapshot.checkpoint().count() - lower);
    for (int i = 1; i <= available; i++) {
      LanguageContent entry = snapshot.entry(lower + i).entry();
      selected.add(entry);
      bytes += bytes(entry);
      // Keep one over-budget candidate so the exact envelope size, not an estimate, decides.
      if (bytes > maximumBytes) {
        break;
      }
    }
    Language.Builder context = context(owned, request, snapshot);
    if (predecessor != null) {
      context.content(predecessor);
    }
    selected.forEach(context::content);
    Language language = context.build();
    if (selected.isEmpty()) {
      return bounded(page(query, snapshot, id, lower, predecessor, selected, language));
    }
    // Serialization/signing is bounded by log(pageSize), never by the total source length.
    LanguageContent full = page(query, snapshot, id, lower, predecessor, selected, language);
    if (bytes(full) <= maximumBytes) {
      return full;
    }
    int low = 1, high = selected.size() - 1;
    LanguageContent accepted = null;
    while (low <= high) {
      int count = low + (high - low) / 2;
      LanguageContent candidate =
          page(query, snapshot, id, lower, predecessor, selected.subList(0, count), language);
      if (bytes(candidate) <= maximumBytes) {
        accepted = candidate;
        low = count + 1;
      } else {
        high = count - 1;
      }
    }
    if (accepted == null) {
      throw new LimitExceeded();
    }
    return accepted;
  }

  LanguageContent entry(String account, BlueId id, LanguageContent request) {
    HostedTimelines.Head owned = timelines.head(account, id);
    BlueId target = parser(owned).entry(request);
    HostedTimelines.Published found = timelines.entry(account, id, target);
    Language context =
        signing.language().toBuilder()
            .content(owned.created().timeline())
            .content(request)
            .content(found.entry())
            .content(found.attestation())
            .build();
    LanguageContent result =
        signing.attest(
            signing.typed(
                "Timeline Entry Response",
                Map.of(
                    "request",
                    ref(request.blueId()),
                    "entry",
                    wire(found.entry()),
                    "entryAttestation",
                    wire(found.attestation()))));
    return bounded(context.prepare(Language.Input.exactJson(result.toJson())));
  }

  private TimelineRequests parser(HostedTimelines.Head owned) {
    return TimelineRequests.forTimeline(
        signing.coordination(), signing.provider(), owned.created().timeline(), pageSize);
  }

  private Language.Builder context(
      HostedTimelines.Head owned, LanguageContent request, HostedTimelines.Snapshot snapshot) {
    return signing.language().toBuilder()
        .content(owned.created().timeline())
        .content(request)
        .content(snapshot.checkpoint().attestation());
  }

  private LanguageContent page(
      TimelineRequests.Read query,
      HostedTimelines.Snapshot snapshot,
      BlueId timeline,
      long lower,
      LanguageContent predecessor,
      List<LanguageContent> entries,
      Language language) {
    boolean more = lower + entries.size() < snapshot.checkpoint().count();
    LanguageContent checkpoint = snapshot.checkpoint().attestation();
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("timeline", ref(timeline));
    fields.put("checkpoint", ref(checkpoint.blueId()));
    fields.put("entries", entries.stream().map(ProviderSigning::wire).toList());
    fields.put("hasMore", more);
    if (predecessor != null) {
      fields.put("predecessor", wire(predecessor));
    }
    if (more) {
      if (entries.isEmpty()) {
        throw new LimitExceeded();
      }
      LanguageContent cursor =
          signing.typed(
              "Timeline Read Cursor",
              Map.of(
                  "timeline",
                  ref(timeline),
                  "checkpoint",
                  ref(checkpoint.blueId()),
                  "afterEntry",
                  ref(entries.getLast().blueId())));
      fields.put("nextCursor", wire(cursor));
    }
    return response(
        query.request(), checkpoint, signing.typed("Timeline Entries Page", fields), language);
  }

  private LanguageContent response(
      LanguageContent request,
      LanguageContent checkpoint,
      LanguageContent page,
      Language.Builder language) {
    return response(request, checkpoint, page, language.build());
  }

  private LanguageContent response(
      LanguageContent request,
      LanguageContent checkpoint,
      LanguageContent page,
      Language language) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("request", ref(request.blueId()));
    fields.put("checkpoint", wire(checkpoint));
    if (page != null) {
      fields.put("page", wire(page));
    }
    LanguageContent result = signing.attest(signing.typed("Timeline Read Response", fields));
    return language.prepare(Language.Input.exactJson(result.toJson()));
  }

  private LanguageContent bounded(LanguageContent content) {
    if (bytes(content) > maximumBytes) {
      throw new LimitExceeded();
    }
    return content;
  }

  private static int bytes(LanguageContent content) {
    return content.toJson().getBytes(StandardCharsets.UTF_8).length;
  }

  static final class LimitExceeded extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    LimitExceeded() {
      super("Timeline response exceeds the byte limit");
    }
  }
}
