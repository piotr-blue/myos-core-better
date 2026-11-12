"""Large-graph probes are pinned, bounded, synthetic-only and always restore the main service."""
import json
import sys
import tempfile
from pathlib import Path
import shlex
import subprocess
import unittest

import run_graph_load as graph


class GraphLoadQualificationTest(unittest.TestCase):
    def setup_args(self):
        return [dict(artifactSha256='a' * 64, bucket='test-artifacts', table='test-documents'),
                'b' * 64, {'blue-java-better-test.jar': 'c' * 64, 'myos-core-better-test.jar': 'd' * 64},
                'graph-load-test', [('bench-small', 128), ('bench-large', 20128)], 3, 64, 8, 0]

    def test_four_exact_forks_reuse_the_full_populations_with_service_restoration(self):
        lines = graph.commands(*self.setup_args())
        shell = '\n'.join(lines)
        subprocess.run(['bash', '-n'], input=shell, text=True, check=True)
        self.assertIn('flock -n 9', shell)
        self.assertIn('sha256sum --check --status', shell)
        self.assertLess(shell.index('trap restore EXIT'), shell.index('systemctl stop'))
        self.assertIn('systemctl start myos-better.service || result=1', shell)
        workloads = [shlex.split(line) for line in lines if 'myos.better.GraphLoadProbe' in line]
        self.assertEqual(4, len(workloads))
        for args, account, orders in zip(workloads, ['bench-small', 'bench-large', 'bench-large', 'bench-small'], [128, 20128, 20128, 128]):
            index = args.index('myos.better.GraphLoadProbe')
            self.assertEqual([account, str(orders), '3', '64', '8', '0'], args[index + 1:index + 7])
            self.assertNotIn('*', args[args.index('-cp') + 1])
            self.assertIn('180s', args)
            self.assertIn('MYOS_RUNTIME=/opt/myos-better/releases/' + 'a' * 64 + '/examples/runtime/canonical.json', args)

    def test_same_active_set_size_for_all_four_forks(self):
        args = self.setup_args()
        args[-1] = 16
        workloads = [shlex.split(line) for line in graph.commands(*args) if 'myos.better.GraphLoadProbe' in line]
        for command in workloads:
            index = command.index('myos.better.GraphLoadProbe')
            self.assertEqual('16', command[index + 6])

    def test_summary_accepts_trailing_diagnostics_and_rejects_missing_or_ambiguous_results(self):
        record = json.dumps(dict(status='PASSED', orders=128,
            sequential=dict(sequentialCompletionMedianMillis=12), concurrent=dict(completionMillis=34)))
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'probe.log'
            path.write_text('SDK startup\n' + record + '\nPropagation failure: AbortedException during shutdown\n')
            result = subprocess.run([sys.executable, '-c', graph.summary_code(), str(path)], capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(128, json.loads(result.stdout)['orders'])
            for contents in ['only diagnostics\n', record + '\n' + record + '\n']:
                path.write_text(contents)
                result = subprocess.run([sys.executable, '-c', graph.summary_code(), str(path)], capture_output=True, text=True)
                self.assertNotEqual(0, result.returncode)

    def test_rejects_user_accounts_unpinned_artifacts_and_unbounded_dimensions(self):
        for index, value in [(1, 'unpinned'), (2, {}),
                             (2, {'myos-core-better-one.jar': 'c'*64, 'myos-core-better-two.jar': 'd'*64, 'blue-java-better.jar': 'e'*64}), (3, 'user-run'),
                             (4, [('user-account', 128), ('bench-large', 20128)]),
                             (4, [('bench-same', 128), ('bench-same', 128)]),
                             (4, [('bench-small', 0), ('bench-large', 20128)]),
                             (5, 9), (6, 513), (7, 17), (8, -1), (8, 129)]:
            args = self.setup_args()
            args[index] = value
            with self.assertRaises(ValueError):
                graph.commands(*args)


if __name__ == '__main__':
    unittest.main()
