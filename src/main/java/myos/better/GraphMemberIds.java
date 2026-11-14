package myos.better;

/** Stable host addresses for native graph members; never written into Blue content. */
final class GraphMemberIds {
  private GraphMemberIds() {}

  static boolean isOwnerAddress(String id) {
    return id.startsWith("graph-fast-");
  }

  /** Retains existing owner addresses; otherwise hashes the JSON-encoded member key. */
  static String address(String member) {
    return isOwnerAddress(member)
        ? member
        : "graph-fast-" + Json.hash(Json.bytes(member)).substring(0, 48);
  }

  static String suffix(String member) {
    return Json.hash(Json.bytes(member)).substring(0, 32);
  }

  static String address(String graph, String root, String member) {
    if (isOwnerAddress(graph)) {
      return address(member);
    }
    return member.equals(root) ? graph : graph + "." + suffix(member);
  }
}
