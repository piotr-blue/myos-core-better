package myos.better;

import blue.AdmissionEvidence;
import blue.BlueException;
import blue.BlueId;
import blue.BluePackage;
import blue.Contracts;
import blue.GasSchedule;
import blue.Language;
import blue.LanguageContent;
import blue.LanguageDocument;
import blue.LanguageProvider;
import blue.NativeChannel;
import blue.NativeStep;
import blue.ProjectionLimits;
import blue.TimelineChannels;
import blue.TimelineVerifier;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Service adapter to the one canonical Contracts engine. No local execution edition, detached child
 * execution or command-derived event identity is implemented here. Old experimental checkpoints
 * require explicit replay; they are never relabelled as canonical snapshots.
 */
final class CanonicalRuntime {
  static final String EDITION = Contracts.create().rulesId().value();
  // Recognized only to reject old storage/configuration, never accepted by the builder.
  static final String FRESH_SUBTREES_EDITION = "blue.document-local/2-preview";
  static final String HOST_LINKS_EDITION = "blue.document-local/3-preview";

  private final ThreadLocal<LanguageProvider> content = new ThreadLocal<>();
  private final Contracts contracts;
  private final Language language;

  private CanonicalRuntime(Builder builder) {
    language =
        builder.language.toBuilder()
            .provider(
                id -> {
                  LanguageProvider current = content.get();
                  return current == null ? Optional.empty() : current.find(id);
                })
            .build();
    contracts = builder.delegate.language(language).build();
  }

  static CanonicalRuntime create() {
    return newBuilder().build();
  }

  static Builder newBuilder() {
    return new Builder();
  }

  String edition() {
    return EDITION;
  }

  Language language() {
    return language;
  }

  static final class Builder {
    private final Contracts.Builder delegate = Contracts.newBuilder();
    private Language language = Language.create();

    Builder edition(String edition) {
      if (!EDITION.equals(edition)) {
        throw new IllegalArgumentException(
            "Experimental execution edition is retired; replay under canonical Contracts: "
                + edition);
      }
      return this;
    }

    Builder language(Language value) {
      language = Objects.requireNonNull(value);
      return this;
    }

    Builder enableDocumentDispatch() {
      delegate.enableDocumentDispatch();
      return this;
    }

    Builder timelineChannels(TimelineChannels value) {
      delegate.timelineChannels(value);
      return this;
    }

    Builder nativeStep(NativeStep value) {
      delegate.nativeStep(value);
      return this;
    }

    Builder nativeChannel(NativeChannel value) {
      delegate.nativeChannel(value);
      return this;
    }

    Builder require(BluePackage value) {
      delegate.require(value);
      return this;
    }

    Builder gasSchedule(GasSchedule schedule) {
      delegate.gasSchedule(schedule);
      return this;
    }

    Builder gasLimit(long value) {
      delegate.gasLimit(value);
      return this;
    }

    CanonicalRuntime build() {
      return new CanonicalRuntime(this);
    }
  }

  Session openYaml(String source) {
    return new Session(contracts.openYaml(source));
  }

  Session open(Object source) {
    return source instanceof LanguageDocument document
        ? new Session(contracts.open(document))
        : source instanceof String text
            ? openYaml(text)
            : new Session(contracts.openJson(JsonText.of(source)));
  }

  Session restore(Map<String, Object> checkpoint) {
    if (!EDITION.equals(checkpoint.get("edition"))) {
      throw retired();
    }
    Map<String, Object> nativeSnapshot = new LinkedHashMap<>(checkpoint);
    nativeSnapshot.remove("edition");
    return new Session(contracts.restore(nativeSnapshot, (String) checkpoint.get("identity")));
  }

  Session restore(Prepared prepared) {
    return restore(prepared.checkpoint());
  }

  Session migrate(Map<String, Object> checkpoint) {
    throw retired();
  }

  private static IllegalArgumentException retired() {
    return new IllegalArgumentException(
        "Experimental document-local history requires explicit replay; canonical processing cannot"
            + " reinterpret it");
  }

  static IllegalArgumentException detachedDelivery() {
    return new IllegalArgumentException(
        "Detached event injection is retired; select the original through canonical owner"
            + " processing");
  }

  record Attachment(
      String path, LanguageDocument document, Optional<Prepared> prepared, BlueId initialBlueId) {}

  record Prepared(
      Map<String, Object> checkpoint, List<Object> events, List<Attachment> attachments) {}

  record AdmissionResult(Update update, boolean verified) {}

  record Update(
      String status,
      long gas,
      List<Object> events,
      List<Attachment> attachments,
      List<String> replacedPaths,
      Object diagnostic) {
    static Update unchanged() {
      return new Update("APPLIED", 0, List.of(), List.of(), List.of(), null);
    }

    boolean applied() {
      return status.equals("APPLIED");
    }

    Update requireApplied() {
      if (!applied()) {
        throw new BlueException(status, String.valueOf(diagnostic));
      }
      return this;
    }
  }

  final class Session implements AutoCloseable {
    private final Contracts.Session delegate;

    private Session(Contracts.Session delegate) {
      this.delegate = delegate;
    }

    Update initialize() {
      return result(delegate.initialize());
    }

    boolean requiresNativeAdmission() {
      return !reception().channels().isEmpty();
    }

    Contracts.Reception reception() {
      return delegate.reception();
    }

    List<Contracts.Operation> operations() {
      return delegate.operations(delegate.rootMember());
    }

    Optional<Contracts.OperationRequest> operationRequest(LanguageContent message) {
      return delegate.operationRequest(message);
    }

    Optional<Contracts.OperationRequest> operationRequest(
        LanguageContent message, LanguageDocument... values) {
      return contracts.operationRequest(message, values);
    }

    Optional<Contracts.OperationRequest> operationRequest(
        TimelineVerifier.Entry entry, LanguageContent message) {
      return delegate.operationRequest(entry, message);
    }

    Update process(Object event, long timestamp) {
      return result(delegate.process(event, timestamp));
    }

    Update call(String channel, String operation, Object request, long timestamp) {
      return result(delegate.call(channel, operation, request, timestamp));
    }

    AdmissionResult admit(
        String command,
        Object event,
        long timestamp,
        LanguageProvider reader,
        AdmissionEvidence evidence) {
      if (!requiresNativeAdmission()) {
        return missingVerifier();
      }
      var result =
          withAdmission(command, reader, () -> delegate.process(event, timestamp, evidence));
      return new AdmissionResult(result(result), result.admissionVerified());
    }

    AdmissionResult admit(
        String command,
        TimelineVerifier.Entry entry,
        LanguageProvider reader,
        AdmissionEvidence evidence) {
      if (!requiresNativeAdmission()) {
        return missingVerifier();
      }
      var result = withAdmission(command, reader, () -> delegate.process(entry, evidence));
      return new AdmissionResult(result(result), result.admissionVerified());
    }

    @SuppressWarnings("try")
    private Contracts.Transition withAdmission(
        String command,
        LanguageProvider reader,
        java.util.function.Supplier<Contracts.Transition> action) {
      LanguageProvider previous = content.get();
      content.set(reader);
      try (var scope = NativeInput.admission(command)) {
        return action.get();
      } finally {
        if (previous == null) {
          content.remove();
        } else {
          content.set(previous);
        }
      }
    }

    private AdmissionResult missingVerifier() {
      return new AdmissionResult(
          new Update(
              "FAILED",
              0,
              List.of(),
              List.of(),
              List.of(),
              Map.of("code", "MISSING_ADMISSION_VERIFIER")),
          false);
    }

    Object read(String pointer) {
      return delegate.read(pointer);
    }

    Object read(String pointer, ProjectionLimits limits) {
      return delegate.read(pointer, limits);
    }

    LanguageDocument document() {
      return delegate.document();
    }

    Map<String, Object> checkpoint() {
      Map<String, Object> result = new LinkedHashMap<>(delegate.snapshot());
      result.put("edition", EDITION);
      return Collections.unmodifiableMap(result);
    }

    @Override
    public void close() {
      delegate.close();
    }

    private Update result(Contracts.Transition result) {
      return new Update(
          result.status(),
          result.gas(),
          result.events(),
          List.of(),
          List.of(),
          result.observations().get("diagnostic"));
    }
  }
}
