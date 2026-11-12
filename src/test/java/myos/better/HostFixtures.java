package myos.better;

/** Shared deterministic host setup; test classes do not depend on another test class. */
final class HostFixtures {
  private HostFixtures() {}

  static void drain(Core core) {
    for (int i = 0; i < 30; i++) if (core.drain(100) == 0) return;
    throw new IllegalStateException("Propagation did not quiesce");
  }
}
