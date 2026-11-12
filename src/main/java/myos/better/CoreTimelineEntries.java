package myos.better;

import blue.BlueException;
import blue.BlueId;
import blue.LanguageContent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Original Timeline form targeting, over the same native operation publication as Documents. */
final class CoreTimelineEntries {
  private final CoreDocuments api;
  private final ConsoleDocuments documents;
  private final CoreContents contents;
  private final CoreTimelines timelines;

  CoreTimelineEntries(
      CoreDocuments api,
      ConsoleDocuments documents,
      CoreContents contents,
      CoreTimelines timelines) {
    this.api = api;
    this.documents = documents;
    this.contents = contents;
    this.timelines = timelines;
  }

  Map<String, Object> append(
      String account, BlueId timeline, String command, Map<String, Object> input) {
    RuntimeConfiguration.fields(
        input, Set.of("inputMode", "actor", "message", "prevEntry", "documentId", "authority"));
    if (!input.containsKey("documentId")) {
      return timelines.append(account, timeline, command, input);
    }
    if (input.containsKey("authority")) {
      throw new ConsoleProblem(
          501, "CORE_PARITY_PENDING", "Delegated entry authority is not yet ported.");
    }
    String initial = RuntimeConfiguration.text(input.get("documentId"), "documentId");
    try {
      new BlueId(initial);
    } catch (BlueException invalid) {
      throw new ConsoleProblem(
          422, "invalid_document_id", "Use the document's pre-initialization BlueId.");
    }
    String id = api.target(account, initial);
    if (!input.containsKey("message")) {
      throw new IllegalArgumentException("message is required");
    }
    String mode = RuntimeConfiguration.text(input.getOrDefault("inputMode", "source"), "inputMode");
    var evidence = documents.evidence(account, id);
    LanguageContent message =
        contents.prepare(
            account,
            input.get("message"),
            mode,
            "definition",
            evidence.stored(),
            evidence.values());
    var parsed = documents.operationRequest(account, id, message, evidence.values());
    if (parsed.isEmpty()) {
      var ordinary = new LinkedHashMap<>(input);
      ordinary.remove("documentId");
      var result = new LinkedHashMap<>(timelines.append(account, timeline, command, ordinary));
      result.put("documentId", initial);
      return result;
    }
    var operation = parsed.orElseThrow();
    if (operation.document().isEmpty()) {
      if (!mode.equals("source")) {
        throw targetRequired();
      }
      Object authored = CoreSourceInputs.parsed(input.get("message"));
      if (!(authored instanceof Map<?, ?> raw) || raw.containsKey("blueId")) {
        throw targetRequired();
      }
      var updated = new LinkedHashMap<>(RuntimeConfiguration.object(raw, "message"));
      updated.put("document", ProviderSigning.ref(new BlueId(initial)));
      message =
          contents.prepare(
              account, updated, "source", "definition", evidence.stored(), evidence.values());
    } else if (!operation.document().orElseThrow().value().equals(initial)
        && !operation.requireExactDocumentVersion()) {
      throw new ConsoleProblem(
          422,
          "document_target_mismatch",
          "Use documentId, or an exact-version request for the same document.");
    }
    boolean writer =
        ((List<?>) documents.operations(account, id, timelines.session()).get("channels"))
            .stream()
                .map(raw -> RuntimeConfiguration.object(raw, "channel"))
                .anyMatch(
                    channel ->
                        timeline.value().equals(channel.get("timeline"))
                            && Boolean.TRUE.equals(channel.get("writable")));
    if (!writer) {
      throw new ConsoleProblem(
          422,
          "document_writer_mismatch",
          "The selected Timeline is not a writer channel of this document.");
    }
    String intent = "timeline-entry-" + Json.hash(Json.bytes(List.of(timeline.value(), command)));
    return timelines.publishOperation(
        account,
        documents.nativeId(account, id),
        initial,
        timeline,
        intent,
        input,
        evidence,
        message,
        (value, completed) -> documents.validateOperation(account, id, value, completed));
  }

  private static ConsoleProblem targetRequired() {
    return new ConsoleProblem(
        422,
        "native_target_required",
        "Exact or referenced Operation Requests must contain their native document reference.");
  }
}
