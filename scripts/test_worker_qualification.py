"""Worker comparisons pin both executables, exclude fallback hosts and restore the service."""
import shlex
import subprocess
import unittest

import deployment_runtime
import run_worker_discovery as worker


class WorkerQualificationTest(unittest.TestCase):
    def deployment(self):
        profile = deployment_runtime.settings('canonical')
        return dict(artifactSha256='a' * 64, table='test-documents', bucket='test-artifacts',
                    edition=profile['edition'], supportedEditions=profile['supportedEditions'])

    def test_pins_each_fork_and_restores_the_original_service_on_failure(self):
        commands = worker.commands(self.deployment(), dict(baseline='b' * 64, candidate='c' * 64, tool='d' * 64),
                                   {'blue-java-better-test.jar': 'e' * 64}, 'worker-test', 8, 5, 8)
        shell = '\n'.join(commands)
        subprocess.run(['bash', '-n'], input=shell, text=True, check=True)
        self.assertIn('flock -n 9', shell)
        self.assertLess(shell.index('trap restore EXIT'), shell.index('systemctl stop'))
        self.assertIn('systemctl start myos-better.service || result=1', shell)
        self.assertIn('test "$healthy" -eq 1 || result=1', shell)
        workloads = [line for line in commands if 'myos.better.WorkerDiscoveryProbe' in line]
        self.assertEqual(4, len(workloads))
        for index, (line, digest) in enumerate(zip(workloads, ['b', 'c', 'c', 'b'])):
            args = shlex.split(line)
            classpath = args[args.index('-cp') + 1]
            self.assertTrue(classpath.startswith('/var/lib/myos-better/worker-' + digest * 64 + '.jar:'))
            self.assertNotIn('*', classpath)
            self.assertNotIn('myos-core-better', classpath)
            self.assertIn('blue-java-better-test.jar', classpath)
            self.assertIn('worker-bench-test-' + str(index), args)
            self.assertIn('/usr/bin/timeout --signal=TERM --kill-after=15s 120s', line)

    def test_rejects_unpinned_dependencies_and_unbounded_runs(self):
        artifacts = dict(baseline='b' * 64, candidate='c' * 64, tool='d' * 64)
        runtime = {'blue-java-better-test.jar': 'e' * 64}
        for name, workers, idle, samples in [('production', 8, 5, 8), ('worker-test', 33, 5, 8),
                                            ('worker-test', 8, 1, 8), ('worker-test', 8, 5, 17)]:
            with self.assertRaises(ValueError):
                worker.commands(self.deployment(), artifacts, runtime, name, workers, idle, samples)
        for values in [{}, {'../other.jar': 'e' * 64}, dict(runtime, **{'myos-core-better-old.jar': 'f' * 64})]:
            with self.assertRaises(ValueError):
                worker.commands(self.deployment(), artifacts, values, 'worker-test', 8, 5, 8)
        with self.assertRaises(ValueError):
            worker.commands(self.deployment(), dict(artifacts, candidate='wrong'), runtime, 'worker-test', 8, 5, 8)

    def test_each_fork_uses_the_same_bounded_backlog(self):
        artifacts = dict(baseline='b' * 64, candidate='c' * 64, tool='d' * 64)
        runtime = {'blue-java-better-test.jar': 'e' * 64}
        for invalid in [-1, 129]:
            with self.assertRaises(ValueError):
                worker.commands(self.deployment(), artifacts, runtime, 'worker-test', 8, 2, 4, invalid)
        shell = worker.commands(self.deployment(), artifacts, runtime, 'worker-test', 8, 2, 4, 64)
        workloads = [shlex.split(line) for line in shell if 'myos.better.WorkerDiscoveryProbe' in line]
        self.assertEqual(4, len(workloads))
        for args in workloads:
            index = args.index('myos.better.WorkerDiscoveryProbe')
            self.assertEqual(['8', '2', '4', '64'], args[index + 2:index + 6])
