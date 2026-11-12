"""Private qualification owns one secret; cleanup cannot revoke a running or foreign job."""
import copy
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import run_private_custody as custody
import run_native_admission as native


class PrivateCustodyQualificationTest(unittest.TestCase):
    identifier = 'a' * 32

    def record(self):
        return dict(custody.scope(self.identifier), identifier=self.identifier,
                    accountId=custody.ACCOUNT, region=custody.REGION, stack=custody.STACK,
                    role=custody.STACK + '-Role-Example', policy=custody.policy(self.identifier),
                    commandId='command', instance='instance', submissionStarted=True)

    def test_policy_can_only_create_read_and_delete_one_fresh_qualification_secret(self):
        policy = custody.policy(self.identifier)
        self.assertEqual({'secretsmanager:CreateSecret', 'secretsmanager:GetSecretValue', 'secretsmanager:DeleteSecret'},
                         {a for s in policy['Statement'] for a in s['Action']})
        create, existing = policy['Statement']
        self.assertEqual('*', create['Resource'])
        self.assertEqual('arn:aws:secretsmanager:eu-west-1:581571886327:secret:myos-better-qualification-reader-' + self.identifier + '-??????', existing['Resource'])
        self.assertEqual({'StringEquals': {'secretsmanager:Name': self.record()['secretName'],
                                          'secretsmanager:Description': custody.DESCRIPTION,
                                          'aws:RequestedRegion': custody.REGION}}, create['Condition'])
        for invalid in ['production', '', '../secret', 'a'*31, 'A'*32, 'a'*32+';echo']:
            with self.subTest(identifier=invalid), self.assertRaises(ValueError): custody.scope(invalid)

    def test_native_command_is_bounded_pinned_and_restores_the_service(self):
        record = self.record()
        deployment = {'artifactSha256': 'd'*64, 'table': 'test-documents', 'bucket': 'test-artifacts'}
        shell = '\n'.join(native.commands(deployment, 'b'*64, {'host.jar': 'c'*64},
                                         'private-test', record['account'], 0, 1,
                                         'private-directory', record['secretName']))
        subprocess.run(['bash', '-n'], input=shell, text=True, check=True)
        self.assertIn('myos.better.PrivateTimelineServiceProbe ' + record['account'] + ' ' + record['secretName'], shell)
        self.assertIn('240s', shell)
        self.assertIn('trap restore EXIT', shell)
        self.assertLess(shell.index('trap restore EXIT'), shell.index('systemctl stop'))
        self.assertNotIn('privateKey', shell)
        self.assertNotIn('secret-string', shell)
        for name, warmups, samples in [('other-secret', 0, 1), (record['secretName'], 1, 1), (None, 0, 1)]:
            with self.assertRaises(ValueError):
                native.commands(deployment, 'b'*64, {}, 'private-test', record['account'], warmups, samples, 'private-directory', name)
        with self.assertRaises(ValueError):
            native.commands(deployment, 'b'*64, {}, 'private-test', record['account'], 0, 1, 'synthetic', record['secretName'])

    def test_scope_tampering_is_rejected_before_any_aws_call(self):
        for field in ['account', 'accountId', 'region', 'role', 'stack', 'secretName', 'policyName', 'policy']:
            record = self.record(); record[field] = 'foreign'
            with self.subTest(field=field), patch.object(custody, 'aws') as api, self.assertRaises(ValueError):
                custody.cleanup(record, Path('unused'))
            api.assert_not_called()

    def test_live_command_cannot_lose_its_secret_or_policy(self):
        for status in ['Pending', 'InProgress', 'Delayed', 'Cancelling']:
            with self.subTest(status=status), patch.object(custody, 'command', return_value={'Status': status}), patch.object(custody, 'aws') as api, self.assertRaises(RuntimeError):
                custody.cleanup(self.record(), Path('unused'))
            api.assert_not_called()

    def test_lost_submission_response_is_not_a_resubmit_or_cleanup_authority(self):
        record = self.record(); del record['commandId']; record.update(createdAt='2026-11-06T00:00:00Z', comment='expected')
        with patch.object(custody, 'aws', return_value={'Commands': []}) as api, self.assertRaises(RuntimeError):
            custody.command(record, Path('unused'))
        self.assertEqual(1, api.call_count)
        self.assertEqual(('ssm', 'list-commands'), api.call_args.args[:2])

    def test_terminal_cleanup_checks_both_resources_before_mutation(self):
        record = self.record(); selected = dict(Name=record['secretName'],
            ARN='arn:aws:secretsmanager:eu-west-1:581571886327:secret:'+record['secretName']+'-AbCdEf',
            Description=custody.DESCRIPTION)
        changed = copy.deepcopy(record['policy']); changed['Statement'][1]['Resource'] = '*'
        with patch.object(custody, 'command', return_value={'Status': 'Success'}), patch.object(custody, 'secret', return_value=selected), patch.object(custody, 'aws', return_value={'PolicyDocument': changed}) as api, self.assertRaises(RuntimeError):
            custody.cleanup(record, Path('unused'))
        self.assertEqual(1, api.call_count)
        self.assertEqual(('iam', 'get-role-policy'), api.call_args.args[:2])

    def test_terminal_cleanup_removes_only_exact_owned_resources_and_records_it(self):
        record = self.record(); selected = dict(Name=record['secretName'],
            ARN='arn:aws:secretsmanager:eu-west-1:581571886327:secret:'+record['secretName']+'-AbCdEf',
            Description=custody.DESCRIPTION)
        with tempfile.TemporaryDirectory() as directory, patch.object(custody, 'command', return_value={'Status': 'Success'}), patch.object(custody, 'secret', return_value=selected), patch.object(custody, 'aws', side_effect=[{'PolicyDocument': record['policy']}, {}, {}]) as api:
            target = Path(directory)/'record.json'; custody.cleanup(record, target)
            self.assertEqual(['get-role-policy', 'delete-secret', 'delete-role-policy'], [c.args[1] for c in api.call_args_list])
            self.assertIn(selected['ARN'], api.call_args_list[1].args)
            self.assertIn(record['policyName'], api.call_args_list[2].args)
            self.assertTrue(json.loads(target.read_text())['cleanup']['temporaryPolicyRemovedOrAbsent'])


if __name__ == '__main__': unittest.main()
