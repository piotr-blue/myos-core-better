import copy
import unittest
import provider_infra as provider

class ProviderInfrastructureTest(unittest.TestCase):
    def test_retained_empty_secret_and_least_privilege_reader(self):
        resources = provider.template()['Resources']
        secret = resources['Identity']
        self.assertEqual('Retain', secret['DeletionPolicy'])
        self.assertNotIn('SecretString', secret['Properties'])
        self.assertNotIn('GenerateSecretString', secret['Properties'])
        statement = resources['ReadIdentity']['Properties']['PolicyDocument']['Statement'][0]
        self.assertEqual(['secretsmanager:GetSecretValue'], statement['Action'])
        self.assertEqual({'Ref': 'Identity'}, statement['Resource'])

    def test_deployment_rejects_wrong_account_unpinned_version_and_endpoint_changes(self):
        origin = 'https://d2ya9daeiur7nz.cloudfront.net'
        config = dict(secretArn='arn:aws:secretsmanager:eu-west-1:581571886327:secret:test-AbCdEf',
                      secretVersion='00000000-0000-0000-0000-000000000000',
                      providerId='GMzA1zqLaxpDhxefhcS3gWtPFQobAUB5rgWSj3jtvaJa',
                      endpoint=origin+'/api/provider', namespace='console-timelines')
        self.assertIn('Environment=MYOS_PROVIDER_SECRET_VERSION=', provider.environment(config, origin))
        for field, value in [('secretArn', config['secretArn'].replace('581571886327', '111111111111')),
                             ('secretVersion', 'AWSCURRENT'), ('providerId', 'x\nEnvironment=evil'),
                             ('endpoint', 'https://other.example/api/provider'), ('namespace', 'replacement')]:
            invalid=copy.deepcopy(config);invalid[field]=value
            with self.assertRaises(ValueError): provider.validate(invalid, origin)

if __name__ == '__main__': unittest.main()
