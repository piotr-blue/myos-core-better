package myos.better;

import java.util.function.Supplier;
import jdk.jfr.*;

/** Optional physical preparation measurements; never part of a Blue observation or receipt. */
@Name("myos.OwnerPreparation")
@Label("Owner result preparation")
@Category({"MyOS", "Processing"})
@StackTrace(false)
final class OwnerPreparationEvent extends Event {
  public String owner;
  public String stage;
  public long recordsExamined;
  public long recordsEncoded;
  public long encodedBytes;
  public long recordsReused;
  public long recordsChanged;
  public long recordsRemoved;

  static OwnerPreparationEvent start(String owner, String stage) {
    var event = new OwnerPreparationEvent();
    event.owner = owner;
    event.stage = stage;
    if (event.isEnabled()) event.begin();
    return event;
  }

  void finish() {
    if (isEnabled()) {
      end();
      commit();
    }
  }

  static <T> T measure(String owner, String stage, Supplier<T> work) {
    var event = new OwnerPreparationEvent();
    if (!event.isEnabled()) return work.get();
    event.owner = owner;
    event.stage = stage;
    event.begin();
    try {
      return work.get();
    } finally {
      event.end();
      event.commit();
    }
  }
}
