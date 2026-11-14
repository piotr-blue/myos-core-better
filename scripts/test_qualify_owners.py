"""The AWS owner launcher records and applies the selected environment without cloud access."""
import contextlib
import io
import json
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import qualify_owners


class OwnerQualificationTest(unittest.TestCase):
    def test_catalogue_and_resource_settings_reach_the_worker_and_report(self):
        for counts, public, heap, cpu, memory, large in (
                ('1,128', False, 1, 2, '1536M', False),
                ('1,1000,10000', True, 8, 4, '12G', True)):
            with self.subTest(counts=counts), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                libraries = root / 'build/install/myos-core-better/lib'
                libraries.mkdir(parents=True)
                (libraries / 'myos-core-better-test.jar').write_bytes(b'host fixture')
                (libraries / 'blue-java-better-test.jar').write_bytes(b'library fixture')
                probes = root / 'build/libs'
                probes.mkdir()
                (probes / 'myos-core-better-test-qualification.jar').write_bytes(b'probe fixture')
                reports = root / 'reports'
                outputs = {'Instance': 'live', 'Table': 'test-table', 'Artifacts': 'test-bucket'}
                request = None

                def aws(*args):
                    nonlocal request
                    if args[:2] == ('cloudformation', 'describe-stacks'):
                        return {'Stacks': [{'Outputs': [
                            {'OutputKey': key, 'OutputValue': value}
                            for key, value in outputs.items()]}]}
                    if args[:2] == ('s3api', 'put-object'):
                        return {}
                    if args[:2] == ('ssm', 'send-command'):
                        request = json.loads(Path(args[-1].removeprefix('file://')).read_text())
                        return {'Command': {'CommandId': 'test-command'}}
                    self.fail('Unexpected cloud call: ' + repr(args))

                arguments = ['qualify_owners.py', '--orders', counts, '--samples', '8',
                             '--report-dir', str(reports)]
                if public:
                    arguments.append('--public-definitions')
                with patch.object(qualify_owners, '__file__', str(root / 'scripts/qualify_owners.py')), \
                        patch.object(qualify_owners, 'aws', side_effect=aws), \
                        patch.object(qualify_owners, 'verify'), \
                        patch.object(qualify_owners, 'worker',
                                     return_value=contextlib.nullcontext('dedicated')) as worker, \
                        patch.object(qualify_owners, 'wait') as wait, \
                        patch('sys.argv', arguments), contextlib.redirect_stdout(io.StringIO()):
                    qualify_owners.main()
                worker.assert_called_once_with(outputs, reports, None, large_graph=large)
                self.assertEqual(['dedicated'], request['InstanceIds'])
                commands = request['Parameters']['commands']
                subprocess.run(['bash', '-n'], input='\n'.join(commands), text=True, check=True)
                workload = shlex.split(commands[-1])
                self.assertIn('--setenv=MYOS_PUBLIC_DEFINITIONS=' + str(public).lower(), workload)
                self.assertIn('-Xmx' + str(heap) + 'g', workload)
                self.assertIn('--property=MemoryMax=' + memory, workload)
                self.assertIn('--property=CPUQuota=' + str(cpu * 100) + '%', workload)
                self.assertIn('-Dblue.probe.orders=' + counts, workload)
                self.assertIn('-Dblue.probe.samples=8', workload)
                self.assertIn('Refusing to benchmark on a live service host', '\n'.join(commands))
                report = json.loads((reports / 'owners-qualification-command.json').read_text())
                self.assertEqual(public, report['publicDefinitions'])
                self.assertEqual(heap, report['heapGiB'])
                self.assertEqual(cpu, report['cpu'])
                self.assertEqual(3, len(report['artifacts']))
                self.assertTrue(report['account'].startswith('owner-qa-'))
                wait.assert_called_once()
                self.assertEqual(5700 if large else 900, wait.call_args.kwargs['timeout'])

    def test_invalid_workloads_fail_before_credentials_or_cloud_calls(self):
        for arguments in (['--samples', '0'], ['--samples', '21'], ['--orders', '0'],
                          ['--orders', '10001'], ['--separate-timelines', '--rotate-targets']):
            with self.subTest(arguments=arguments), \
                    patch('sys.argv', ['qualify_owners.py', *arguments]), \
                    patch.object(qualify_owners, 'verify') as verify, \
                    patch.object(qualify_owners, 'aws') as aws, \
                    contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit):
                    qualify_owners.main()
                verify.assert_not_called()
                aws.assert_not_called()


if __name__ == '__main__':
    unittest.main()
