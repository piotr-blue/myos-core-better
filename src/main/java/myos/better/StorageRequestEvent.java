package myos.better;

import java.util.function.Supplier;
import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.EventType;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * One SDK invocation, including its internal retries. Never records keys, payloads or credentials.
 */
@Name("myos.StorageRequest")
@Label("Storage SDK request")
@Category({"MyOS", "Storage"})
@StackTrace(false)
final class StorageRequestEvent extends Event {
  private static final EventType TYPE = EventType.getEventType(StorageRequestEvent.class);
  private static final StackWalker CALLERS = StackWalker.getInstance();
  public String operation;
  public int requestedItems;
  public String outcome;
  public String errorClass = "";
  public String caller = "";

  static <T> T call(String operation, int requestedItems, Supplier<T> request) {
    if (!TYPE.isEnabled()) {
      return request.get();
    }
    var event = new StorageRequestEvent();
    event.operation = operation;
    event.requestedItems = requestedItems;
    // Record a bounded application path before entering SDK machinery. Disabled profiling
    // takes the return above and performs no stack walk. Never inspect frame arguments.
    event.caller =
        CALLERS.walk(
            frames ->
                frames
                    .filter(
                        frame ->
                            (frame.getClassName().startsWith("myos.")
                                    || frame.getClassName().startsWith("blue."))
                                && !frame.getClassName().equals(StorageRequestEvent.class.getName())
                                && !frame.getClassName().contains("$$Lambda"))
                    .limit(12)
                    .map(frame -> frame.getClassName() + "." + frame.getMethodName())
                    .collect(java.util.stream.Collectors.joining(" > ")));
    event.begin();
    try {
      T result = request.get();
      event.outcome = "RETURNED";
      return result;
    } catch (RuntimeException | Error failure) {
      event.outcome = "THREW";
      event.errorClass = failure.getClass().getSimpleName();
      throw failure;
    } finally {
      event.end();
      event.commit();
    }
  }
}
