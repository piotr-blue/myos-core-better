package myos.better;

import blue.BlueException;
import blue.ContractInspection;
import blue.Contracts;
import blue.LanguageContent;
import blue.ProjectionLimits;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Bounded exact context from the same authorized stable publication used by operation discovery.
 */
final class CoreOperationContext {
  private static final int MAX_BYTES = 262_144;
  private static final ProjectionLimits LIMITS =
      new ProjectionLimits(MAX_BYTES * 2L, MAX_BYTES + 1L, 256);

  private CoreOperationContext() {}

  static Map<String, Object> read(
      ConsoleDocuments documents,
      String account,
      String session,
      String documentId,
      Map<String, Object> publication,
      Map<String, String> query) {
    check(
        "operation_context".equals(query.get("view")),
        400,
        "unsupported_selector",
        "Unknown content view");
    String inline = query.getOrDefault("inlineContracts", "false");
    check(
        Set.of("true", "false").contains(inline),
        400,
        "unsupported_selector",
        "inlineContracts must be true or false");
    boolean pinned = query.containsKey("documentBlueId");
    check(
        pinned == query.containsKey("epoch"),
        400,
        "publication_required",
        "Provide both documentBlueId and epoch, or omit both to read the current stable"
            + " publication");
    if (pinned)
      check(
          Objects.equals(
                  RuntimeConfiguration.object(publication.get("documentRef"), "documentRef")
                      .get("blueId"),
                  query.get("documentBlueId"))
              && publication.get("epoch").toString().equals(query.get("epoch")),
          409,
          "document_changed",
          "Read the current document publication again");
    String path = query.getOrDefault("path", "");
    check(
        path.isEmpty() || path.startsWith("/") && !path.matches(".*~(?![01]).*"),
        400,
        "invalid_context_path",
        "Invalid canonical document path");
    Integer offset = null;
    if (query.containsKey("childrenOffset")) {
      try {
        offset = Integer.valueOf(query.get("childrenOffset"));
      } catch (NumberFormatException invalid) {
        throw new ConsoleProblem(
            400, "invalid_context_offset", "Provide a nonnegative child offset");
      }
      check(offset >= 0, 400, "invalid_context_offset", "Provide a nonnegative child offset");
    }
    try {
      long version = CoreDocumentHistory.version(Repository.number(publication.get("epoch")));
      LanguageContent content = documents.reference(account, session, version);
      var result = new LinkedHashMap<String, Object>();
      result.put("accountId", account);
      result.put("sessionId", session);
      result.put("documentId", documentId);
      result.put("reviewedPublication", publication);
      result.put("path", path);
      result.putAll(
          project(Contracts.inspect(content), path, offset, Boolean.parseBoolean(inline)));
      return result;
    } catch (BlueException unavailable) {
      throw new ConsoleProblem(
          503,
          "operation_context_unavailable",
          "Canonical context could not be resolved: " + unavailable.code());
    }
  }

  static Map<String, Object> project(
      ContractInspection root, String path, Integer offset, boolean inlineContracts) {
    var selected =
        root.at(path)
            .orElseThrow(
                () ->
                    new ConsoleProblem(
                        404,
                        "context_path_not_found",
                        "The selected document has no value at this path"));
    if (offset == null) {
      String json;
      try {
        json =
            JsonText.of(
                selected.readableLiteral(inlineContracts ? Set.of() : Set.of("contracts"), LIMITS));
      } catch (BlueException limit) {
        if (!limit.code().equals("PROJECTION_LIMIT_EXCEEDED")) throw limit;
        throw oversized(selected);
      }
      if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw oversized(selected);
      return Map.of("valueJson", json);
    }
    List<String> selectors = selected.children();
    check(
        offset >= 0 && offset <= selectors.size(),
        400,
        "invalid_context_offset",
        "The child offset is outside this node");
    List<Object> children = new ArrayList<>();
    int index = offset, bytes = 0;
    while (index < selectors.size() && children.size() < 100) {
      var row =
          Map.of("path", path + "/" + selectors.get(index).replace("~", "~0").replace("/", "~1"));
      int size = Json.bytes(row).length;
      check(
          size <= MAX_BYTES / 2,
          413,
          "context_path_too_large",
          "A canonical child path exceeds the bounded context response");
      if (bytes + size > MAX_BYTES / 2) break;
      children.add(row);
      bytes += size;
      index++;
    }
    var result = new LinkedHashMap<String, Object>();
    result.put("children", children);
    result.put("nextChildrenOffset", index < selectors.size() ? index : null);
    return result;
  }

  private static ConsoleProblem oversized(ContractInspection selected) {
    boolean scalarTooLarge = false;
    var scalar = selected.at("/value");
    if (scalar.isPresent()) {
      try {
        scalarTooLarge =
            JsonText.of(scalar.get().literal(Set.of(), LIMITS))
                    .getBytes(StandardCharsets.UTF_8)
                    .length
                > MAX_BYTES;
      } catch (BlueException failure) {
        if (!failure.code().equals("PROJECTION_LIMIT_EXCEEDED")) throw failure;
        scalarTooLarge = true;
      }
    }
    return scalarTooLarge
        ? new ConsoleProblem(
            413,
            "context_scalar_too_large",
            "Scalar exceeds 262144 bytes and cannot be split into children. Do not repeat this"
                + " identical value read.")
        : new ConsoleProblem(
            413,
            "context_too_large",
            "Context exceeds 262144 bytes. Read childrenOffset=0 with the same path and"
                + " publication, then select a returned child path.");
  }

  private static void check(boolean condition, int status, String code, String message) {
    if (!condition) throw new ConsoleProblem(status, code, message);
  }
}
