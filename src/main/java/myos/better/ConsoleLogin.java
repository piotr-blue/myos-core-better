package myos.better;

import java.util.Map;

/** Identity verification is independent of document execution and account selection. */
interface ConsoleLogin extends AutoCloseable {
  record User(String subject, String email, long expiresAt) {
    User(String subject, String email) {
      this(subject, email, Long.MAX_VALUE);
    }

    public User {
      if (subject == null || subject.isBlank() || email == null || email.isBlank())
        throw new IllegalArgumentException("Missing verified identity");
    }

    String account() {
      return "user-" + Json.hash(Json.bytes(subject));
    }
  }

  User verify(String token);

  Map<String, Object> action(String action, Map<String, Object> input);

  @Override
  void close();
}
