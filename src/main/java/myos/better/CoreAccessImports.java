package myos.better;

import blue.BlueException;
import blue.BlueId;
import blue.LanguageException;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Imports an access follower only after replay authenticates the provider's current claim. */
final class CoreAccessImports implements AutoCloseable {
  private static final TimelinePull.Limits ACQUISITION =
      new TimelinePull.Limits(256, 8, 2048, 8192, 2 * 1024 * 1024, Duration.ofSeconds(5));
  private final CoreTimelineDiscovery discovery;
  private final CoreTimelineImports imports;
  private final CoreContents contents;
  private final CoreDocuments documents;
  private final CoreRuntime runtime;
  private final TimelineTransport transport;
  private final TimelineAccessHistory histories = new TimelineAccessHistory();

  CoreAccessImports(
      CoreTimelineDiscovery discovery,
      CoreTimelineImports imports,
      CoreContents contents,
      CoreDocuments documents,
      CoreRuntime runtime,
      TimelineTransport transport) {
    this.discovery = Objects.requireNonNull(discovery);
    this.imports = Objects.requireNonNull(imports);
    this.contents = Objects.requireNonNull(contents);
    this.documents = Objects.requireNonNull(documents);
    this.runtime = runtime;
    this.transport = Objects.requireNonNull(transport);
  }

  Map<String, Object> add(String account, Map<String, Object> input) {
    RuntimeConfiguration.fields(input, Set.of("endpoint", "accessId", "executionSelection"));
    String endpoint = RuntimeConfiguration.text(input.get("endpoint"), "endpoint");
    CoreTimelineDiscovery.endpoint(endpoint);
    var access = new BlueId(RuntimeConfiguration.text(input.get("accessId"), "accessId"));
    if (input.containsKey("executionSelection")) {
      if (runtime == null)
        throw new ConsoleProblem(503, "runtime_unavailable", "Runtime selection is unavailable.");
      runtime.validate(input);
      documents.checkSelection(
          account,
          access,
          RuntimeConfiguration.object(input.get("executionSelection"), "executionSelection"));
    }
    CoreTimelineDiscovery.Access selected;
    try {
      selected = discovery.access(endpoint, access);
      var archive = imports.retainControl(account, selected.source());
      new TimelinePull(archive, transport, ACQUISITION, System::nanoTime).refresh();
      var view = archive.view();
      var description = selected.description();
      BlueId publication =
          new BlueId(description.publication().at("/object").observation("exactId").text());
      BlueId latest =
          new BlueId(description.lastEntry().at("/object").observation("exactId").text());
      if (view.find(publication).isEmpty()) throw pending();
      var occurrence = view.find(latest).orElseThrow(CoreAccessImports::pending);
      var order =
          new TimelineInputs.Order(
              occurrence.entry().timestamp(), archive.source().timeline().value(), latest.value());
      var verified =
          histories.at(
              archive,
              archive.discovery().orElseThrow(),
              selected.initial(),
              view.version(),
              order);
      if (!verified.caughtUp()) throw pending();
      if (verified.state().isEmpty()
          || !verified
              .state()
              .orElseThrow()
              .document()
              .equals(description.claimedCurrent().blueId()))
        throw new ConsoleProblem(
            422,
            "invalid_access_history",
            "The access state differs from verified control-history replay.");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw pending();
    } catch (IOException | TimelineArchive.PublicationConflict unavailable) {
      throw (ConsoleProblem) pending().initCause(unavailable);
    } catch (BlueException | LanguageException invalid) {
      throw (ConsoleProblem)
          new ConsoleProblem(
                  422,
                  "invalid_access_history",
                  "The provider response or access history could not be verified.")
              .initCause(invalid);
    }
    contents.retain(account, selected.initial(), false);
    var creation = new LinkedHashMap<String, Object>();
    creation.put("document", Map.of("blueId", access.value()));
    creation.put("inputMode", "exact");
    if (input.containsKey("executionSelection"))
      creation.put("executionSelection", input.get("executionSelection"));
    String command = "access-" + access.value();
    if (input.containsKey("executionSelection"))
      command += "-" + Json.hash(Json.bytes(input.get("executionSelection")));
    var result = new LinkedHashMap<>(documents.create(account, command, creation));
    result.put("documentId", access.value());
    return result;
  }

  private static ConsoleProblem pending() {
    return new ConsoleProblem(
        503,
        "access_history_unavailable",
        "Verified control history is not complete yet. Retry the same access import.");
  }

  public void close() {
    histories.close();
  }
}
