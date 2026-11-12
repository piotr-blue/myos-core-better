package myos.better;

import blue.Contracts;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Propagation metadata for a native, acyclic one-member birth; never infers topology from patches.
 */
final class OwnerBirth {
  private OwnerBirth() {}

  static Map<String, Object> project(
      Contracts.Execution execution,
      Contracts.Execution.Outcome outcome,
      String page,
      String eventContent) {
    var proof = execution.singleBirthCompletion(outcome);
    if (proof.isEmpty()) return Map.of();
    var after = outcome.after();
    var members = new HashMap<String, Object>();
    var admissions = new HashMap<String, Object>();
    var views = new HashMap<String, Object>();
    for (String key : List.of("after", "leaf")) {
      var checkpoint = RuntimeConfiguration.object(proof.get(key), "owner checkpoint");
      var member = (String) checkpoint.get("root");
      var local = after.rooted(member);
      members.put(member, checkpoint.get("member"));
      var admission = local.document().checkpoint();
      admissions.put(
          member,
          Map.of(
              "record",
              admission.record(),
              "identity",
              admission.identity(),
              "native",
              Map.of(
                  "environment",
                  checkpoint.get("environment"),
                  "member",
                  checkpoint.get("preparation"))));
      views.put(
          member,
          GraphOperationCatalogue.describe(
              local.blueId(),
              local.operations(member),
              GraphOperationCatalogue.channels(local.reception(), member)));
    }
    var roots = new TreeSet<String>();
    members
        .values()
        .forEach(
            row -> roots.add((String) RuntimeConfiguration.object(row, "member").get("blueId")));
    outcome
        .transition()
        .emissions()
        .forEach(emission -> roots.add(emission.event().blueId().value()));
    return Map.of(
        "native",
        proof,
        "page",
        page,
        "members",
        members,
        "contentRoots",
        List.copyOf(roots),
        "admissions",
        admissions,
        "views",
        views,
        "birth",
        proof.get("birth"),
        "eventContent",
        eventContent);
  }
}
