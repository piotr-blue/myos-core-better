package myos.better;

import jdk.jfr.*;

/** Optional bounded recording of host phases. Never records payloads, tokens or secret values. */
@Name("myos.HostPhase")
@Label("Host phase")
@Category({"MyOS", "Host"})
@StackTrace(false)
final class HostPhaseEvent extends Event implements AutoCloseable {
  public String phase;
  public String account;
  public String subject;
  public String entry = "";

  static HostPhaseEvent start(String phase, String account, String subject) {
    var event = new HostPhaseEvent();
    event.phase = phase;
    event.account = account;
    event.subject = subject;
    event.begin();
    return event;
  }

  @SuppressWarnings("try")
  static <T> T measure(
      String phase, String account, String subject, java.util.function.Supplier<T> action) {
    try (var ignored = start(phase, account, subject)) {
      return action.get();
    }
  }

  @Override
  public void close() {
    end();
    commit();
  }
}
