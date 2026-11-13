package myos.better;

import java.util.HashSet;
import java.util.Map;

/** Read-only inventory of one timeline's durable routing metadata; never opens document bodies. */
public final class FrontierInspection {
  private FrontierInspection() {}

  public static void main(String[] args) {
    if (args.length != 3) {
      throw new IllegalArgumentException("Expected table, account, timeline");
    }
    try (var store = new DynamoStore(args[0], "eu-west-1")) {
      var transaction = new OwnerMetadata(store, args[1]).begin();
      String prefix = "timeline-consumer\0" + args[2] + "\0";
      String after = null;
      int bindings = 0;
      var owners = new HashSet<String>();
      while (true) {
        var page = transaction.page(prefix, after, 128);
        for (var row : page) {
          var consumer = Json.object(row.value().getBytes(java.nio.charset.StandardCharsets.UTF_8));
          owners.add((String) consumer.get("owner"));
          bindings++;
        }
        if (page.size() < 128) {
          break;
        }
        after = page.getLast().key();
      }
      System.out.println(
          JsonText.of(Map.of("timeline", args[2], "bindings", bindings, "owners", owners.size())));
    }
  }
}
