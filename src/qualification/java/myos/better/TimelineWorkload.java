package myos.better;

/** Shared authored workload for direct and ordinary HTTP Timeline qualifications. */
final class TimelineWorkload {
  private TimelineWorkload() {}

  static String agreement(QualificationTimeline active, QualificationTimeline quiet) {
    String child =
        "count: 0\ncontracts:\n  input:\n    type: Coordination/Public Timeline Channel\n"
            + "    provider: {blueId: '"
            + active.provider.blueId()
            + "'}\n    url: "
            + active.url
            + "\n"
            + "  quiet:\n    type: Coordination/Public Timeline Channel\n"
            + "    provider: {blueId: '"
            + quiet.provider.blueId()
            + "'}\n    url: "
            + quiet.url
            + "\n"
            + workflow();
    return parent(child);
  }

  static String parent(String child) {
    return "count: 0\nchild:\n"
        + child.indent(2)
        + """
        contracts:
          embedded: {type: Contracts/Process Embedded, paths: [/child]}
          events: {type: Contracts/Embedded Node Channel, event: {kind: Counted}}
          react:
            type: Contracts/Sequential Workflow
            channel: events
            steps:
              - type: Contracts/Update Document
                changeset: [{op: replace, path: /count, val: {$add: [{$document: /count}, {$event: /event/amount}]}}]
        """;
  }

  static String workflow() {
    return """
      countInput:
        type: Contracts/Sequential Workflow
        channel: input
        steps:
          - type: Contracts/Update Document
            changeset: [{op: replace, path: /count, val: {$add: [{$document: /count}, {$event: /message/amount}]}}]
          - type: Contracts/Trigger Event
            event: {kind: Counted, amount: {$event: /message/amount}}
    """;
  }
}
