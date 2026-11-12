package myos.better;

import blue.AdmissionEvidence;
import blue.BlueId;
import java.util.LinkedHashMap;
import java.util.Map;

/** Host-derived scope and captured evidence for an explicitly admitted external command. */
record NativeInput(String runtimeCommand, Object event, AdmissionEvidence evidence, Object audit) {
  static final String FORMAT = "myos.native-input/1";
  private static final int MAX_BYTES = 128 * 1024;

  private static final ThreadLocal<String> ADMISSION = new ThreadLocal<>();

  static String admissionScope() {
    String scope = ADMISSION.get();
    if (scope == null) {
      throw new IllegalStateException("No host admission in progress");
    }
    return scope;
  }

  static Scope admission(String scope) {
    String previous = ADMISSION.get();
    ADMISSION.set(scope);
    return () -> {
      if (previous == null) {
        ADMISSION.remove();
      } else {
        ADMISSION.set(previous);
      }
    };
  }

  @FunctionalInterface
  interface Scope extends AutoCloseable {
    @Override
    void close();
  }

  static String namespace(String value) {
    if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,255}")) {
      throw new IllegalArgumentException("A stable native admission namespace is required");
    }
    return value;
  }

  static String scopedCommand(String namespace, String account, String document, String command) {
    Map<String, Object> scope =
        Map.of(
            "format", FORMAT,
            "namespace", namespace(namespace),
            "account", Repository.identifier(account),
            "document", Repository.identifier(document),
            "command", Repository.identifier(command));
    return FORMAT + ":" + Json.hash(Json.bytes(scope));
  }

  static NativeInput capture(
      String namespace,
      String account,
      String document,
      String command,
      Object event,
      long timestamp,
      Object rawEvidence) {
    String scoped = scopedCommand(namespace, account, document, command);
    if (event == null) {
      throw new IllegalArgumentException("Event is required");
    }
    if (!(rawEvidence instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException(
          "Native evidence must be an object keyed by channel blueId");
    }
    Map<BlueId, Object> proofs = new LinkedHashMap<>();
    for (var entry : map.entrySet()) {
      if (!(entry.getKey() instanceof String type)) {
        throw new IllegalArgumentException("Native evidence keys must be channel blueIds");
      }
      if (entry.getValue() == null) {
        throw new IllegalArgumentException("Null admission proof");
      }
      proofs.put(new BlueId(type), entry.getValue());
    }
    AdmissionEvidence evidence = AdmissionEvidence.copyOf(proofs);
    Map<String, Object> durableProofs = new LinkedHashMap<>();
    evidence.asMap().forEach((type, proof) -> durableProofs.put(type.value(), proof));
    Map<String, Object> audit =
        Map.of(
            "format", FORMAT,
            "namespace", namespace,
            "account", account,
            "document", document,
            "command", command,
            "event", event,
            "timestamp", timestamp,
            "evidence", durableProofs);
    byte[] bytes = Json.bytes(audit);
    if (bytes.length > MAX_BYTES) {
      throw new IllegalArgumentException("Native input exceeds 128 KiB");
    }
    Map<String, Object> captured = Json.object(bytes);
    return new NativeInput(scoped, captured.get("event"), evidence, captured);
  }
}
