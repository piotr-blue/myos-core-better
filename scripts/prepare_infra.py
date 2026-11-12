#!/usr/bin/env python3
"""Generate reviewable bounded infrastructure. This script does not deploy it."""
import json
from pathlib import Path
from aws import aws, verify, STACK

root=Path(__file__).resolve().parents[1]
verify()
vpc=aws('ec2','describe-vpcs','--filters','Name=is-default,Values=true')['Vpcs'][0]['VpcId']
subnets=aws('ec2','describe-subnets','--filters',f'Name=vpc-id,Values={vpc}')['Subnets']
subnet=sorted(subnets,key=lambda s:s['AvailabilityZone'])[0]['SubnetId']
ami=aws('ssm','get-parameter','--name','/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64')['Parameter']['Value']
ref=lambda value:{'Ref':value}
get=lambda resource,attr:{'Fn::GetAtt':[resource,attr]}
sub=lambda value:{'Fn::Sub':value}
tags=[{'Key':'owner','Value':'myos-core-better'}, {'Key':'environment','Value':'isolated-experiment'},
      {'Key':'review-after','Value':'2026-11-12'}, {'Key':'project','Value':STACK}]
template={'AWSTemplateFormatVersion':'2010-09-09',
 'Description':'Isolated MyOS document-local runtime. One host, no inbound ports, DynamoDB and SSM.',
 'Resources':{
 'Artifacts':{'Type':'AWS::S3::Bucket','Properties':{
   'BucketEncryption':{'ServerSideEncryptionConfiguration':[{'ServerSideEncryptionByDefault':{'SSEAlgorithm':'AES256'}}]},
   'PublicAccessBlockConfiguration':{'BlockPublicAcls':True,'BlockPublicPolicy':True,'IgnorePublicAcls':True,'RestrictPublicBuckets':True},
   'VersioningConfiguration':{'Status':'Enabled'}, 'Tags':tags}},
 'Documents':{'Type':'AWS::DynamoDB::Table','DeletionPolicy':'Retain','UpdateReplacePolicy':'Retain','Properties':{
   'TableName':STACK+'-documents','BillingMode':'PAY_PER_REQUEST',
   'AttributeDefinitions':[{'AttributeName':n,'AttributeType':'S'} for n in ['pk','sk','work','position']],
   'KeySchema':[{'AttributeName':'pk','KeyType':'HASH'},{'AttributeName':'sk','KeyType':'RANGE'}],
   'GlobalSecondaryIndexes':[{'IndexName':'work','KeySchema':[{'AttributeName':'work','KeyType':'HASH'},{'AttributeName':'position','KeyType':'RANGE'}], 'Projection':{'ProjectionType':'ALL'}}],
   'SSESpecification':{'SSEEnabled':True},'PointInTimeRecoverySpecification':{'PointInTimeRecoveryEnabled':True},'Tags':tags}},
 'Role':{'Type':'AWS::IAM::Role','Properties':{
   'AssumeRolePolicyDocument':{'Version':'2012-10-17','Statement':[{'Effect':'Allow','Principal':{'Service':'ec2.amazonaws.com'},'Action':'sts:AssumeRole'}]},
   'ManagedPolicyArns':['arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore'],
   'Policies':[{'PolicyName':'IsolatedDocuments','PolicyDocument':{'Version':'2012-10-17','Statement':[
     {'Effect':'Allow','Action':['dynamodb:ConditionCheckItem','dynamodb:GetItem','dynamodb:PutItem','dynamodb:UpdateItem','dynamodb:DeleteItem','dynamodb:Query','dynamodb:TransactWriteItems','dynamodb:TransactGetItems','dynamodb:BatchGetItem','dynamodb:BatchWriteItem','dynamodb:DescribeTable'],
      'Resource':[get('Documents','Arn'),sub('${Documents.Arn}/index/*')]},
     {'Effect':'Allow','Action':['s3:GetObject'],'Resource':sub('${Artifacts.Arn}/releases/*')},
     {'Effect':'Allow','Action':['s3:PutObject'],'Resource':sub('${Artifacts.Arn}/reports/*')}
   ]}}],'Tags':tags}},
 'Profile':{'Type':'AWS::IAM::InstanceProfile','Properties':{'Roles':[ref('Role')]}},
 'SecurityGroup':{'Type':'AWS::EC2::SecurityGroup','Properties':{'GroupDescription':'No inbound ports; authenticated SSM access only','VpcId':vpc,'SecurityGroupIngress':[],'Tags':tags}},
 'Host':{'Type':'AWS::EC2::Instance','Properties':{'ImageId':ami,'InstanceType':'c7i.large',
   'IamInstanceProfile':ref('Profile'),'SubnetId':subnet,'SecurityGroupIds':[ref('SecurityGroup')],
   'MetadataOptions':{'HttpTokens':'required','HttpEndpoint':'enabled'},
   'BlockDeviceMappings':[{'DeviceName':'/dev/xvda','Ebs':{'Encrypted':True,'VolumeSize':30,'VolumeType':'gp3','DeleteOnTermination':True}}],
   'Tags':tags+[{'Key':'Name','Value':STACK}],
   'UserData':{'Fn::Base64':'#!/bin/bash\nset -euo pipefail\ndnf install -y java-25-amazon-corretto-devel\nuseradd --system --home-dir /opt/myos-better myos-better\ninstall -d -o myos-better -g myos-better /opt/myos-better /var/lib/myos-better\n'}}}
 },'Outputs':{'Instance':{'Value':ref('Host')},'Table':{'Value':ref('Documents')},'Artifacts':{'Value':ref('Artifacts')}}}
(root/'infra/stack.json').write_text(json.dumps(template,indent=2)+'\n')
print(json.dumps({'account':verify()['account'],'region':verify()['region'],'stack':STACK,'instanceType':'c7i.large','maxHosts':1,'inboundPorts':0,'dataStore':'DynamoDB','expiryPolicy':'Retained for user; review 2026-11-12, explicit cleanup script'}))
