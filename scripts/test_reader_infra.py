import unittest
import deployment_runtime
import reader_infra

class ReaderDeploymentTest(unittest.TestCase):
    def test_only_immutable_scoped_reader_custody_is_allowed(self):
        policy = reader_infra.template()['Resources']['ReaderCustody']['Properties']
        self.assertEqual([reader_infra.ROLE], policy['Roles'])
        statements = policy['PolicyDocument']['Statement']
        self.assertEqual({'secretsmanager:GetSecretValue', 'secretsmanager:CreateSecret'},
                         {action for row in statements for action in row['Action']})
        for row in statements:
            self.assertIn(':secret:' + reader_infra.PREFIX, row['Resource'])
        self.assertEqual(reader_infra.PREFIX + '*', statements[1]['Condition']['StringLike']['secretsmanager:Name'])
        env = deployment_runtime.environment('console-native')
        self.assertIn('/examples/clause-tutorial/runtime-console.json', env)
        self.assertIn('MYOS_READER_SECRET_PREFIX=' + reader_infra.PREFIX, env)
        self.assertNotIn('MYOS_PROVIDER_SECRET', env)

    def test_preview_scope_is_explicit_and_cannot_inject_unit_settings(self):
        env = deployment_runtime.environment('console-native', reader_prefix='myos-better-reader-canonical-v1-')
        self.assertIn('MYOS_READER_SECRET_PREFIX=myos-better-reader-canonical-v1-', env)
        with self.assertRaises(ValueError):
            deployment_runtime.environment('console-native', reader_prefix='unsafe\nEnvironment=OTHER=value')

if __name__ == '__main__': unittest.main()
