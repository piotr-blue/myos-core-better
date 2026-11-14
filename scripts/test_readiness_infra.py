import copy
import unittest
from readiness_infra import prepare, validate_changes


class ReadinessInfrastructureTest(unittest.TestCase):
    def test_only_adds_deletion_protection_and_observable_alarms(self):
        original = {'Resources': {
            'Documents': {'Type': 'AWS::DynamoDB::Table', 'Properties': {'TableName': 'existing'}},
            'Host': {'Type': 'AWS::EC2::Instance', 'Properties': {'InstanceType': 'existing'}},
            'Role': {'Type': 'AWS::IAM::Role', 'Properties': {'Policies': []}},
        }}
        before = copy.deepcopy(original)
        result = prepare(original)
        self.assertEqual(before, original)
        self.assertEqual(original['Resources']['Host'], result['Resources']['Host'])
        self.assertEqual(original['Resources']['Role'], result['Resources']['Role'])
        self.assertTrue(result['Resources']['Documents']['Properties']['DeletionProtectionEnabled'])
        for name in ('HostCpuAlarm', 'HostStatusAlarm'):
            self.assertFalse(result['Resources'][name]['Properties']['ActionsEnabled'])
        with self.assertRaises(ValueError):
            prepare(result)

    def test_change_review_rejects_replacements_deletions_and_unexpected_changes(self):
        good = [{'ResourceChange': {'LogicalResourceId': name, 'Action': action, 'Replacement': 'False'}}
                for name, action in (('Documents', 'Modify'), ('HostCpuAlarm', 'Add'), ('HostStatusAlarm', 'Add'))]
        validate_changes(good)
        for mutation in ('replace', 'remove', 'unexpected'):
            changed = copy.deepcopy(good)
            if mutation == 'replace': changed[0]['ResourceChange']['Replacement'] = 'True'
            elif mutation == 'remove': changed[0]['ResourceChange']['Action'] = 'Remove'
            else: changed.append({'ResourceChange': {'LogicalResourceId': 'Role', 'Action': 'Modify'}})
            with self.assertRaises(ValueError):
                validate_changes(changed)
