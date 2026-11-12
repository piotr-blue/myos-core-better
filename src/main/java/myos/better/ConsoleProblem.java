package myos.better;

/** A bounded, public API diagnostic; internal exceptions are never returned as stack traces. */
final class ConsoleProblem extends RuntimeException {
  private static final long serialVersionUID = 1L;
  final int status;
  final String code;

  ConsoleProblem(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}
