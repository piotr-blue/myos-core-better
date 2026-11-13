package myos.better;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Every read of an existing session passes the same account-scoped visibility check. */
final class CoreDocumentReads {
  private final CoreRegistrations registrations;
  private final CoreDocuments coreDocuments;
  private final ConsoleDocuments documents;
  private final CoreEvents events;

  CoreDocumentReads(
      CoreRegistrations registrations,
      CoreDocuments coreDocuments,
      ConsoleDocuments documents,
      CoreEvents events) {
    this.registrations = registrations;
    this.coreDocuments = coreDocuments;
    this.documents = documents;
    this.events = events;
  }

  Object read(
      CoreAccounts.Session session,
      String id,
      String route,
      String[] parts,
      Map<String, String> query) {
    String account = session.principal().account();
    registrations.requireVisible(account, id);
    if (parts.length == 2) {
      return coreDocuments.read(account, id);
    }
    if (parts.length == 3) {
      return switch (parts[2]) {
        case "content" -> coreDocuments.content(account, id, query);
        case "processing" -> coreDocuments.nativeProcessing(account, id);
        case "operations" -> coreDocuments.operations(account, id, session);
        case "timelines" -> coreDocuments.timelines(account, id);
        case "timers" -> coreDocuments.timers(account, id, query);
        case "history" -> coreDocuments.history(account, id, query);
        case "evidence" -> coreDocuments.evidence(account, id, query);
        case "epochs" -> coreDocuments.epochs(account, id, query);
        case "relationships" -> coreDocuments.relationships(account, id, query);
        default -> throw CoreDashboardApi.unavailable(route);
      };
    }
    if (parts.length == 4 && parts[2].equals("processing") && parts[3].equals("history")) {
      return events.list(account, route, query, documents.nativeId(account, id), null, false);
    }
    if (parts.length == 4 && parts[2].equals("processing") && parts[3].equals("requirements")) {
      RuntimeConfiguration.fields(new HashMap<>(query), Set.of());
      return coreDocuments.nativeProcessing(account, id);
    }
    if (parts.length == 4 && parts[2].equals("inputs")) {
      return coreDocuments.input(account, id, parts[3]);
    }
    if (parts[2].equals("epochs") && parts.length == 4) {
      return coreDocuments.epoch(account, id, Long.parseLong(parts[3]));
    }
    if (parts[2].equals("epochs") && parts.length == 5 && parts[4].equals("events")) {
      return coreDocuments.events(account, id, Long.parseLong(parts[3]));
    }
    throw CoreDashboardApi.unavailable(route);
  }
}
