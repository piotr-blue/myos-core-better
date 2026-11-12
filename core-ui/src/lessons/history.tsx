import { Download } from "lucide-react";
import type { LessonRun } from "./model";
export function LessonHistory({ run }: { run: LessonRun }) {
  const report = run
    ? {
        example: run.exampleId,
        root: run.root,
        selection: run.selection,
        results: run.results,
        rejectedAttempts: run.attempts || [],
        pending: run.pending
          ? {
              sessionId: run.pending.sessionId,
              entryId: run.pending.entryId,
              admissionStatus: run.pending.admission?.status,
            }
          : undefined,
        scope:
          "Observed browser request, admission and stable-outcome latency. Not isolated BEX execution time.",
      }
    : undefined;
  function download() {
    if (!run) return;
    const url = URL.createObjectURL(
      new Blob([JSON.stringify(report, null, 2)], { type: "application/json" }),
    );
    const a = document.createElement("a");
    a.href = url;
    a.download = `${run.exampleId}-timings.json`;
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 0);
  }

  return (
    <section className="lesson-history">
      <header>
        <h2>Completed actions</h2>
        <button
          className="secondary"
          onClick={download}
          disabled={!run.results.length}
        >
          <Download size={16} /> Download timings
        </button>
      </header>
      <p>
        Times include the browser request, Core admission and waiting for the
        observed outcome. They are not isolated execution-engine timings.
      </p>
      <div className="lesson-table">
        <table>
          <thead>
            <tr>
              <th>Action</th>
              <th>Outcome</th>
              <th>Request</th>
              <th>Admission wait</th>
              <th>Processing wait</th>
              <th>Total</th>
            </tr>
          </thead>
          <tbody>
            {[...run.results, ...(run.attempts || [])].map((r, i) => (
              <tr key={i}>
                <td>
                  {r.action}
                  {r.interrupted ? " (resumed; includes interruption)" : ""}
                </td>
                <td>{r.status}</td>
                {[r.submissionMs, r.admissionMs, r.processingMs, r.totalMs].map(
                  (ms, j) => (
                    <td key={j}>{(ms / 1000).toFixed(2)} s</td>
                  ),
                )}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <details>
        <summary>Exact packages used by this run</summary>
        {run.selection ? <ul>{run.selection.packages.map(id => <li key={id}><code>{id}</code></li>)}</ul> :
          <p>This earlier run used the original package selection from its example.</p>}
      </details>
      <details>
        <summary>Timing data (JSON)</summary>
        <pre className="lesson-timing-json">
          {JSON.stringify(report, null, 2)}
        </pre>
      </details>
    </section>
  );
}
