"""The canonical preview owns fresh storage and custody, while reusing sign-in identities."""
import json
from pathlib import Path
import unittest

import preview_infra as preview


class PreviewInfrastructureTest(unittest.TestCase):
    def test_storage_and_signing_are_scoped_to_new_stack(self):
        base = json.loads((Path(__file__).resolve().parents[1] / 'infra/stack.json').read_text())
        original = json.dumps(base, sort_keys=True)
        value = preview.core_template(base)
        self.assertEqual(original, json.dumps(base, sort_keys=True))
        resources = value['Resources']
        self.assertEqual(preview.STACK + '-documents', resources['Documents']['Properties']['TableName'])
        self.assertEqual('Retain', resources['Documents']['DeletionPolicy'])
        self.assertEqual('Retain', resources['ProviderIdentity']['DeletionPolicy'])
        self.assertEqual([], resources['SecurityGroup']['Properties']['SecurityGroupIngress'])
        custody = resources['Role']['Properties']['Policies'][-1]['PolicyDocument']['Statement']
        self.assertEqual({'Ref': 'ProviderIdentity'}, custody[0]['Resource'])
        self.assertIn(preview.READER_PREFIX, custody[1]['Resource'])
        self.assertNotIn('console-v1', json.dumps(custody))

    def test_edge_reuses_identity_pool_without_mutating_existing_resources(self):
        base = json.loads((Path(__file__).resolve().parents[1] / 'infra/console-stack.json').read_text())
        target = {'instance': 'i-new', 'securityGroup': 'sg-new', 'privateDns': 'new.internal'}
        value = preview.edge_template(base, target, {'UserPool': 'existing-pool', 'UserClient': 'existing-client'})
        self.assertNotIn('UserPool', value['Resources'])
        self.assertNotIn('UserClient', value['Resources'])
        self.assertEqual({'Value': 'existing-pool'}, value['Outputs']['UserPool'])
        self.assertIn('i-new', value['Resources']['VpcOrigin']['Properties']['VpcOriginEndpointConfig']['Arn'])
        self.assertEqual('sg-new', value['Resources']['ConsoleIngress']['Properties']['GroupId'])
        self.assertEqual('new.internal', value['Resources']['Distribution']['Properties']['DistributionConfig']['Origins'][0]['DomainName'])


if __name__ == '__main__':
    unittest.main()
