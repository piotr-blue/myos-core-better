"""Native ingress qualification pins executable inputs and restores isolated native parent work."""
import json
import shlex
import subprocess
import unittest

import deployment_runtime
import run_native_admission as native


class NativeQualificationTest(unittest.TestCase):
    def test_published_timeline_parent_work_is_isolated_and_service_is_restored(self):
        commands = native.commands(self.deployment(), 'b' * 64, {'host.jar': 'c' * 64},
                                   'timeline-test', 'native-bench-timeline', 16, 32, 'published-timeline')
        shell = '\n'.join(commands)
        subprocess.run(['bash', '-n'], input=shell, text=True, check=True)
        self.assertIn('myos.better.PublishedTimelineProbe', shell)
        self.assertIn('trap restore EXIT', shell)
        self.assertIn('systemctl start myos-better.service || result=1', shell)
        self.assertIn('test "$healthy" -eq 1 || result=1', shell)
        self.assertLess(shell.index('trap restore EXIT'), shell.index('systemctl stop'))
        self.assertLess(shell.index('systemctl stop'), shell.index('result=0;'))
        self.assertNotIn('-eq "$pid"', shell)
        self.assertIn('flock -n 9', shell)
        with self.assertRaises(ValueError):
            native.commands(self.deployment(), 'b' * 64, {}, 'test', 'native-bench-test', 1, 1, 'unknown')

    def test_service_profile_runs_real_main_with_bounded_process_group_and_restoration(self):
        for protocol in ('timeline-service', 'timeline-directory', 'automatic-timeline', 'automatic-directory'):
            with self.subTest(protocol=protocol):
                shell = '\n'.join(native.commands(self.deployment(), 'b' * 64, {'host.jar': 'c' * 64},
                                                 'service-test', 'native-bench-service', 4, 8, protocol))
                subprocess.run(['bash', '-n'], input=shell, text=True, check=True)
                probe = 'AutomaticTimelineProbe' if protocol in ('automatic-timeline', 'automatic-directory') else 'TimelineServiceProbe'
                self.assertIn('myos.better.' + probe + ' native-bench-service 4 8' + (' directory' if protocol in ('timeline-directory', 'automatic-directory') else ''), shell)
                self.assertIn('timeout --signal=TERM --kill-after=15s 240s', shell)
                self.assertNotIn('--foreground', shell)
                self.assertLess(shell.index('trap restore EXIT'), shell.index('systemctl stop'))
                self.assertIn('systemctl start myos-better.service || result=1', shell)
                self.assertIn('test "$healthy" -eq 1 || result=1', shell)
                self.assertNotIn('-eq "$pid"', shell)

    def deployment(self):
        profile = deployment_runtime.settings('canonical')
        return {'artifactSha256': 'a' * 64, 'instance': 'i-primary', 'table': 'test-documents',
                'bucket': 'test-artifacts', 'runtimeProfile': 'canonical',
                'edition': profile['edition'], 'supportedEditions': profile['supportedEditions'],
                'combinedAcknowledgements': True}

    def test_pins_binary_tool_process_and_profile_without_stopping_the_main_service(self):
        commands = native.commands(self.deployment(), 'b' * 64,
                                   {'host.jar': 'c' * 64, 'language.jar': 'd' * 64},
                                   'native-test', 'native-bench-test', 16, 32)
        shell = '\n'.join(commands)
        subprocess.run(['bash', '-n'], input=shell, text=True, check=True)
        self.assertIn('flock -n 9', commands[1])
        self.assertNotIn('systemctl stop', shell)
        self.assertNotIn('systemd-run', shell)
        self.assertIn('/proc/$pid/cwd', shell)
        self.assertIn('-eq "$pid"', shell)
        self.assertIn('/usr/bin/timeout --signal=TERM --kill-after=15s 240s', shell)
        self.assertIn('MYOS_TABLE=test-documents', shell)
        self.assertIn('/releases/' + 'a' * 64 + '/lib/host.jar', shell)
        self.assertIn('c' * 64, shell)
        self.assertIn('d' * 64, shell)
        self.assertIn('b' * 64, shell)
        workload = next(c for c in commands if 'NativeAdmissionProbe' in c)
        self.assertIn('native-bench-test 16 32', workload)
        self.assertIn('|| result=$?', workload)
        self.assertTrue(commands[-1].endswith('"$result" -eq 0'))
        for command in commands:
            if '| python3 -c ' in command:
                tokens = shlex.split(command)
                code = tokens[-1]
                compile(code, 'generated-health-check', 'exec')
                health = {'status': 'ok', 'durable': True, 'combinedAcknowledgements': True,
                          'edition': self.deployment()['edition'],
                          'supportedEditions': self.deployment()['supportedEditions']}
                for accepted in (True, False):
                    health['combinedAcknowledgements'] = accepted
                    result = subprocess.run(['/usr/bin/python3', '-c', code], input=json.dumps(health),
                                            text=True, capture_output=True)
                    self.assertEqual(accepted, result.returncode == 0)

    def test_rejects_wrong_deployment_scope_and_unbounded_or_nonsynthetic_targets(self):
        deployment = self.deployment()
        outputs = {'Instance': 'i-primary', 'Table': 'test-documents', 'Artifacts': 'test-artifacts'}
        native.check_deployment(deployment, outputs, 'Success')
        for key in ['artifactSha256', 'instance', 'table', 'bucket', 'runtimeProfile', 'edition',
                    'supportedEditions', 'combinedAcknowledgements']:
            with self.subTest(key=key), self.assertRaises(RuntimeError):
                native.check_deployment(dict(deployment, **{key: 'wrong'}), outputs, 'Success')
        with self.assertRaises(RuntimeError):
            native.check_deployment(deployment, outputs, 'InProgress')
        for account, warmups, samples in [('production', 1, 1), ('native-bench-test', -1, 1),
                                           ('native-bench-test', 1, 65)]:
            with self.assertRaises(ValueError):
                native.commands(deployment, 'b' * 64, {}, 'test', account, warmups, samples)
        for warmups, samples in [(17, 1), (0, 33)]:
            with self.assertRaises(ValueError):
                native.commands(deployment, 'b' * 64, {}, 'test', 'native-bench-test', warmups, samples, 'automatic-timeline')
        with self.assertRaises(ValueError):
            native.commands(deployment, 'b' * 64, {'../other.jar': 'c' * 64},
                            'test', 'native-bench-test', 1, 1)
