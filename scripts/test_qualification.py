"""Hermetic checks for generated qualification commands; no AWS credentials or calls."""
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import qualify_cluster


class ClusterQualificationTest(unittest.TestCase):
    def qualification(self, directory):
        result = qualify_cluster.Qualification.__new__(qualify_cluster.Qualification)
        result.root = Path(directory)
        result.reports = Path(directory) / 'reports'
        result.reports.mkdir()
        (Path(directory) / 'scripts').mkdir()
        (Path(directory) / 'scripts/cluster_probe.py').write_text('print("test")\n')
        result.deployment = {'artifactSha256': 'a' * 64, 'table': 'test-documents',
                             'bucket': 'test-artifacts', 'instance': 'i-primary'}
        result.release = '/opt/myos-better/releases/' + 'a' * 64
        result.peer = 'i-peer'
        result.run = 'cluster-test'
        result.guard = 'myos-better-cluster-test'
        result.remote_script = '/var/lib/myos-better/cluster-test.py'
        result.commands = []
        result.guarded = False
        return result

    def test_install_pins_artifact_and_preserves_table_variable(self):
        requests = []

        def aws(*args):
            if args[:2] == ('s3api', 'put-object'):
                return {}
            if args[:2] == ('ssm', 'get-command-invocation'):
                return {'Status': 'Success'}
            if args[:2] != ('ssm', 'send-command'):
                raise AssertionError('Unexpected AWS call: ' + str(args))
            path = args[args.index('--cli-input-json') + 1].removeprefix('file://')
            request = json.loads(Path(path).read_text())
            subprocess.run(['bash', '-n'], input='\n'.join(request['Parameters']['commands']),
                           text=True, check=True)
            requests.append(request)
            return {'Command': {'CommandId': 'test-command'}}

        with tempfile.TemporaryDirectory() as directory, patch.object(qualify_cluster, 'aws', aws):
            runner = self.qualification(directory)
            runner.install()
            self.assertTrue(runner.guarded)
        self.assertEqual(4, len(requests))
        install = '\n'.join(requests[0]['Parameters']['commands'])
        self.assertIn('Environment=MYOS_TABLE=test-documents\n', install)
        self.assertIn('ExecStart=/opt/myos-better/releases/' + 'a' * 64 + '/bin/', install)
        self.assertIn('sha256sum --check --status', install)
        self.assertNotIn('@TABLE@', install)
        self.assertIn('ExecStopPost=/usr/bin/systemctl start myos-better.service',
                      '\n'.join(requests[-1]['Parameters']['commands']))

    def test_health_requires_durable_mode(self):
        import shlex
        tokens = shlex.split(qualify_cluster.Qualification.health())
        code = tokens[tokens.index('python3') + 2]
        for durable in (True, False):
            result = subprocess.run(['/usr/bin/python3', '-c', code],
                                    input=json.dumps({'status': 'ok', 'durable': durable}),
                                    text=True, capture_output=True)
            self.assertEqual(durable, result.returncode == 0)


class ThroughputQualificationTest(unittest.TestCase):
    def deployment(self):
        return {'artifactSha256': 'a' * 64, 'table': 'test-documents',
                'bucket': 'test-artifacts', 'instance': 'i-primary', 'workers': 8,
                'sessionEntries': 256, 'sessionMiB': 512, 'sessionUses': 32}

    def commands(self, profile):
        import argparse
        import run_throughput
        return run_throughput.commands(self.deployment(), 'b' * 64, 'releases/tools/tool.py',
                                       'bounded-test', argparse.Namespace(
                                           accounts=16, orders=8, operations=32, profile=profile))

    def test_pins_the_release_and_locks_before_running_or_profiling(self):
        import shlex
        for profile in (False, True):
            with self.subTest(profile=profile):
                commands = self.commands(profile)
                subprocess.run(['bash', '-n'], input='\n'.join(commands), text=True, check=True)
                self.assertIn('flock -n 9', commands[1])
                release = '/opt/myos-better/releases/' + 'a' * 64
                workload = next(c for c in commands if '--examples' in c)
                self.assertIn(release + '/examples', workload)
                self.assertNotIn('current/examples', workload)
                self.assertTrue(any('MainPID' in c and '-eq "$pid"' in c for c in commands))
                self.assertTrue(any('sha256sum --check --status' in c for c in commands))
                for command in commands:
                    if command.startswith('python3 -c '):
                        compile(shlex.split(command)[2], 'generated-check', 'exec')
                start = next((i for i, c in enumerate(commands) if 'JFR.start' in c), None)
                if profile:
                    self.assertLess(start, commands.index(workload))
                    self.assertTrue(any(c.startswith('trap ') and 'JFR.stop' in c for c in commands))
                else:
                    self.assertIsNone(start)

    def test_rejects_cross_stack_metadata_and_incomplete_deployment(self):
        import run_throughput
        outputs = {'Instance': 'i-primary', 'Table': 'test-documents', 'Artifacts': 'test-artifacts'}
        run_throughput.check_deployment(self.deployment(), outputs, 'Success')
        for key in ('instance', 'table', 'bucket', 'artifactSha256'):
            with self.subTest(key=key):
                changed = dict(self.deployment(), **{key: 'wrong'})
                with self.assertRaises(RuntimeError):
                    run_throughput.check_deployment(changed, outputs, 'Success')
        with self.assertRaises(RuntimeError):
            run_throughput.check_deployment(self.deployment(), outputs, 'InProgress')

    def test_running_process_check_requires_exact_release_and_capacity(self):
        import io
        import shlex
        import sys
        from types import SimpleNamespace
        command = self.commands(False)[4]
        tokens = shlex.split(command)
        code, argv = tokens[2], ['check', '123', *tokens[4:]]
        release = '/opt/myos-better/releases/' + 'a' * 64
        expected = {'maximumEntries': 256, 'maximumBytes': 512 * 1024 * 1024, 'maximumUses': 32}
        for problem in (None, 'release', 'durable', 'workers', 'entries', 'bytes', 'uses', 'edition'):
            with self.subTest(problem=problem):
                actual_release = '/another/release' if problem == 'release' else release
                process = SimpleNamespace(read_bytes=lambda: (
                    'java\0-cp\0' + actual_release + '/lib/app.jar\0Main').encode())
                health = {'status': 'ok', 'durable': problem != 'durable',
                          'workers': 1 if problem == 'workers' else 8,
                          'edition': 'wrong' if problem == 'edition' else 'AYqefXDffJR23qq1exeZN33bNVxT6ZiraXtJg9bk4Vup'}
                cache = dict(expected)
                for name, field in [('entries', 'maximumEntries'), ('bytes', 'maximumBytes'),
                                    ('uses', 'maximumUses')]:
                    if problem == name: cache[field] = 1

                def response(url, timeout):
                    value = health if url.endswith('/health') else {'sessions': cache}
                    return io.BytesIO(json.dumps(value).encode())

                with patch('pathlib.Path', return_value=process), \
                        patch('urllib.request.urlopen', side_effect=response), patch.object(sys, 'argv', argv):
                    if problem is None: exec(code, {})
                    else:
                        with self.assertRaises(RuntimeError): exec(code, {})


class DeploymentRuntimeTest(unittest.TestCase):
    def test_health_requires_the_exact_selected_protocol_and_supported_editions(self):
        import deployment_runtime
        for profile in deployment_runtime.PROFILES:
            expected = deployment_runtime.settings(profile)
            problems = [None, 'edition', 'supportedEditions', 'durable']
            if profile == 'console-native': problems += ['nativeAdmission', 'timelineProcessing', 'timelineAutomatic', 'canonicalGraphs']
            for problem in problems:
                with self.subTest(profile=profile, problem=problem):
                    health = {'status': 'ok', 'durable': True,
                              'edition': expected['edition'],
                              'supportedEditions': expected['supportedEditions']}
                    if profile == 'console-native': health.update(nativeAdmission=True, timelineProcessing=True, timelineAutomatic=True, canonicalGraphs=True)
                    if problem: health[problem] = 'wrong'
                    result = subprocess.run(['/usr/bin/python3', '-c', deployment_runtime.health_script(profile)],
                                            input=json.dumps(health), text=True, capture_output=True)
                    self.assertEqual(problem is None, result.returncode == 0)
            subprocess.run(['bash', '-n'], input=deployment_runtime.health_check(profile),
                           text=True, check=True)
        self.assertIn('examples/runtime/canonical.json', deployment_runtime.environment('canonical'))
        self.assertIn('/current/examples/runtime/canonical.json',
                      deployment_runtime.environment('canonical'))
        with self.assertRaises(ValueError): deployment_runtime.settings('unknown')


class DeploymentGenerationTest(unittest.TestCase):
    def test_selected_profile_is_in_unit_receipt_and_health_check(self):
        import contextlib
        import io
        import runpy
        import sys
        import types
        import deployment_runtime
        source = Path(__file__).with_name('deploy.py').read_text()
        # Console packaging/authentication has its own tests; this fixture supplies only a host distribution.
        for profile in ('canonical',):
            with self.subTest(profile=profile), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                (root/'scripts').mkdir()
                (root/'scripts/deploy.py').write_text(source)
                distribution = root/'build/install/myos-core-better'
                (distribution/'examples/runtime').mkdir(parents=True)
                for manifest in ('canonical',):
                    (distribution/('examples/runtime/' + manifest + '.json')).write_text('{}')
                (distribution/'placeholder.txt').write_text('built artifact')
                requests = []
                outputs = {'Instance': 'i-test', 'Table': 'test-documents', 'Artifacts': 'test-artifacts'}

                def aws(*args):
                    if args[:2] == ('cloudformation', 'describe-stacks'):
                        return {'Stacks': [{'Outputs': [{'OutputKey': k, 'OutputValue': v}
                                                         for k, v in outputs.items()]}]}
                    if args[:2] == ('s3api', 'put-object'): return {}
                    if args[:2] == ('ssm', 'send-command'):
                        request = json.loads(Path(args[-1].removeprefix('file://')).read_text())
                        requests.append(request)
                        return {'Command': {'CommandId': 'test-command'}}
                    raise AssertionError('Unexpected AWS operation: ' + str(args))

                module = types.ModuleType('aws')
                module.aws, module.verify, module.STACK = aws, lambda: {'account': 'test'}, 'myos-better-20261105'
                with patch.dict(sys.modules, {'aws': module}), patch.object(sys, 'argv',
                        ['deploy.py', '--runtime-profile', profile]), contextlib.redirect_stdout(io.StringIO()):
                    runpy.run_path(str(root/'scripts/deploy.py'), run_name='__main__')
                command = '\n'.join(requests[0]['Parameters']['commands'])
                subprocess.run(['bash', '-n'], input=command, text=True, check=True)
                self.assertIn(deployment_runtime.health_check(profile), command)
                receipt = json.loads((root/'reports/deployment.json').read_text())
                self.assertEqual(profile, receipt['runtimeProfile'])
                self.assertEqual(deployment_runtime.settings(profile)['edition'], receipt['edition'])
                self.assertIn(deployment_runtime.environment(profile), command)


class ScaleGenerationTest(unittest.TestCase):
    def test_preparation_and_probe_have_separate_commands_and_pinned_runtime_configuration(self):
        import contextlib
        import io
        import runpy
        import sys
        import types
        source = Path(__file__).with_name('run_benchmark.py').read_text()
        for options, mode in [(['--prepare-only'], 'populate'), (['--probe'], 'probe'),
                              (['--probe', '--stored-links'], 'probe-links'),
                              (['--probe', '--stored-links', '--separate-acks'], 'probe-links')]:
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                (root/'scripts').mkdir()
                (root/'scripts/run_benchmark.py').write_text(source)
                (root/'reports').mkdir()
                deployment = {'instance': 'i-test', 'table': 'test-documents', 'bucket': 'test-artifacts',
                              'artifactSha256': 'a'*64, 'commandId': 'deployment',
                              'runtimeProfile': 'canonical', 'edition': 'AYqefXDffJR23qq1exeZN33bNVxT6ZiraXtJg9bk4Vup'}
                (root/'reports/deployment.json').write_text(json.dumps(deployment))
                requests = []
                outputs = {'Instance': 'i-test', 'Table': 'test-documents', 'Artifacts': 'test-artifacts'}

                def aws(*args):
                    if args[:2] == ('cloudformation', 'describe-stacks'):
                        return {'Stacks': [{'Outputs': [{'OutputKey': k, 'OutputValue': v} for k, v in outputs.items()]}]}
                    if args[:2] == ('ssm', 'get-command-invocation'): return {'Status': 'Success'}
                    if args[:2] == ('ssm', 'send-command'):
                        requests.append(json.loads(Path(args[-1].removeprefix('file://')).read_text()))
                        return {'Command': {'CommandId': 'test-command'}}
                    raise AssertionError('Unexpected AWS call: ' + str(args))

                module = types.ModuleType('aws')
                module.aws, module.verify, module.STACK = aws, lambda: {'account': 'test'}, 'test-stack'
                with patch.dict(sys.modules, {'aws': module}), patch.object(sys, 'argv',
                        ['run_benchmark.py', '--orders', '128', '--account', 'bench-test', *options]), \
                        contextlib.redirect_stdout(io.StringIO()):
                    runpy.run_path(str(root/'scripts/run_benchmark.py'), run_name='__main__')
                command = '\n'.join(requests[0]['Parameters']['commands'])
                subprocess.run(['bash', '-n'], input=command, text=True, check=True)
                release = '/opt/myos-better/releases/' + 'a'*64
                self.assertIn('MYOS_RUNTIME='+release+'/examples/runtime/canonical.json', command)
                self.assertIn(release+'/bin/myos-core-better '+mode+' 128 ', command)
                self.assertIn('readlink -f /opt/myos-better/current', command)
                self.assertIn('trap restore EXIT', command)
                result = json.loads((root/'reports/benchmark-latest-command.json').read_text())
                self.assertEqual(mode, result['mode'])
                combine = '--separate-acks' not in options
                self.assertEqual(combine, result['combinedAcknowledgements'])
                self.assertIn('MYOS_COMBINE_ACKS=' + str(combine).lower(), command)
                self.assertEqual(0 if mode == 'populate' else 128, result['operations'])


class GrowthGenerationTest(unittest.TestCase):
    def test_growth_uses_the_deployed_edition_and_restores_healthy_on_failure(self):
        import contextlib
        import io
        import runpy
        import sys
        import types
        source = Path(__file__).with_name('run_growth.py').read_text()
        for profile, edition in [('canonical', 'AYqefXDffJR23qq1exeZN33bNVxT6ZiraXtJg9bk4Vup')]:
            with self.subTest(profile=profile), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                (root/'scripts').mkdir()
                (root/'scripts/run_growth.py').write_text(source)
                (root/'reports').mkdir()
                (root/'build/libs').mkdir(parents=True)
                (root/'build/libs/test-qualification.jar').write_bytes(b'qualification')
                deployment = {'instance': 'i-test', 'table': 'test-documents', 'bucket': 'test-artifacts',
                              'artifactSha256': 'a'*64, 'commandId': 'deployment',
                              'runtimeProfile': profile, 'edition': edition}
                (root/'reports/deployment.json').write_text(json.dumps(deployment))
                requests = []
                outputs = {'Instance': 'i-test', 'Table': 'test-documents', 'Artifacts': 'test-artifacts'}

                def aws(*args):
                    if args[:2] == ('cloudformation', 'describe-stacks'):
                        return {'Stacks': [{'Outputs': [{'OutputKey': k, 'OutputValue': v} for k, v in outputs.items()]}]}
                    if args[:2] == ('ssm', 'get-command-invocation'): return {'Status': 'Success'}
                    if args[:2] == ('s3api', 'put-object'): return {}
                    if args[:2] == ('ssm', 'send-command'):
                        requests.append(json.loads(Path(args[-1].removeprefix('file://')).read_text()))
                        return {'Command': {'CommandId': 'test-command'}}
                    raise AssertionError('Unexpected AWS call: ' + str(args))

                module = types.ModuleType('aws')
                module.aws, module.verify, module.STACK = aws, lambda: {'account': 'test'}, 'test-stack'
                with patch.dict(sys.modules, {'aws': module}), patch.object(sys, 'argv',
                        ['run_growth.py', '--orders', '20000', '--account', 'bench-test']), \
                        contextlib.redirect_stdout(io.StringIO()):
                    runpy.run_path(str(root/'scripts/run_growth.py'), run_name='__main__')
                command = '\n'.join(requests[0]['Parameters']['commands'])
                subprocess.run(['bash', '-n'], input=command, text=True, check=True)
                release = '/opt/myos-better/releases/' + 'a'*64
                self.assertIn('readlink -f /opt/myos-better/current', command)
                self.assertIn('flock -n 9', command)
                self.assertIn('trap restore EXIT', command)
                self.assertIn(edition, command)
                self.assertIn('sha256sum --check --status', command)
                self.assertIn('myos.better.AddOrderProbe bench-test', command)
                self.assertEqual(2, command.count('MYOS_RUNTIME='+release+'/examples/runtime/canonical.json'))
                result = json.loads((root/'reports/growth-latest-command.json').read_text())
                self.assertEqual(edition, result['creationEdition'])
                self.assertEqual(profile, result['runtimeProfile'])

                # Execute the exact generated EXIT handler with local service/health stand-ins.
                import shlex
                import deployment_runtime
                restore = next(line for line in requests[0]['Parameters']['commands']
                               if line.startswith('restore()'))
                for healthy in (True, False):
                    health = dict(status='ok', durable=True,
                                  edition=edition if healthy else 'unsupported',
                                  supportedEditions=deployment_runtime.settings(profile)['supportedEditions'])
                    standins = 'python3() { /usr/bin/python3 "$@"; }; ' + "systemctl() { echo SERVICE_STARTED; }; " \
                        "seq() { echo 1; }; sleep() { :; }; " \
                        "curl() { printf '%s\\n' " + shlex.quote(json.dumps(health)) + "; };\n"
                    recovery = subprocess.run(['bash'], input='set -euo pipefail\n'+standins+restore+'\nexit 19\n',
                                              text=True, capture_output=True)
                    self.assertEqual(19 if healthy else 1, recovery.returncode, recovery.stderr)
                    self.assertEqual(1, recovery.stdout.count('SERVICE_STARTED'))




class MultiProcessGenerationTest(unittest.TestCase):
    def test_services_and_recovery_pin_the_deployed_protocol_and_acknowledgment_policy(self):
        import contextlib
        import io
        import runpy
        import shlex
        import sys
        import types
        import deployment_runtime
        source = Path(__file__).with_name('run_multi_process.py').read_text()
        for profile, combined in [('canonical', False), ('canonical', True)]:
            with self.subTest(profile=profile), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                for folder in ('scripts', 'reports'):
                    (root/folder).mkdir()
                (root/'scripts/run_multi_process.py').write_text(source)
                (root/'scripts/multi_process.py').write_text('print("qualified")\n')
                selected = deployment_runtime.settings(profile)
                deployment = dict(selected, runtimeProfile=profile, artifactSha256='a'*64,
                                  instance='i-test', table='test-documents', bucket='test-artifacts',
                                  commandId='deployed', combinedAcknowledgements=combined)
                (root/'reports/deployment.json').write_text(json.dumps(deployment))
                requests = []
                outputs = {'Instance': 'i-test', 'Table': 'test-documents', 'Artifacts': 'test-artifacts'}

                def aws(*args):
                    if args[:2] == ('cloudformation', 'describe-stacks'):
                        return {'Stacks': [{'Outputs': [{'OutputKey': k, 'OutputValue': v}
                                                         for k, v in outputs.items()]}]}
                    if args[:2] == ('ssm', 'get-command-invocation'): return {'Status': 'Success'}
                    if args[:2] == ('s3api', 'put-object'): return {}
                    if args[:2] == ('ssm', 'send-command'):
                        requests.append(json.loads(Path(args[-1].removeprefix('file://')).read_text()))
                        return {'Command': {'CommandId': 'race'}}
                    raise AssertionError('Unexpected AWS operation: ' + str(args))

                module = types.ModuleType('aws')
                module.aws, module.verify, module.STACK = aws, lambda: {}, 'test-stack'
                with patch.dict(sys.modules, {'aws': module}), contextlib.redirect_stdout(io.StringIO()):
                    runpy.run_path(str(root/'scripts/run_multi_process.py'), run_name='__main__')
                commands = requests[0]['Parameters']['commands']
                subprocess.run(['bash', '-n'], input='\n'.join(commands), text=True, check=True)
                self.assertIn('flock -n 9', commands[1])
                self.assertIn('readlink -f /opt/myos-better/current', commands[2])
                services = [shlex.split(c) for c in commands if c.startswith('systemd-run ')]
                self.assertEqual(2, len(services))
                for service in services:
                    self.assertIn('MYOS_TABLE=test-documents', service)
                    self.assertIn('MYOS_COMBINE_ACKS=' + str(combined).lower(), service)
                    if selected['manifest']:
                        self.assertIn('MYOS_RUNTIME=/opt/myos-better/releases/' + 'a'*64
                                      + '/' + selected['manifest'], service)
                    else:
                        self.assertFalse(any(x.startswith('MYOS_RUNTIME=') for x in service))
                receipt = json.loads((root/'reports/multi-process-latest-command.json').read_text())
                self.assertEqual(selected['edition'], receipt['edition'])
                self.assertEqual(combined, receipt['combinedAcknowledgements'])

                # Execute the generated EXIT handler, including its exact health predicate.
                for problem in (None, 'edition', 'durable', 'combinedAcknowledgements'):
                    health = dict(status='ok', durable=True, edition=selected['edition'],
                                  supportedEditions=selected['supportedEditions'],
                                  combinedAcknowledgements=combined)
                    if problem:
                        health[problem] = not combined if problem == 'combinedAcknowledgements' else 'wrong'
                    standins = ('python3() { /usr/bin/python3 "$@"; }; '
                                'systemctl() { echo "SERVICE:$*"; }; seq() { echo 1; }; '
                                'sleep() { :; }; curl() { printf "%s\\n" '
                                + shlex.quote(json.dumps(health)) + '; };\n')
                    recovery = subprocess.run(['bash'], input='set -euo pipefail\n' + standins
                                              + commands[3] + '\nexit 19\n', text=True, capture_output=True)
                    self.assertEqual(19 if problem is None else 1, recovery.returncode, recovery.stderr)
                    self.assertEqual(1, recovery.stdout.count('SERVICE:start myos-better.service'))
                    self.assertIn('SERVICE:stop myos-better-qualification-a.service '
                                  'myos-better-qualification-b.service', recovery.stdout)


if __name__ == '__main__':
    unittest.main()
