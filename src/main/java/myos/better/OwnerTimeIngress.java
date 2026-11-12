package myos.better;

import blue.*;
import java.util.*;

/** Binds native Time admission to one already claimed, complete durable owner cut. */
final class OwnerTimeIngress {
  private final Language language;
  private final BlueId channelType;
  private final TimeBindings bindings;
  private final ThreadLocal<Frame> invocation = new ThreadLocal<>();

  private static final class Frame {
    final Contracts.Reception reception;
    final OwnerTimers.Registration registration;
    final NativeAdmission.Receiver receiver;
    final Map<String, Object> proof;
    boolean verified;

    Frame(
        Contracts.Reception reception,
        OwnerTimers.Registration registration,
        NativeAdmission.Receiver receiver,
        Map<String, Object> proof) {
      this.reception = reception;
      this.registration = registration;
      this.receiver = receiver;
      this.proof = proof;
    }
  }

  OwnerTimeIngress(Language language, BluePackage publication) {
    this.language = language;
    channelType = publication.export("Timer Channel");
    bindings = TimeBindings.verifiedBy(publication, this::verify);
  }

  TimeBindings bindings() {
    return bindings;
  }

  OwnerInput open(
      OwnerRecords records,
      Contracts.Execution processor,
      Contracts.Execution.State state,
      OwnerFrontiers.Claim claim,
      OwnerTimers.Registration registration,
      GraphTimelineIngress timelines) {
    if (invocation.get() != null) throw new IllegalStateException("Nested Time invocation");
    var head = records.frontiers.find(records.read, claim.owner());
    if (head == null || !claim.equals(head.claim()) || !registration.order().equals(claim.order()))
      throw new IllegalStateException("Time claim is not current");
    boolean direct =
        claim.sources().stream()
            .anyMatch(
                s ->
                    s.binding().kind() == OwnerFrontiers.Kind.TIMER
                        && s.binding().source().equals(registration.id()));
    var reception = state.reception();
    NativeAdmission.Receiver receiver = null;
    if (direct) {
      if (registration.status() != OwnerTimers.Status.PENDING
          || !registration.owner().equals(claim.owner()))
        throw new IllegalStateException("Time registration is not pending for this owner");
    } else if (!Set.of(
                OwnerTimers.Status.FIRED, OwnerTimers.Status.NO_MATCH, OwnerTimers.Status.FAILED)
            .contains(registration.status())
        || !causalCompletion(records, claim, registration)) {
      throw new IllegalStateException("Time original has no established causal source");
    }
    var matching =
        reception.channels().stream()
            .filter(c -> c.typeId().equals(channelType))
            .map(Contracts.ReceivingChannel::receiver)
            .filter(
                r ->
                    records.owner(r.member()).equals(registration.member())
                        && r.channel().equals(registration.channel())
                        && r.generation() == registration.generation())
            .toList();
    if (matching.size() > 1 || direct && matching.isEmpty())
      throw new IllegalStateException("Time receiving lifetime changed");
    if (!matching.isEmpty()) receiver = matching.getFirst();
    Language scoped =
        language.toBuilder()
            .provider(
                records.emissions.provider(records.account, registration.payload().catalogue()))
            .build();
    LanguageDocument original = scoped.read(new BlueId(registration.payload().message()));
    processor.withContent(original);
    var negative = timelines.timeInput(reception, original, claim.order());
    try {
      Map<String, Object> proof =
          Map.of(
              "registration",
              registration.id(),
              "claim",
              claim.token(),
              "snapshot",
              reception.snapshotIdentity(),
              "entry",
              registration.payload().entry());
      Frame frame = new Frame(reception, registration, receiver, proof);
      invocation.set(frame);
      AdmissionEvidence evidence = negative.request().evidence();
      if (receiver != null) evidence = evidence.with(channelType, proof);
      var request =
          new CanonicalGraphs.Request(
              "time-" + registration.id(),
              original,
              registration.timestamp(),
              evidence,
              Map.of(
                  "entry",
                  registration.payload().entry(),
                  "timestamp",
                  registration.timestamp(),
                  "registration",
                  registration.id()),
              (writes, checks) -> {});
      return new OwnerInput() {
        public CanonicalGraphs.Request request() {
          return request;
        }

        public boolean participating() {
          return direct;
        }

        public Optional<TimelineVerifier.Entry> entry() {
          return Optional.empty();
        }

        public RetainedTimeline retainedTimeline() {
          return null;
        }

        public void verified() {
          negative.verified();
          if (frame.receiver != null && !frame.verified)
            throw new IllegalStateException("Native Time admission was not verified");
        }

        public Map<String, Object> audit() {
          return Map.of("time", proof, "direct", direct, "timelines", negative.audit());
        }

        public void close() {
          invocation.remove();
          negative.close();
        }
      };
    } catch (RuntimeException | Error failure) {
      invocation.remove();
      negative.close();
      throw failure;
    }
  }

  private static boolean causalCompletion(
      OwnerRecords records, OwnerFrontiers.Claim claim, OwnerTimers.Registration registration) {
    for (var source : claim.sources()) {
      if (source.binding().kind() != OwnerFrontiers.Kind.OWNER || source.receipt() == null)
        continue;
      var completion = records.get("completion", source.receipt());
      if (completion != null
          && registration.payload().message().equals(completion.get("event"))
          && registration.timestamp() == Repository.number(completion.get("timestamp")))
        return true;
    }
    return false;
  }

  private TimeBindings.Firing verify(TimeBindings.Request request) {
    Frame frame = invocation.get();
    if (frame == null || frame.receiver == null) {
      request.reject("No claimed Time firing or retained causal invocation");
      throw new AssertionError();
    }
    var registration = frame.registration;
    var expected =
        frame.reception.channels().stream()
            .filter(c -> c.typeId().equals(channelType))
            .map(Contracts.ReceivingChannel::receiver)
            .toList();
    if (!request.snapshotIdentity().equals(frame.reception.snapshotIdentity())
        || !request.inputId().value().equals(registration.payload().message())
        || !request.eventId().value().equals(registration.payload().message())
        || request.timestamp() != registration.timestamp()
        || !request.evidence().equals(frame.proof)
        || request.receivers().size() != expected.size()
        || !new HashSet<>(request.receivers()).equals(new HashSet<>(expected)))
      request.reject("Different Time input, snapshot or receiving lifetime");
    frame.verified = true;
    return new TimeBindings.Firing(frame.receiver, new BlueId(registration.id()));
  }
}
