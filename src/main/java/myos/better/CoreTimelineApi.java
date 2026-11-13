package myos.better;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Account-scoped timeline routes. Admissions capture the same session as the timeline view. */
final class CoreTimelineApi {
  private final CoreTimelines timelines;
  private final CoreAdmissions admissions;
  private final CoreDocuments coreDocuments;
  private final ConsoleDocuments documents;
  private final CoreContents contents;

  CoreTimelineApi(
      CoreTimelines timelines,
      CoreAdmissions admissions,
      CoreDocuments coreDocuments,
      ConsoleDocuments documents,
      CoreContents contents) {
    this.timelines = timelines;
    this.admissions = admissions;
    this.coreDocuments = coreDocuments;
    this.documents = documents;
    this.contents = contents;
  }

  Object call(
      String route,
      String method,
      Map<String, String> query,
      String command,
      Map<String, Object> input,
      CoreAccounts.Session session) {
    if (timelines == null) {
      throw new ConsoleProblem(
          503, "TIMELINES_NOT_CONFIGURED", "Timeline publication is not configured.");
    }
    String account = session.principal().account();
    CoreTimelines timelines = this.timelines.as(session);
    String[] parts = route.split("/", -1);
    if (parts.length == 1) {
      if (method.equals("GET")) {
        return timelines.list(account, query);
      }
      if (method.equals("POST")) {
        return new CoreDashboardApi.Response(
            202,
            admissions.submit(
                account,
                route,
                command,
                input,
                "TIMELINE",
                "TIMELINE_STORED",
                () -> timelines.create(account, command, input)));
      }
    }
    if (parts.length == 2 && parts[1].equals("import") && method.equals("POST")) {
      RuntimeConfiguration.fields(new HashMap<>(query), Set.of());
      return new CoreDashboardApi.Response(201, timelines.importTimeline(account, input));
    }
    if (parts.length < 2 || parts[1].equals("import")) {
      throw CoreDashboardApi.unavailable(route);
    }
    var id = new blue.BlueId(parts[1]);
    if (parts.length == 3 && parts[2].equals("guarantees") && method.equals("POST")) {
      return timelines.guarantees(account, id, command, CoreTimelineGuarantees.input(query, input));
    }
    if (parts.length == 3 && parts[2].equals("retry") && method.equals("POST")) {
      RuntimeConfiguration.fields(new HashMap<>(query), Set.of());
      return timelines.retry(account, id, input);
    }
    if (parts.length == 2 && method.equals("GET")) {
      return timelines.read(account, id);
    }
    if (parts.length == 3 && parts[2].equals("entries")) {
      if (method.equals("GET")) {
        return timelines.entries(account, id, query);
      }
      if (method.equals("POST")) {
        return new CoreDashboardApi.Response(
            202,
            admissions.submit(
                account,
                route,
                command,
                input,
                "TIMELINE_ENTRY",
                "TIMELINE_ENTRY_STORED",
                () ->
                    new CoreTimelineEntries(coreDocuments, documents, contents, timelines)
                        .append(account, id, command, input)));
      }
    }
    if (parts.length == 4 && parts[2].equals("entries") && method.equals("GET")) {
      return timelines.entry(account, id, new blue.BlueId(parts[3]));
    }
    if (parts.length == 3 && parts[2].equals("access")) {
      if (method.equals("GET")) {
        return timelines.accesses(account, id);
      }
      if (method.equals("POST")) {
        return timelines.updateAccess(account, id, command, input);
      }
    }
    throw CoreDashboardApi.unavailable(route);
  }
}
