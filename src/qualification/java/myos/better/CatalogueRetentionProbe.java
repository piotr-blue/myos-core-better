package myos.better;

import blue.BlueId;
import blue.BluePackage;
import blue.Language;
import blue.PackageLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Loads and mounts the actual archive set, without server workers, stores or credentials. */
public final class CatalogueRetentionProbe {
  public static void main(String[] args) throws Exception {
    if (args.length != 0) {
      throw new IllegalArgumentException("The probe uses the checked-in deployed catalogue");
    }
    var path = Path.of("examples/clause-tutorial/runtime-console-deployed.json");
    var config = Json.object(Files.readAllBytes(path));
    var loader = PackageLoader.create();
    var language = Language.newBuilder();
    var publications = new LinkedHashMap<BlueId, BluePackage>();
    var mounts = new LinkedHashMap<String, BluePackage>();
    long start = System.nanoTime();
    int index = 0;
    try {
      for (Object raw : (List<?>) config.get("packages")) {
        var row = (Map<?, ?>) raw;
        var loaded =
            loader.open(
                path.getParent().resolve((String) row.get("archive")),
                new BlueId((String) row.get("blueId")));
        publications.put(loaded.blueId(), loaded);
        for (var entry : ((Map<?, ?>) row.get("mounts")).entrySet()) {
          var mounted = loaded;
          for (var step : (List<?>) entry.getValue()) {
            mounted = mounted.imported((String) step);
          }
          language.mount((String) entry.getKey(), mounted);
          mounts.put((String) entry.getKey(), mounted);
          publications.put(mounted.blueId(), mounted);
        }
        index++;
      }
      var publicPackages = new PublicPackages(publications.values(), mounts);
      var configured = language.build();
      loader.clearCache();
      System.gc();
      long retained = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
      long maximum = 512L * 1024 * 1024;
      if (retained > maximum) {
        throw new AssertionError("Retained catalogue heap exceeds 512 MiB: " + retained);
      }
      System.out.println(
          JsonText.of(
              Map.of(
                  "passed",
                  true,
                  "packages",
                  index,
                  "records",
                  publicPackages.records().size(),
                  "mounts",
                  mounts.size(),
                  "loadMillis",
                  (System.nanoTime() - start) / 1_000_000.0,
                  "retainedHeapBytes",
                  retained,
                  "retainedHeapLimitBytes",
                  maximum,
                  "runtimeManifestSha256",
                  Json.hash(Files.readAllBytes(path)),
                  "maximumHeapBytes",
                  Runtime.getRuntime().maxMemory())));
      java.lang.ref.Reference.reachabilityFence(publicPackages);
      java.lang.ref.Reference.reachabilityFence(configured);
      java.lang.ref.Reference.reachabilityFence(publications);
      java.lang.ref.Reference.reachabilityFence(mounts);
    } catch (OutOfMemoryError failure) {
      System.err.println("Catalogue failure after " + index + " packages");
      failure.printStackTrace();
      throw failure;
    }
  }
}
