package myos.better;

/** Stable host addresses for native graph members; never written into Blue content. */
final class GraphMemberIds {
  private GraphMemberIds() {}

  static String suffix(String member) {
    return Json.hash(Json.bytes(member)).substring(0, 32);
  }

  static String address(String graph, String root, String member) {
    if (OwnerGraphs.handles(graph)) {
      return OwnerGraphs.address(member);
    }
    return member.equals(root) ? graph : graph + "." + suffix(member);
  }
}
