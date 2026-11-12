package myos.better;

import java.util.*;

/** Committed Time registrations share the native owner's conditional publication. */
final class OwnerTimers {
  static final int MAX_PER_MEMBER = 1024, MAX_PER_ACCOUNT = 16384;

  enum Status {
    PENDING,
    FIRED,
    NO_MATCH,
    FAILED,
    REJECTED,
    CANCELLED,
    REPLACED,
    INVALIDATED
  }

  record Payload(
      String request,
      String source,
      String entry,
      String message,
      String content,
      String catalogue,
      String occurrence) {
    Payload {
      Objects.requireNonNull(request);
      Objects.requireNonNull(source);
      Objects.requireNonNull(entry);
      Objects.requireNonNull(message);
      Objects.requireNonNull(content);
      Objects.requireNonNull(catalogue);
      Objects.requireNonNull(occurrence);
    }
  }

  record Registration(
      String id,
      String owner,
      String member,
      String channel,
      long generation,
      String key,
      long timestamp,
      Payload payload,
      Status status) {
    Registration {
      Objects.requireNonNull(id);
      Objects.requireNonNull(owner);
      Objects.requireNonNull(member);
      Objects.requireNonNull(channel);
      Objects.requireNonNull(payload);
      Objects.requireNonNull(status);
      if (generation < 0 || timestamp < 0 || timestamp > 9_007_199_254_740_991L)
        throw new IllegalArgumentException("Invalid Time lifetime or timestamp");
    }

    OwnerFrontiers.Order order() {
      return new OwnerFrontiers.Order(timestamp, payload.source(), payload.entry());
    }

    Registration terminal(Status next) {
      if (next == Status.PENDING) throw new IllegalArgumentException("Terminal status required");
      return new Registration(
          id, owner, member, channel, generation, key, timestamp, payload, next);
    }
  }

  record Member(
      String id, String owner, Map<String, Long> channels, boolean terminated, Long floor) {
    Member {
      channels = Map.copyOf(channels);
    }
  }

  record Effect(
      String member,
      String occurrence,
      String event,
      String channel,
      long generation,
      String key,
      Registration registration) {
    boolean cancel() {
      return registration == null;
    }
  }

  record Turn(
      List<Member> members, List<Effect> effects, String consumedEntry, Status consumedStatus) {
    Turn {
      members = List.copyOf(members);
      effects = List.copyOf(effects);
      if (!Set.of(Status.FIRED, Status.NO_MATCH, Status.FAILED).contains(consumedStatus))
        throw new IllegalArgumentException("Invalid Time processing disposition");
    }

    Turn(List<Member> members, List<Effect> effects, String consumedEntry) {
      this(members, effects, consumedEntry, Status.FIRED);
    }
  }

  private static String key(String kind, String id) {
    return "time-" + kind + "\0" + id;
  }

  private static String binding(String id) {
    return "time:" + id;
  }

  private static String pending(Registration r) {
    return key("pending", r.member()) + "\0" + r.order().key();
  }

  private static String alarm(Registration r) {
    return key("alarm", r.order().key());
  }

  static Registration registration(OwnerMetadata.Transaction tx, String id) {
    String value = tx.get(key("registration", id));
    return value == null
        ? null
        : Json.MAPPER.convertValue(
            Json.object(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            Registration.class);
  }

  static Registration input(OwnerMetadata.Transaction tx, OwnerFrontiers.Order order) {
    String id = tx.get(key("entry", order.entry()));
    if (id == null) return null;
    Registration result = registration(tx, id);
    if (result == null || !result.order().equals(order))
      throw new IllegalStateException("Conflicting Time input");
    return result;
  }

  record PendingPage(List<Registration> items, String after, int total) {}

  static PendingPage pendingPage(
      OwnerMetadata.Transaction tx, String member, String after, int limit) {
    if (limit < 1 || limit > 100) throw new IllegalArgumentException("Timer page must be 1..100");
    String prefix = key("pending", member) + "\0";
    if (after != null && !after.startsWith(prefix))
      throw new IllegalArgumentException("Foreign timer cursor");
    var rows = tx.page(prefix, after, limit + 1);
    boolean more = rows.size() > limit;
    var selected = rows.subList(0, Math.min(limit, rows.size()));
    tx.prefetch(selected.stream().map(r -> key("registration", r.value())).toList());
    var items =
        selected.stream()
            .map(
                row -> {
                  var r = registration(tx, row.value());
                  if (r == null || r.status() != Status.PENDING || !r.member().equals(member))
                    throw new IllegalStateException("Invalid pending Time index");
                  return r;
                })
            .toList();
    String count = tx.get(key("count", member));
    return new PendingPage(
        items, more ? selected.getLast().key() : null, count == null ? 0 : Integer.parseInt(count));
  }

  static List<Registration> pending(OwnerMetadata.Transaction tx, String member) {
    String prefix = key("pending", member) + "\0";
    List<Registration> result = new ArrayList<>();
    String after = null;
    do {
      var page = tx.page(prefix, after, 128);
      for (var row : page) {
        Registration registration = registration(tx, row.value());
        if (registration == null || registration.status() != Status.PENDING)
          throw new IllegalStateException("Invalid pending Time index");
        result.add(registration);
      }
      if (result.size() > MAX_PER_MEMBER)
        throw new IllegalStateException("Time index exceeds quota");
      if (page.size() < 128) break;
      after = page.getLast().key();
    } while (true);
    return List.copyOf(result);
  }

  static boolean apply(OwnerFrontiers frontiers, OwnerMetadata.Transaction tx, Turn turn) {
    Map<String, List<Effect>> effects = new HashMap<>();
    turn.effects()
        .forEach(e -> effects.computeIfAbsent(e.member(), ignored -> new ArrayList<>()).add(e));
    long delta = 0;
    boolean touched = false;
    Set<String> seen = new HashSet<>();
    for (Member member : turn.members()) {
      if (!seen.add(member.id())) throw new IllegalArgumentException("Duplicate Time member");
      List<Effect> changes = effects.remove(member.id());
      String countKey = key("count", member.id());
      long before = number(tx.get(countKey));
      if (before == 0 && (changes == null || changes.isEmpty())) continue;
      touched = true;
      Map<String, Registration> active = new LinkedHashMap<>();
      pending(tx, member.id()).forEach(r -> active.put(r.id(), r));
      if (active.size() != before) throw new IllegalStateException("Inconsistent Time count");
      for (Registration r : List.copyOf(active.values())) {
        if (!r.owner().equals(member.owner()))
          throw new IllegalStateException("Time ownership changed");
        Status terminal =
            r.payload().entry().equals(turn.consumedEntry())
                ? turn.consumedStatus()
                : member.terminated()
                        || !Objects.equals(member.channels().get(r.channel()), r.generation())
                    ? Status.INVALIDATED
                    : null;
        if (terminal != null) {
          terminal(frontiers, tx, r, terminal);
          active.remove(r.id());
        }
      }
      for (Effect effect : changes == null ? List.<Effect>of() : changes) {
        String effectKey = key("effect", member.id()) + "\0" + effect.occurrence();
        String prior = tx.get(effectKey);
        if (prior != null) {
          if (!prior.equals(effect.event()))
            throw new IllegalStateException("Conflicting Time effect identity");
          continue;
        }
        if (member.terminated()
            || !Objects.equals(member.channels().get(effect.channel()), effect.generation()))
          throw new OwnerRejections.Failure("TIME_INVALID_CHANNEL");
        if (effect.cancel() && effect.key() == null)
          throw new OwnerRejections.Failure("TIME_INVALID_NAME");
        if (effect.key() != null)
          for (Registration r : List.copyOf(active.values())) {
            if (r.channel().equals(effect.channel())
                && r.generation() == effect.generation()
                && effect.key().equals(r.key())) {
              terminal(frontiers, tx, r, effect.cancel() ? Status.CANCELLED : Status.REPLACED);
              active.remove(r.id());
            }
          }
        if (!effect.cancel()) {
          Registration r = effect.registration();
          if (!r.member().equals(member.id())
              || !r.owner().equals(member.owner())
              || !r.channel().equals(effect.channel())
              || r.generation() != effect.generation()
              || !Objects.equals(r.key(), effect.key())
              || !r.payload().occurrence().equals(effect.occurrence())
              || !r.payload().request().equals(effect.event())
              || r.status() != Status.PENDING)
            throw new IllegalArgumentException("Inconsistent Time registration");
          if (member.floor() != null && r.timestamp() <= member.floor())
            throw new OwnerRejections.Failure("TIME_DEADLINE_BEFORE_POSITION");
          if (registration(tx, r.id()) != null)
            throw new IllegalStateException("Time registration identity reused");
          String entryKey = key("entry", r.payload().entry());
          if (tx.get(entryKey) != null)
            throw new IllegalStateException("Time entry identity reused");
          frontiers.timer(
              tx, r.id(), new OwnerFrontiers.Original(r.order(), r.payload().content()));
          frontiers.configureTimerSources(
              tx,
              r.owner(),
              List.of(
                  new OwnerFrontiers.Source(
                      binding(r.id()), OwnerFrontiers.Kind.TIMER, r.id(), r.generation(), 0)),
              Set.of());
          put(tx, r);
          tx.put(entryKey, r.id());
          tx.put(pending(r), r.id());
          tx.put(alarm(r), r.id());
          active.put(r.id(), r);
        }
        tx.put(effectKey, effect.event());
      }
      if (active.size() > MAX_PER_MEMBER) throw new OwnerRejections.Failure("TIME_PENDING_LIMIT");
      delta += active.size() - before;
      tx.put(countKey, Integer.toString(active.size()));
    }
    if (!effects.isEmpty())
      throw new IllegalArgumentException("Time effect belongs to an unscheduled member");
    long count = Math.addExact(number(tx.get(key("total", "pending"))), delta);
    if (count < 0) throw new IllegalStateException("Negative Time pending count");
    if (count > MAX_PER_ACCOUNT) throw new OwnerRejections.Failure("TIME_PENDING_LIMIT");
    if (delta != 0) tx.put(key("total", "pending"), Long.toString(count));
    return touched;
  }

  static List<String> activateDue(
      OwnerFrontiers frontiers, OwnerMetadata.Transaction tx, long nowMicros, int limit) {
    if (limit < 1 || limit > 128)
      throw new IllegalArgumentException("Time activation batch must be 1..128");
    Set<String> owners = new LinkedHashSet<>();
    for (var row : tx.page(key("alarm", ""), null, limit)) {
      Registration r = registration(tx, row.value());
      if (r == null || r.status() != Status.PENDING)
        throw new IllegalStateException("Invalid Time alarm");
      if (r.timestamp() > nowMicros) break;
      frontiers.activateTimer(tx, r.id(), nowMicros);
      tx.remove(row.key());
      tx.put(key("active", r.id()), r.owner());
      owners.add(r.owner());
    }
    return List.copyOf(owners);
  }

  static Long nextAlarm(OwnerMetadata.Transaction tx) {
    var next = tx.page(key("alarm", ""), null, 1);
    return next.isEmpty() ? null : registration(tx, next.getFirst().value()).timestamp();
  }

  static boolean hasActive(OwnerMetadata.Transaction tx) {
    return !tx.page(key("active", ""), null, 1).isEmpty();
  }

  /** Consume only this original on rollback; all other pending registrations remain unchanged. */
  static void reject(
      OwnerFrontiers frontiers,
      OwnerMetadata.Transaction tx,
      String owner,
      OwnerFrontiers.Order order) {
    var registration = input(tx, order);
    if (registration == null || !registration.owner().equals(owner)) return;
    if (registration.status() != Status.PENDING)
      throw new IllegalStateException("Rejected Time input is not pending");
    long member = number(tx.get(key("count", registration.member())));
    long total = number(tx.get(key("total", "pending")));
    if (member < 1 || total < 1) throw new IllegalStateException("Inconsistent Time counts");
    terminal(frontiers, tx, registration, Status.REJECTED);
    tx.put(key("count", registration.member()), Long.toString(member - 1));
    tx.put(key("total", "pending"), Long.toString(total - 1));
  }

  private static void terminal(
      OwnerFrontiers frontiers, OwnerMetadata.Transaction tx, Registration r, Status status) {
    put(tx, r.terminal(status));
    tx.remove(pending(r));
    tx.remove(alarm(r));
    tx.remove(key("active", r.id()));
    frontiers.configureTimerSources(tx, r.owner(), List.of(), Set.of(binding(r.id())));
  }

  private static void put(OwnerMetadata.Transaction tx, Registration r) {
    tx.put(key("registration", r.id()), JsonText.of(r));
  }

  private static long number(String value) {
    return value == null ? 0 : Long.parseLong(value);
  }
}
