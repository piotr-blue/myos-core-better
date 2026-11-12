import copy
import unittest
import webhook_infra as callbacks


class WebhookInfrastructureTest(unittest.TestCase):
    def test_retained_independent_key_and_exact_read_permission(self):
        resources = callbacks.template()['Resources']
        secret = resources['Identity']
        self.assertEqual('Retain', secret['DeletionPolicy'])
        self.assertEqual('Retain', secret['UpdateReplacePolicy'])
        self.assertNotIn('SecretString', secret['Properties'])
        generated = secret['Properties']['GenerateSecretString']
        self.assertEqual(64, generated['PasswordLength'])
        self.assertEqual('seed', generated['GenerateStringKey'])
        self.assertTrue(generated['ExcludePunctuation'])
        statement = resources['ReadIdentity']['Properties']['PolicyDocument']['Statement'][0]
        self.assertEqual(['secretsmanager:GetSecretValue'], statement['Action'])
        self.assertEqual({'Ref': 'Identity'}, statement['Resource'])

    def test_only_exact_authorized_version_pins_can_enter_the_unit(self):
        pin = dict(secretArn='arn:aws:secretsmanager:eu-west-1:581571886327:secret:test-AbCdEf',
                   secretVersion='00000000-0000-0000-0000-000000000000')
        self.assertEqual(2, callbacks.environment(pin).count('Environment='))
        for field, value in [('secretArn', pin['secretArn'].replace('581571886327', '111111111111')),
                             ('secretArn', pin['secretArn'].replace('eu-west-1', 'us-east-1')),
                             ('secretVersion', 'AWSCURRENT'), ('secretVersion', 'bad\nEnvironment=evil')]:
            invalid = copy.deepcopy(pin); invalid[field] = value
            with self.assertRaises(ValueError): callbacks.validate(invalid)


if __name__ == '__main__':
    unittest.main()
