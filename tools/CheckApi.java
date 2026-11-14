import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.spi.ToolProvider;
import java.util.zip.ZipFile;

/** JDK-only guard for the public/protected signatures recorded at the compatibility baseline. */
class CheckApi {
  public static void main(String[] args) throws Exception {
    if (args.length < 2 || args.length > 3 || args.length == 3 && !args[2].equals("--record")) {
      throw new IllegalArgumentException("Expected JAR BASELINE [--record]");
    }
    Path baseline = Path.of(args[1]);
    Set<String> surface = surface(Path.of(args[0]));
    if (args.length == 3) {
      Files.createDirectories(baseline.toAbsolutePath().getParent());
      Files.write(baseline, surface, StandardCharsets.UTF_8);
      System.out.println("Recorded " + surface.size() + " API signatures");
      return;
    }
    verify(new TreeSet<>(Files.readAllLines(baseline, StandardCharsets.UTF_8)), surface);
    System.out.println("API baseline preserved (" + surface.size() + " current signatures)");
  }

  private record Classes(Set<String> names, Map<String, String> enclosing) {}

  private static Classes classes(Path archive) throws Exception {
    var names = new TreeSet<String>();
    var enclosing = new HashMap<String, String>();
    try (var jar = new ZipFile(archive.toFile())) {
      jar.stream()
          .map(entry -> entry.getName())
          .filter(name -> name.endsWith(".class"))
          .filter(name -> !name.startsWith("META-INF/") && !name.endsWith("module-info.class"))
          .map(name -> name.substring(0, name.length() - 6).replace('/', '.'))
          .forEach(names::add);
      // '$' is legal in a top-level Java identifier. Only class-file metadata establishes
      // nesting; guessing from names silently hides otherwise public types.
      for (String name : names) {
        try (var input = jar.getInputStream(jar.getEntry(name.replace('.', '/') + ".class"))) {
          var model = ClassFile.of().parse(input.readAllBytes());
          model
              .findAttribute(Attributes.innerClasses())
              .ifPresent(
                  attribute -> {
                    for (var inner : attribute.classes()) {
                      if (inner.innerClass().asInternalName().replace('/', '.').equals(name)) {
                        inner
                            .outerClass()
                            .ifPresent(
                                outer ->
                                    enclosing.put(name, outer.asInternalName().replace('/', '.')));
                      }
                    }
                  });
        }
      }
    }
    return new Classes(names, enclosing);
  }

  private static Map<String, List<String>> declarations(Path archive, Set<String> names) {
    if (names.isEmpty()) {
      return Map.of();
    }
    var options =
        new ArrayList<>(
            List.of("-classpath", archive.toString(), "-protected", "-constants", "-s"));
    options.addAll(names);
    var output = new StringWriter();
    int status =
        ToolProvider.findFirst("javap")
            .orElseThrow()
            .run(
                new PrintWriter(output),
                new PrintWriter(System.err),
                options.toArray(String[]::new));
    if (status != 0) {
      throw new IllegalStateException("javap failed: " + status);
    }
    Map<String, List<String>> classes = new TreeMap<>();
    String selected = null;
    var iterator = names.iterator();
    for (String line : output.toString().split("\\R")) {
      if (line.endsWith("{")) {
        selected = iterator.next();
        classes.put(selected, new ArrayList<>());
        classes.get(selected).add(line.trim());
      } else if (selected != null && !line.isBlank() && !line.equals("}")) {
        classes.get(selected).add(line.trim());
      }
      if (line.equals("}")) {
        selected = null;
      }
    }
    if (iterator.hasNext() || selected != null) {
      throw new IllegalStateException("Incomplete javap declarations");
    }
    return classes;
  }

  private static Set<String> surface(Path archive) throws Exception {
    Classes catalogue = classes(archive);
    Map<String, List<String>> declarations = declarations(archive, catalogue.names());
    var surface = new TreeSet<String>();
    declarations.forEach(
        (name, lines) -> {
          if (!visible(name, catalogue.enclosing(), declarations)) {
            return;
          }
          String member = "";
          for (String line : lines) {
            if (line.startsWith("descriptor:")) {
              surface.add(name + " :: " + member + " :: " + line);
            } else {
              member = line;
              surface.add(name + " :: " + line);
            }
          }
        });
    return surface;
  }

  private static boolean visible(
      String name, Map<String, String> enclosing, Map<String, List<String>> declarations) {
    for (String selected = name; selected != null; selected = enclosing.get(selected)) {
      List<String> lines = declarations.get(selected);
      if (lines == null
          || !(lines.getFirst().startsWith("public ")
              || lines.getFirst().startsWith("protected "))) {
        return false;
      }
    }
    return true;
  }

  private static void verify(Set<String> original, Set<String> surface) {
    var removed = new TreeSet<>(original);
    removed.removeAll(surface);
    var obligations = new TreeSet<String>();
    Set<String> originalClasses = new TreeSet<>();
    original.stream()
        .filter(line -> line.endsWith("{"))
        .map(CheckApi::declaringClass)
        .forEach(originalClasses::add);
    for (String line : surface) {
      // New types may declare abstract methods, but existing implementers/subclasses must
      // not acquire new obligations just because every previous signature is still present.
      if (!original.contains(line)
          && !line.contains(" :: descriptor:")
          && line.contains(" abstract ")
          && line.endsWith(";")
          && originalClasses.contains(declaringClass(line))) {
        obligations.add(line);
      }
    }
    if (!removed.isEmpty() || !obligations.isEmpty()) {
      removed.forEach(line -> System.err.println("Changed or removed API: " + line));
      obligations.forEach(line -> System.err.println("Added abstract obligation: " + line));
      throw new IllegalStateException(
          "Compatibility baseline changed; review versioning before updating it");
    }
  }

  private static String declaringClass(String line) {
    return line.substring(0, line.indexOf(" :: "));
  }
}
