import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.spi.ToolProvider;
import java.util.zip.ZipFile;

/** JDK-only guard for the public/protected signatures recorded at the compatibility baseline. */
class CheckApi {
  public static void main(String[] args) throws Exception {
    if (args.length < 2 || args.length > 3) {
      throw new IllegalArgumentException("Expected JAR BASELINE [--record]");
    }
    Path baseline = Path.of(args[1]);
    var names = new TreeSet<String>();
    try (var jar = new ZipFile(args[0])) {
      jar.stream().map(entry -> entry.getName()).filter(name -> name.endsWith(".class"))
          .filter(name -> !name.startsWith("META-INF/") && !name.endsWith("module-info.class"))
          .map(name -> name.substring(0, name.length() - 6).replace('/', '.')).forEach(names::add);
    }
    var options = new ArrayList<>(java.util.List.of("-classpath", args[0], "-protected", "-constants", "-s"));
    options.addAll(names);
    var output = new StringWriter();
    int status = ToolProvider.findFirst("javap").orElseThrow().run(new PrintWriter(output), new PrintWriter(System.err), options.toArray(String[]::new));
    if (status != 0) {
      throw new IllegalStateException("javap failed: " + status);
    }
    Map<String, java.util.List<String>> classes = new TreeMap<>();
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
    var surface = new TreeSet<String>();
    classes.forEach((name, lines) -> {
      String enclosing = name;
      while (true) {
        var declaration = classes.get(enclosing);
        if (declaration == null || !(declaration.getFirst().startsWith("public ") || declaration.getFirst().startsWith("protected "))) {
          return;
        }
        int dollar = enclosing.lastIndexOf('$');
        if (dollar < 0) {
          break;
        }
        enclosing = enclosing.substring(0, dollar);
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
    if (args.length == 3 && args[2].equals("--record")) {
      Files.createDirectories(baseline.toAbsolutePath().getParent());
      Files.write(baseline, surface, StandardCharsets.UTF_8);
      System.out.println("Recorded " + surface.size() + " API signatures");
      return;
    }
    var removed = new TreeSet<>(Files.readAllLines(baseline, StandardCharsets.UTF_8));
    removed.removeAll(surface);
    if (!removed.isEmpty()) {
      removed.forEach(line -> System.err.println("Changed or removed API: " + line));
      throw new IllegalStateException("Compatibility baseline changed; review versioning before updating it");
    }
    System.out.println("API baseline preserved (" + surface.size() + " current signatures)");
  }
}
