import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;

/** Compiles independent client-facing fixtures; no library classes or external tools are needed. */
class CheckApiTest {
  private static final String API =
      """
      package fixture;
      public class Api {
        public static final int LIMIT = 7;
        public Api() {}
        protected String label() { return "label"; }
        public java.util.List<String> names() { return java.util.List.of(); }
        public static int size() { return 1; }
        public static final class Nested { public int value() { return 1; } }
        private static final class Hidden { public int secret() { return 1; } }
      }
      """;
  private static final String HOOK = "package fixture; public interface Hook { void run(); }";
  private static final Map<String, String> SOURCES =
      Map.of(
          "Api.java",
          API,
          "Hook.java",
          HOOK,
          "Internal.java",
          "package fixture; class Internal { public static class Nested { public void hidden() {} }"
              + " }",
          "Standalone$Utility.java",
          "package fixture; public class Standalone$Utility { public void visible() {} }");

  private final Path root;
  private final Path baseline;
  private int count;
  private final List<String> failures = new ArrayList<>();

  private CheckApiTest(Path root) throws Exception {
    this.root = root;
    baseline = root.resolve("baseline.txt");
    CheckApi.main(
        new String[] {compile("baseline", SOURCES).toString(), baseline.toString(), "--record"});
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 1) {
      throw new IllegalArgumentException("Expected output directory");
    }
    var suite =
        new CheckApiTest(
            Files.createTempDirectory(Files.createDirectories(Path.of(args[0])), "fixtures-"));
    suite.run();
    if (!suite.failures.isEmpty()) {
      throw new AssertionError(String.join("\n", suite.failures));
    }
    System.out.println("API checker passed " + suite.count + " independent fixture checks");
  }

  private void run() throws Exception {
    check("unchanged", SOURCES, true);
    api("implementation-change", API.replace("return 1;", "return 2;"), true);
    api(
        "concrete-addition",
        API.replace("public Api() {}", "public Api() {} public void added() {}"),
        true);
    check(
        "default-addition",
        replace("Hook.java", HOOK.replace("void run();", "void run(); default void added() {}")),
        true);
    var added = new LinkedHashMap<>(SOURCES);
    added.put(
        "NewHook.java", "package fixture; public interface NewHook { void newObligation(); }");
    check("new-abstract-type", added, true);
    check(
        "hidden-implementation-change",
        replace("Internal.java", "package fixture; class Internal {}"),
        true);
    api(
        "private-nested-change",
        API.replace("public int secret() { return 1; }", "public void replaced() {}"),
        true);
    api("method-removal", API.replace("public static int size() { return 1; }", ""), false);
    api(
        "protected-removal",
        API.replace("protected String label()", "private String label()"),
        false);
    api("generic-change", API.replace("List<String>", "List<Integer>"), false);
    api("constant-change", API.replace("LIMIT = 7", "LIMIT = 8"), false);
    api("static-change", API.replace("public static int size()", "public int size()"), false);
    api(
        "return-change",
        API.replace("public static int size()", "public static long size()"),
        false);
    api("final-class", API.replace("public class Api", "public final class Api"), false);
    api(
        "nested-removal",
        API.replace("public static final class Nested { public int value() { return 1; } }", ""),
        false);
    check(
        "new-abstract-obligation",
        replace("Hook.java", HOOK.replace("void run();", "void run(); void added();")),
        false);
    check(
        "dollar-in-top-level-name",
        replace("Standalone$Utility.java", "package fixture; public class Standalone$Utility {}"),
        false);
    count++;
    try {
      CheckApi.main(new String[] {"unused.jar", baseline.toString(), "--typo"});
      failures.add("Unknown CLI option was silently accepted");
    } catch (IllegalArgumentException expected) {
      // Reject a misspelled recording option before opening either file.
    } catch (Exception wrong) {
      failures.add("Unknown CLI option opened the input: " + wrong);
    }
  }

  private void api(String name, String source, boolean compatible) throws Exception {
    check(name, replace("Api.java", source), compatible);
  }

  private static Map<String, String> replace(String name, String source) {
    var sources = new LinkedHashMap<>(SOURCES);
    sources.put(name, source);
    return sources;
  }

  private void check(String name, Map<String, String> sources, boolean compatible)
      throws Exception {
    Path jar = compile(name, sources);
    count++;
    var output = new ByteArrayOutputStream();
    PrintStream priorOut = System.out, priorError = System.err;
    try (var capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
      System.setOut(capture);
      System.setErr(capture);
      try {
        CheckApi.main(new String[] {jar.toString(), baseline.toString()});
        if (!compatible) {
          failures.add(name + ": accepted incompatible API");
        }
      } catch (IllegalStateException rejected) {
        if (compatible) {
          failures.add(name + ": rejected compatible API: " + rejected.getMessage());
        }
      }
    } finally {
      System.setOut(priorOut);
      System.setErr(priorError);
      Files.writeString(
          jar.resolveSibling("diagnostics.txt"), output.toString(StandardCharsets.UTF_8));
    }
  }

  private Path compile(String name, Map<String, String> sources) throws Exception {
    Path directory = Files.createDirectories(root.resolve(name));
    Path classes = Files.createDirectories(directory.resolve("classes"));
    var options = new ArrayList<>(List.of("-d", classes.toString(), "-Xlint:all", "-Werror"));
    for (var source : sources.entrySet()) {
      Path file = directory.resolve(source.getKey());
      Files.writeString(file, source.getValue(), StandardCharsets.UTF_8);
      options.add(file.toString());
    }
    var errors = new ByteArrayOutputStream();
    int status =
        ToolProvider.getSystemJavaCompiler()
            .run(null, errors, errors, options.toArray(String[]::new));
    if (status != 0) {
      throw new AssertionError(
          "Fixture compilation failed: " + errors.toString(StandardCharsets.UTF_8));
    }
    Path archive = directory.resolve("fixture.jar");
    try (var output = new JarOutputStream(Files.newOutputStream(archive));
        var files = Files.walk(classes)) {
      for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
        output.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
        Files.copy(file, output);
        output.closeEntry();
      }
    }
    return archive;
  }
}
