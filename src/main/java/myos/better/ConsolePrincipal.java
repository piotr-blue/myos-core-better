package myos.better;

/**
 * Verified request identity; API keys never acquire interactive credential-management authority.
 */
record ConsolePrincipal(
    String account,
    String email,
    boolean user,
    long expiresAt,
    ApiKeys.Identity credential,
    CoreImpersonations.Identity impersonation) {
  ConsolePrincipal(
      String account, String email, boolean user, long expiresAt, ApiKeys.Identity credential) {
    this(account, email, user, expiresAt, credential, null);
  }

  ConsolePrincipal(String account, String email, boolean user, long expiresAt) {
    this(account, email, user, expiresAt, null);
  }

  static ConsolePrincipal user(ConsoleLogin.User user) {
    return new ConsolePrincipal(user.account(), user.email(), true, user.expiresAt());
  }

  static ConsolePrincipal key(ApiKeys.Identity key) {
    return new ConsolePrincipal(key.account(), "", false, Long.MAX_VALUE, key);
  }

  String credentialId() {
    return credential == null ? null : credential.id();
  }

  String subject() {
    return impersonation != null
        ? impersonation.userId()
        : credential != null && credential.owner() != null ? credential.owner() : account;
  }

  void requireUser() {
    if (!user) throw new ConsoleProblem(403, "USER_REQUIRED", "Sign in to manage API keys.");
  }
}
