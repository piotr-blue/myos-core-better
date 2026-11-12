package myos.better;

import java.util.Map;

/** Original dashboard routes; public selectors and native callback bodies keep their meanings. */
final class CoreWebhookApi implements AutoCloseable {
  private final CoreWebhooks hooks;
  private final CoreWebhookDelivery deliveries;
  private final CoreWebhookDispatch dispatch;

  CoreWebhookApi(
      AtomicStore store, CoreEvents events, CorePageTokens pages, WebhookSecrets secrets) {
    hooks = new CoreWebhooks(store, events, pages, secrets);
    deliveries = new CoreWebhookDelivery(hooks, pages);
    dispatch = secrets == null ? null : new CoreWebhookDispatch(hooks, deliveries);
  }

  void check() {
    if (dispatch != null) dispatch.check();
  }

  void start() {
    if (dispatch != null) dispatch.start();
  }

  @Override
  public void close() {
    if (dispatch != null) dispatch.close();
  }

  Object call(
      String route,
      String method,
      Map<String, String> query,
      CoreAccounts.Session session,
      String command,
      Map<String, Object> input) {
    String[] path = route.split("/", -1);
    boolean get = method.equals("GET"), post = method.equals("POST");
    if (path.length == 1 && get) return hooks.list(session, query);
    if (path.length == 1 && post) {
      empty(query);
      return new CoreDashboardApi.Response(201, hooks.create(session, command, input));
    }
    if (path.length == 2) {
      empty(query);
      if (get) return hooks.read(session, path[1]);
      if (post) return hooks.change(session, path[1], command, input, false);
      if (method.equals("DELETE")) {
        hooks.change(session, path[1], command, input, true);
        return new CoreDashboardApi.Response(204, null);
      }
    }
    if (path.length == 3 && get && path[2].equals("health")) {
      empty(query);
      return deliveries.health(session, path[1]);
    }
    if (path.length >= 3 && path[2].equals("events")) {
      if (path.length == 3 && get) return deliveries.list(session, path[1], query);
      if (path.length == 4 && get) {
        empty(query);
        return deliveries.detail(session, path[1], path[3]);
      }
      if (path.length == 5 && path[4].equals("attempts") && get)
        return deliveries.attempts(session, path[1], path[3], query);
      if (path.length == 5 && path[4].equals("retry") && post) {
        empty(query);
        empty(input);
        return new CoreDashboardApi.Response(
            202, deliveries.retry(session, path[1], path[3], command));
      }
    }
    throw new ConsoleProblem(404, "route_not_found", "Unknown webhook route.");
  }

  private static void empty(Map<String, ?> value) {
    if (!value.isEmpty())
      throw new ConsoleProblem(
          400, "unsupported_field", "This request does not accept extra fields.");
  }
}
