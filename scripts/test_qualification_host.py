"""Qualification cannot take down the live service or leak its temporary worker."""
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import qualification_host


class QualificationHostTest(unittest.TestCase):
    def test_refuses_the_live_host_before_any_aws_call(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(qualification_host, 'aws') as aws:
            with self.assertRaisesRegex(ValueError, 'live application'):
                with qualification_host.worker({'Instance': 'live'}, Path(directory), 'live'):
                    self.fail('Live worker was accepted')
            aws.assert_not_called()

    def test_temporary_worker_is_terminated_when_qualification_fails(self):
        calls = []

        def aws(*args):
            calls.append(args)
            if args[:2] == ('ec2', 'describe-instances'):
                return {'Reservations': [{'Instances': [{
                    'ImageId': 'ami-clean', 'InstanceType': 'c7i.xlarge', 'SubnetId': 'subnet',
                    'SecurityGroups': [{'GroupId': 'sg'}],
                    'IamInstanceProfile': {'Arn': 'arn:role'}, 'RootDeviceName': '/dev/xvda',
                }]}]}
            if args[:2] == ('ec2', 'run-instances'):
                return {'Instances': [{'InstanceId': 'temporary'}]}
            if args[:2] == ('ec2', 'describe-instance-types'):
                return {'InstanceTypes': [{'MemoryInfo': {'SizeInMiB': 8192}}]}
            if args[:2] == ('ssm', 'describe-instance-information'):
                return {'InstanceInformationList': [{'PingStatus': 'Online'}]}
            if args[:2] == ('ec2', 'terminate-instances'):
                return {}
            raise AssertionError(args)

        with tempfile.TemporaryDirectory() as directory, patch.object(qualification_host, 'aws', side_effect=aws):
            with self.assertRaisesRegex(RuntimeError, 'probe failed'):
                with qualification_host.worker({'Instance': 'live'}, Path(directory)) as identity:
                    self.assertEqual('temporary', identity)
                    raise RuntimeError('probe failed')
            self.assertIn(('ec2', 'terminate-instances', '--instance-ids', 'temporary'), calls)
            self.assertNotIn(('ec2', 'terminate-instances', '--instance-ids', 'live'), calls)

    def test_rejects_small_existing_worker_without_terminating_it(self):
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(qualification_host, 'instance', return_value={'InstanceType': 'small'}), \
                patch.object(qualification_host, 'aws', return_value={
                    'InstanceTypes': [{'MemoryInfo': {'SizeInMiB': 4096}}]}) as aws:
            with self.assertRaisesRegex(ValueError, 'at least 8 GiB'):
                with qualification_host.worker({'Instance': 'live'}, Path(directory), 'owned-by-user'):
                    self.fail('Undersized worker was accepted')
            self.assertEqual(1, aws.call_count)

    def test_large_graph_requires_sixteen_gib(self):
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(qualification_host, 'instance', return_value={'InstanceType': 'normal'}), \
                patch.object(qualification_host, 'aws', return_value={
                    'InstanceTypes': [{'MemoryInfo': {'SizeInMiB': 8192}}]}) as aws:
            with self.assertRaisesRegex(ValueError, 'at least 16 GiB'):
                with qualification_host.worker({'Instance': 'live'}, Path(directory), 'owned-by-user',
                                               large_graph=True):
                    self.fail('Undersized worker was accepted')
            self.assertEqual(1, aws.call_count)


class QualificationOutputTest(unittest.TestCase):
    def test_collects_complete_stdout_instead_of_inline_truncation(self):
        content = 'x' * 25000 + '\n{"passed":true}\n'
        def aws(*args):
            if args[:2] == ('s3api', 'list-objects-v2'):
                return {'Contents': [{'Key': 'reports/test/run/stdout', 'Size': len(content)}]}
            if args[:2] == ('s3api', 'get-object'):
                Path(args[-1]).write_text(content)
                return {}
            raise AssertionError(args)
        with tempfile.TemporaryDirectory() as directory, patch.object(qualification_host, 'aws', side_effect=aws):
            found = qualification_host.collect_stdout('bucket', 'reports/test', Path(directory) / 'result.json')
            self.assertEqual(content, found)

    def test_refuses_ambiguous_or_oversized_reports_before_download(self):
        for listing in ({'IsTruncated': True},
                        {'Contents': [{'Key': 'a/stdout', 'Size': 1}, {'Key': 'b/stdout', 'Size': 1}]},
                        {'Contents': [{'Key': 'a/stdout', 'Size': 16 * 1024 * 1024 + 1}]}):
            with tempfile.TemporaryDirectory() as directory, patch.object(qualification_host, 'aws', return_value=listing) as aws:
                with self.assertRaises(RuntimeError):
                    qualification_host.collect_stdout('bucket', 'reports/test', Path(directory) / 'result.json')
                self.assertEqual(1, aws.call_count)

    def test_missing_full_output_cannot_be_reported_as_success(self):
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(qualification_host, 'aws', return_value={'Status': 'Success', 'StandardOutputContent': 'partial'}), \
                patch.object(qualification_host, 'collect_stdout', side_effect=TimeoutError()), \
                patch.object(qualification_host.time, 'sleep'):
            destination = Path(directory) / 'result.json'
            with self.assertRaisesRegex(RuntimeError, 'output incomplete'):
                qualification_host.wait('command', 'instance', destination, output=('bucket', 'reports/test'))
            self.assertIn('OutputCollectionError', destination.read_text())

    def test_preserves_primary_failure_when_output_collection_also_fails(self):
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(qualification_host, 'aws', return_value={'Status': 'Failed'}), \
                patch.object(qualification_host, 'collect_stdout', side_effect=TimeoutError()), \
                patch.object(qualification_host.time, 'sleep'):
            with self.assertRaisesRegex(RuntimeError, 'Qualification failed'):
                qualification_host.wait('command', 'instance', Path(directory) / 'result.json',
                                        output=('bucket', 'reports/test'))


if __name__ == '__main__':
    unittest.main()
