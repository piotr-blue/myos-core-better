"""Published-clause qualification must identify its actual catalogue and source."""
import contextlib
import io
import itertools
import json
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest
import zipfile
from unittest.mock import patch

import qualify_clauses


class ClauseQualificationTest(unittest.TestCase):
    def test_current_and_explicit_historical_profiles_reach_worker_and_evidence(self):
        for historical, profile in itertools.product((False, True), repeat=2):
            with self.subTest(historical=historical, profile=profile), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                install = root / 'build/install/myos-core-better'
                (install / 'lib').mkdir(parents=True)
                (install / 'lib/myos-core-better-test.jar').write_bytes(b'host')
                (install / 'lib/blue-java-better-test.jar').write_bytes(b'library')
                for path in ('examples/clause-tutorial/runtime.json',
                             'examples/clause-tutorial/runtime-current-clauses.json',
                             'examples/clause-payment-reaction/agreement.blue',
                             'examples/clause-current/agreement.blue'):
                    target = install / path
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_text('fixture ' + path)
                (root / 'build/libs').mkdir(parents=True)
                (root / 'build/libs/test-qualification.jar').write_bytes(b'probe')
                outputs = {'Instance': 'live', 'Table': 'fixture-table', 'Artifacts': 'fixture-bucket'}
                request = None

                def aws(*args):
                    nonlocal request
                    if args[:2] == ('cloudformation', 'describe-stacks'):
                        return {'Stacks': [{'Outputs': [{'OutputKey': k, 'OutputValue': v} for k, v in outputs.items()]}]}
                    if args[:2] == ('s3api', 'put-object'):
                        return {}
                    if args[:2] == ('ssm', 'send-command'):
                        request = json.loads(Path(args[-1].removeprefix('file://')).read_text())
                        return {'Command': {'CommandId': 'fixture-command'}}
                    self.fail('Unexpected cloud call')

                count = 100 if not historical and not profile else 20
                arguments = ['qualify_clauses.py', '--orders', str(count)]
                if profile:
                    arguments += ['--profile']
                if historical:
                    arguments += ['--historical-clauses', '--private-definitions']
                with patch.object(qualify_clauses, '__file__', str(root / 'scripts/qualify_clauses.py')), \
                        patch.object(qualify_clauses, 'aws', side_effect=aws), \
                        patch.object(qualify_clauses, 'verify'), \
                        patch.object(qualify_clauses, 'worker', return_value=contextlib.nullcontext('dedicated')), \
                        patch.object(qualify_clauses, 'wait', return_value={}) as waiter, \
                        patch('sys.argv', arguments), contextlib.redirect_stdout(io.StringIO()):
                    qualify_clauses.main()
                commands = request['Parameters']['commands']
                subprocess.run(['bash', '-n'], input='\n'.join(commands), text=True, check=True)
                workload = shlex.split(commands[-1])
                runtime = 'examples/clause-tutorial/' + ('runtime.json' if historical else 'runtime-current-clauses.json')
                source = 'examples/' + ('clause-payment-reaction' if historical else 'clause-current') + '/agreement.blue'
                self.assertIn('-Dblue.probe.clauseRuntime=' + runtime, workload)
                self.assertIn('-Dblue.probe.clauseSource=' + source, workload)
                self.assertIn('--setenv=MYOS_PUBLIC_DEFINITIONS=' + str(not historical).lower(), workload)
                self.assertIn('-XX:+UseG1GC', workload)
                metadata = json.loads(next((root / 'reports').glob('clauses-*/command.json')).read_text())
                self.assertEqual(runtime, metadata['runtimeManifest'])
                self.assertEqual(source, metadata['agreementSource'])
                self.assertEqual(not historical, metadata['publicDefinitions'])
                self.assertEqual('G1', metadata['garbageCollector'])
                self.assertEqual(profile, metadata['profiled'])
                self.assertIn('-Dblue.probe.clauseProfile=' + str(profile).lower(), workload)
                timeout = 2700 if count > 20 else 900
                self.assertEqual(timeout, metadata['timeoutSeconds'])
                self.assertEqual([str(timeout)], request['Parameters']['executionTimeout'])
                self.assertEqual(timeout + 45, waiter.call_args.kwargs['timeout'])
                self.assertEqual(3, len(metadata['artifacts']))
                self.assertEqual(64, len(metadata['runtimeSha256']))
                self.assertEqual(64, len(metadata['sourceSha256']))
                self.assertFalse((root / 'build/clause-qualification.tar.gz').exists())

    def test_current_catalogue_mounts_are_exact_deployed_identities(self):
        directory = Path(__file__).resolve().parents[1] / 'examples/clause-tutorial'
        deployed = json.loads((directory / 'runtime-console-deployed.json').read_text())
        expected = {mount: package['blueId'] for package in deployed['packages']
                    for mount, route in package['mounts'].items() if not route}
        selected = json.loads((directory / 'runtime-current-clauses.json').read_text())
        self.assertEqual(['document-dispatch'], selected['features'])
        self.assertNotIn('timelines', selected)
        aliases = set()
        for package in selected['packages']:
            with zipfile.ZipFile(directory / package['archive']) as archive:
                metadata = json.loads(archive.read('package.json'))
            self.assertEqual(package['blueId'], metadata['packageBlueId'])
            for alias, route in package['mounts'].items():
                identity = package['blueId']
                for step in route:
                    identity = metadata['semanticPackages'][identity]['imports'][step]['blueId']
                self.assertEqual(expected[alias], identity, alias)
                self.assertNotIn(alias, aliases)
                aliases.add(alias)
        self.assertEqual({'Activation', 'Parties', 'Contracts', 'Documents', 'Agreements',
                          'Connections', 'Payment', 'ManualPayments', 'PaymentInstruction',
                          'Orders', 'Coordination'}, aliases)

    def test_invalid_inputs_fail_before_credentials_or_cloud_access(self):
        for arguments in (['--orders', '0'], ['--orders', '101'], ['--reader-prefix', 'wrong'],
                          ['--audit', 'real-account', 'root']):
            with self.subTest(arguments=arguments), patch('sys.argv', ['qualify_clauses.py', *arguments]), \
                    patch.object(qualify_clauses, 'verify') as verify, \
                    patch.object(qualify_clauses, 'aws') as aws, contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit):
                    qualify_clauses.main()
                verify.assert_not_called()
                aws.assert_not_called()


if __name__ == '__main__':
    unittest.main()
