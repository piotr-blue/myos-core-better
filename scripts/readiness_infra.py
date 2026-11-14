"""Narrow, reviewable safeguards for the existing isolated stack; no topology replacement."""
import copy


def prepare(template):
    result = copy.deepcopy(template)
    resources = result['Resources']
    if resources['Documents']['Type'] != 'AWS::DynamoDB::Table':
        raise ValueError('Expected the existing Documents table')
    if resources['Host']['Type'] != 'AWS::EC2::Instance':
        raise ValueError('Expected the existing test host')
    resources['Documents']['Properties']['DeletionProtectionEnabled'] = True
    for name, metric, threshold, period, description in (
        ('HostCpuAlarm', 'CPUUtilization', 85, 300, 'CPU above 85 percent for ten minutes'),
        ('HostStatusAlarm', 'StatusCheckFailed', 1, 60, 'EC2 health checks failing for two minutes'),
    ):
        if name in resources:
            raise ValueError('Review the existing alarm before changing it: ' + name)
        resources[name] = {
            'Type': 'AWS::CloudWatch::Alarm',
            'Properties': {
                'AlarmDescription': description + '; visible in CloudWatch, no notification destination configured',
                'Namespace': 'AWS/EC2', 'MetricName': metric,
                'Dimensions': [{'Name': 'InstanceId', 'Value': {'Ref': 'Host'}}],
                'Statistic': 'Maximum' if metric == 'StatusCheckFailed' else 'Average',
                'Period': period, 'EvaluationPeriods': 2,
                'Threshold': threshold, 'ComparisonOperator': 'GreaterThanOrEqualToThreshold',
                'TreatMissingData': 'notBreaching', 'ActionsEnabled': False,
            },
        }
    return result


def validate_changes(changes, original=None, proposed=None):
    expected = {'Documents': 'Modify', 'HostCpuAlarm': 'Add', 'HostStatusAlarm': 'Add'}
    actual = {}
    for item in changes:
        resource = item['ResourceChange']
        name = resource['LogicalResourceId']
        # CloudFormation may re-evaluate the unchanged IAM policy's Documents.Arn reference.
        # Accept that dependency only with identical role definitions and this exact cause.
        if (name == 'Role' and original is not None and proposed is not None
                and original['Resources']['Role'] == proposed['Resources']['Role']
                and resource.get('Details') == [{
                    'Target': {'Attribute': 'Properties', 'Name': 'Policies', 'RequiresRecreation': 'Never'},
                    'Evaluation': 'Dynamic', 'ChangeSource': 'ResourceAttribute',
                    'CausingEntity': 'Documents.Arn'}]):
            expected['Role'] = 'Modify'
        if resource.get('Replacement', 'False') != 'False':
            raise ValueError('Resource replacement is forbidden')
        actual[name] = resource['Action']
    if actual != expected or len(changes) != len(expected):
        raise ValueError('Unexpected resources in readiness change set: ' + repr(actual))
