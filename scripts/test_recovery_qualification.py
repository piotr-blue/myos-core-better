"""Same-history replay must pin binaries, retain the fixture and restore the deployed service."""
import shlex
import subprocess
import unittest
import run_timeline_recovery as recovery


class RecoveryQualificationTest(unittest.TestCase):
    def deployment(self):
        return dict(artifactSha256='a' * 64, table='test-documents', bucket='test-artifacts')

    def test_same_fixture_and_pinned_classpaths_survive_every_fork(self):
        commands = recovery.commands(self.deployment(), dict(baseline='b' * 64, candidate='c' * 64, tool='d' * 64),
                                     {'blue-java-better-test.jar': 'e' * 64}, 'recovery-test', 3)
        shell = '\n'.join(commands)
        subprocess.run(['bash', '-n'], input=shell, text=True, check=True)
        self.assertIn('flock -n 9', shell)
        self.assertLess(shell.index('trap restore EXIT'), shell.index('systemctl stop'))
        self.assertIn('systemctl start myos-better.service || result=1', shell)
        self.assertIn('test "$healthy" -eq 1 || result=1', shell)
        workloads = [line for line in commands if 'myos.better.TimelineRecoveryProbe' in line]
        self.assertEqual(5, len(workloads))
        for index, (line, digest) in enumerate(zip(workloads, ['b', 'b', 'c', 'c', 'b'])):
            args = shlex.split(line)
            classpath = args[args.index('-cp') + 1]
            self.assertTrue(classpath.startswith('/var/lib/myos-better/recovery-' + digest * 64 + '.jar:'))
            self.assertNotIn('*', classpath)
            self.assertNotIn('myos-core-better', classpath)
            self.assertIn('blue-java-better-test.jar', classpath)
            probe = args.index('myos.better.TimelineRecoveryProbe')
            self.assertEqual('seed' if index == 0 else 'replay', args[probe + 1])
            self.assertEqual('/var/lib/myos-better/recovery-test-fixture.json', args[probe + 2])
            self.assertIn('/usr/bin/timeout --signal=TERM --kill-after=15s 180s', line)

    def test_rejects_unpinned_dependencies_or_unbounded_samples(self):
        artifacts = dict(baseline='b' * 64, candidate='c' * 64, tool='d' * 64)
        runtime = {'blue-java-better-test.jar': 'e' * 64}
        for name, samples in [('production', 3), ('recovery-test', 0), ('recovery-test', 6)]:
            with self.assertRaises(ValueError):
                recovery.commands(self.deployment(), artifacts, runtime, name, samples)
        for values in [{}, {'../other.jar': 'e' * 64}, dict(runtime, **{'myos-core-better-old.jar': 'f' * 64})]:
            with self.assertRaises(ValueError):
                recovery.commands(self.deployment(), artifacts, values, 'recovery-test', 3)
        with self.assertRaises(ValueError):
            recovery.commands(self.deployment(), dict(artifacts, candidate='wrong'), runtime, 'recovery-test', 3)
