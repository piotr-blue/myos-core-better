"""Isolated HTTPS console edge for the existing Better host; no original MyOS resources."""
from aws import ACCOUNT, REGION

STACK = 'myos-better-console-20261105'
INSTANCE = 'i-0d892c8d2e30807a2'

def template(target):
    if target['instance'] != INSTANCE or len(target['securityGroups']) != 1:
        raise ValueError('Unexpected Better deployment target')
    ref = lambda name: {'Ref': name}
    get = lambda name, attr: {'Fn::GetAtt': [name, attr]}
    tags = {'owner': 'myos-core-better', 'environment': 'isolated-experiment', 'review-after': '2026-11-12'}
    return {
        'AWSTemplateFormatVersion': '2010-09-09',
        'Description': 'Authenticated MyOS Better console; private EC2 origin and isolated Cognito identities.',
        'Resources': {
            'UserPool': {'Type': 'AWS::Cognito::UserPool', 'DeletionPolicy': 'Retain', 'UpdateReplacePolicy': 'Retain', 'Properties': {
                'UserPoolName': STACK, 'UsernameAttributes': ['email'], 'AutoVerifiedAttributes': ['email'],
                'UsernameConfiguration': {'CaseSensitive': False},
                'Policies': {'PasswordPolicy': {'MinimumLength': 12, 'RequireLowercase': True, 'RequireUppercase': True, 'RequireNumbers': True, 'RequireSymbols': False}},
                'AccountRecoverySetting': {'RecoveryMechanisms': [{'Name': 'verified_email', 'Priority': 1}]},
                'UserPoolTags': tags}},
            'UserClient': {'Type': 'AWS::Cognito::UserPoolClient', 'Properties': {
                'UserPoolId': ref('UserPool'), 'GenerateSecret': False,
                'ExplicitAuthFlows': ['ALLOW_USER_PASSWORD_AUTH', 'ALLOW_REFRESH_TOKEN_AUTH'],
                'PreventUserExistenceErrors': 'ENABLED', 'EnableTokenRevocation': True,
                'AccessTokenValidity': 1, 'IdTokenValidity': 1, 'RefreshTokenValidity': 1}},
            'ConsoleIngress': {'Type': 'AWS::EC2::SecurityGroupIngress', 'Properties': {
                'GroupId': target['securityGroups'][0], 'IpProtocol': 'tcp', 'FromPort': 8088, 'ToPort': 8088,
                'SourcePrefixListId': target['prefixList'], 'Description': 'MyOS Better console via CloudFront only'}},
            'VpcOrigin': {'Type': 'AWS::CloudFront::VpcOrigin', 'DependsOn': 'ConsoleIngress', 'Properties': {
                'VpcOriginEndpointConfig': {'Name': STACK, 'Arn': f'arn:aws:ec2:{REGION}:{ACCOUNT}:instance/{INSTANCE}',
                    'HTTPPort': 8088, 'HTTPSPort': 443, 'OriginProtocolPolicy': 'http-only', 'OriginSSLProtocols': ['TLSv1.2']}}},
            'Distribution': {'Type': 'AWS::CloudFront::Distribution', 'Properties': {'DistributionConfig': {
                'Enabled': True, 'Comment': 'MyOS Better console — isolated experiment', 'HttpVersion': 'http2and3', 'PriceClass': 'PriceClass_100',
                'Origins': [{'Id': 'Better', 'DomainName': target['privateDns'], 'VpcOriginConfig': {'VpcOriginId': get('VpcOrigin', 'Id'), 'OriginReadTimeout': 60}}],
                'DefaultCacheBehavior': {'TargetOriginId': 'Better', 'ViewerProtocolPolicy': 'redirect-to-https',
                    'AllowedMethods': ['GET', 'HEAD', 'OPTIONS', 'PUT', 'POST', 'PATCH', 'DELETE'], 'CachedMethods': ['GET', 'HEAD'],
                    'CachePolicyId': '4135ea2d-6df8-44a3-9df3-4b5a84be39ad', 'OriginRequestPolicyId': 'b689b0a8-53d0-40ab-baf2-68738e2966ac', 'Compress': True},
                'ViewerCertificate': {'CloudFrontDefaultCertificate': True},
                'CustomErrorResponses': [{'ErrorCode': code, 'ErrorCachingMinTTL': 0} for code in [400,403,404,405,500,502,503,504]]},
                'Tags': [{'Key': key, 'Value': value} for key, value in tags.items()]}}
        },
        'Outputs': {**{name: {'Value': ref(name)} for name in ['UserPool', 'UserClient', 'Distribution']},
                    'Url': {'Value': {'Fn::Sub': 'https://${Distribution.DomainName}'}}}
    }
