#!/usr/bin/env python3
"""Deploy a built distribution to the isolated stack; verifies account and artifact digest."""
import argparse,gzip,hashlib,json,tarfile,re
from pathlib import Path
from aws import aws,verify,STACK
import deployment_runtime

parser=argparse.ArgumentParser()
parser.add_argument('--console', action='store_true', help='Serve the isolated authenticated HTTPS console')
parser.add_argument('--stack', default=STACK, help='Explicit isolated Better infrastructure stack')
parser.add_argument('--console-stack', default='myos-better-console-20261105')
parser.add_argument('--provider-manifest', default='infra/hosted-provider.json')
parser.add_argument('--webhook-manifest', default='infra/webhook-secret.json')
parser.add_argument('--without-webhooks', action='store_true', help='Use only for a fresh service with no existing callback custody')
parser.add_argument('--reader-prefix', default='myos-better-reader-console-v1-')
parser.add_argument('--runtime-profile', choices=deployment_runtime.PROFILES, default='canonical')
parser.add_argument('--independent-owners', action='store_true', help='Use durable native owners for newly created console documents')
parser.add_argument('--public-definitions', action='store_true', help='Enable shared public catalogue writes only after a reader-compatible release is healthy')
parser.add_argument('--projections',choices=['eager','bounded'],default='bounded')
parser.add_argument('--workers',type=int,default=2)
parser.add_argument('--session-entries',type=int,default=64)
parser.add_argument('--session-mib',type=int,default=128)
parser.add_argument('--session-uses',type=int,default=32)
args=parser.parse_args()
if not re.fullmatch(r'myos-better-(?:canonical-)?[0-9]{8}', args.stack):
    parser.error('Deployment must target an explicitly isolated Better stack')
if not re.fullmatch(r'myos-better-(?:canonical-)?console-[0-9]{8}', args.console_stack):
    parser.error('Deployment must target an explicitly isolated Better console stack')
if not re.fullmatch(r'myos-better-reader-[a-z0-9-]{1,64}-', args.reader_prefix):
    parser.error('Invalid reader custody prefix')
if args.runtime_profile in ('console-native', 'console-deployed') and not args.console: parser.error('Console runtime profiles require --console')
if args.independent_owners and args.runtime_profile not in ('console-native', 'console-deployed'): parser.error('Independent owners require a console-native or console-deployed runtime profile')
if not 1 <= args.workers <= 32: parser.error('Worker count must be 1..32')
if not (1 <= args.session_entries <= 10000 and 1 <= args.session_mib <= 1024 and 1 <= args.session_uses <= 1024):
    parser.error('Invalid session cache bounds for the 2 GiB deployment heap')
root=Path(__file__).resolve().parents[1]
runtime_settings=deployment_runtime.settings(args.runtime_profile)
manifest=runtime_settings['manifest']
if manifest is not None and not (root/'build/install/myos-core-better'/manifest).is_file():
    raise RuntimeError('Selected runtime manifest is missing from the built distribution')
if args.runtime_profile == 'console-deployed':
    import runtime_catalogue
    runtime_catalogue.verify(root/'build/install/myos-core-better', root/'infra/runtime-catalogue.lock.json')
print(json.dumps(verify()))
outputs={x['OutputKey']:x['OutputValue'] for x in aws('cloudformation','describe-stacks','--stack-name',args.stack)['Stacks'][0]['Outputs']}
console_settings={}
console_environment=''
console_check='true'
provider_settings={}
webhook_settings={}
if args.console:
    import console_build
    console_build.verify(root/'core-ui',root/'build/install/myos-core-better/web')
    console_stack=aws('cloudformation','describe-stacks','--stack-name',args.console_stack)['Stacks'][0]
    if console_stack['StackStatus'] not in {'CREATE_COMPLETE','UPDATE_COMPLETE'}:
        raise RuntimeError('Console stack is not ready')
    console_settings={x['OutputKey']:x['OutputValue'] for x in console_stack['Outputs']}
    if not re.fullmatch(r'https://[a-z0-9]+\.cloudfront\.net',console_settings['Url']):raise RuntimeError('Unexpected console origin')
    if not re.fullmatch(r'eu-west-1_[A-Za-z0-9]+',console_settings['UserPool']):raise RuntimeError('Unexpected console pool')
    if not re.fullmatch(r'[a-z0-9]+',console_settings['UserClient']):raise RuntimeError('Unexpected console client')
    console_environment=('Environment=CONSOLE_ORIGIN='+console_settings['Url']+'\n'
        +'Environment=CONSOLE_USER_POOL='+console_settings['UserPool']+'\n'
        +'Environment=CONSOLE_USER_CLIENT='+console_settings['UserClient']+'\n'
        +'Environment=CONSOLE_WEB=/opt/myos-better/current/web\n')
    console_check='curl --fail --silent http://127.0.0.1:8088/ >/dev/null'
    provider_file = root/args.provider_manifest
    if provider_file.exists():
        import provider_infra
        provider_settings=provider_infra.validate(json.loads(provider_file.read_text()),console_settings['Url'])
        console_environment += provider_infra.environment(provider_settings,console_settings['Url'])
    webhook_file = root/args.webhook_manifest
    if webhook_file.exists() and not args.without_webhooks:
        import webhook_infra
        webhook_settings=webhook_infra.validate(json.loads(webhook_file.read_text()))
        console_environment += webhook_infra.environment(webhook_settings)


reports=root/'reports';reports.mkdir(exist_ok=True)
archive=reports/'myos-core-better.tar.gz'
def metadata(member):
    member.uid=member.gid=0
    member.uname=member.gname=''
    member.mtime=0
    member.mode = 0o755 if member.isdir() or member.mode & 0o111 else 0o644
    return member
with archive.open('wb') as raw, gzip.GzipFile(filename='',mode='wb',fileobj=raw,mtime=0) as compressed:
    with tarfile.open(fileobj=compressed,mode='w') as tar:
        tar.add(root/'build/install/myos-core-better',arcname='myos-core-better',filter=metadata)
digest=hashlib.sha256(archive.read_bytes()).hexdigest()
key='releases/'+digest+'.tar.gz'
aws('s3api','put-object','--bucket',outputs['Artifacts'],'--key',key,'--body',str(archive))
unit='''[Unit]
Description=MyOS canonical document processing
After=network-online.target
Wants=network-online.target
[Service]
User=myos-better
Group=myos-better
WorkingDirectory=/opt/myos-better/current
ExecStart=/opt/myos-better/current/bin/myos-core-better serve
Environment=AWS_REGION=eu-west-1
Environment=MYOS_TABLE='''+outputs['Table']+'''
'''+deployment_runtime.environment(args.runtime_profile, reader_prefix=args.reader_prefix)+console_environment+'''Environment=PORT=8080
Environment=MYOS_COMBINE_ACKS=true
Environment=MYOS_PUBLIC_DEFINITIONS='''+str(args.public_definitions).lower()+'''
Environment=MYOS_OWNERS_FAST='''+str(args.independent_owners).lower()+'''
Environment=MYOS_PROJECTIONS='''+args.projections+'''
Environment=MYOS_WORKERS='''+str(args.workers)+'''
Environment=MYOS_SESSION_ENTRIES='''+str(args.session_entries)+'''
Environment=MYOS_SESSION_MIB='''+str(args.session_mib)+'''
Environment=MYOS_SESSION_USES='''+str(args.session_uses)+'''
Environment="JAVA_OPTS=-Xms256m -Xmx2g -XX:+ExitOnOutOfMemoryError"
Restart=on-failure
RestartSec=3
TimeoutStopSec=45
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true
ReadWritePaths=/var/lib/myos-better
MemoryMax=3G
TasksMax=1024
[Install]
WantedBy=multi-user.target
'''
rollback = '''rollback() {
  result=$?
  trap - EXIT
  if [ "$result" -ne 0 ] && [ -n "$previous" ] && [ -d "$previous" ]; then
    set +e
    systemctl stop myos-better.service
    ln -sfn "$previous" /opt/myos-better/current
    if [ -f /tmp/myos-better-previous.service ]; then cp /tmp/myos-better-previous.service /etc/systemd/system/myos-better.service; fi
    systemctl daemon-reload
    systemctl restart myos-better.service
    for i in $(seq 1 180); do if systemctl is-active --quiet myos-better.service && curl --fail --silent http://127.0.0.1:8080/health; then break; fi; sleep 1; done
  fi
  exit "$result"
}
trap rollback EXIT'''
commands=[
 'set -euo pipefail',
 'exec 9>/var/lock/myos-better-deploy.lock; flock -n 9',
 ('if grep -q ^Environment=MYOS_PROVIDER_ID= /etc/systemd/system/myos-better.service 2>/dev/null; then echo "Existing provider requires infra/hosted-provider.json and --console" >&2; exit 1; fi' if not provider_settings else 'true'),
 ('if grep -q ^Environment=MYOS_WEBHOOK_SECRET_ARN= /etc/systemd/system/myos-better.service 2>/dev/null; then echo "Existing callbacks require infra/webhook-secret.json and --console" >&2; exit 1; fi' if not webhook_settings else 'true'),
 'java -version',
 'install -d /opt/myos-better/releases',
 f'aws s3 cp s3://{outputs["Artifacts"]}/{key} /tmp/myos-better-release.tar.gz --region eu-west-1 --only-show-errors',
 f'echo "{digest}  /tmp/myos-better-release.tar.gz" | sha256sum --check --status',
 'staging=$(mktemp -d /opt/myos-better/releases/.staging-XXXXXX)',
 """trap 'rm -rf -- "$staging"' EXIT""",
 'tar -xzf /tmp/myos-better-release.tar.gz -C "$staging" --strip-components=1',
 'chown -R root:root "$staging"',
 'chmod 755 "$staging"',
 f'if [ -d /opt/myos-better/releases/{digest} ]; then diff -qr --no-dereference "$staging" /opt/myos-better/releases/{digest}; rm -rf -- "$staging"; else mv "$staging" /opt/myos-better/releases/{digest}; fi',
 f'chmod 755 /opt/myos-better/releases/{digest}',
 f'sudo -u myos-better test -x /opt/myos-better/releases/{digest}/bin/myos-core-better',
 'trap - EXIT',
 'previous=$(readlink -f /opt/myos-better/current || true); if [ -L /opt/myos-better/healthy ]; then previous=$(readlink -f /opt/myos-better/healthy); fi',
 'if [ -f /opt/myos-better/healthy.service ]; then cp /opt/myos-better/healthy.service /tmp/myos-better-previous.service; elif [ -f /etc/systemd/system/myos-better.service ]; then cp /etc/systemd/system/myos-better.service /tmp/myos-better-previous.service; fi',
 rollback,
 'if systemctl cat myos-better.service >/dev/null 2>&1; then systemctl stop myos-better.service; fi',
 f'ln -sfn /opt/myos-better/releases/{digest} /opt/myos-better/current',
 "cat > /etc/systemd/system/myos-better.service <<'MYOS_SERVICE'\n"+unit+'MYOS_SERVICE',
 'systemctl daemon-reload',
 'systemctl enable --now myos-better.service',
 f'for i in $(seq 1 180); do if systemctl is-active --quiet myos-better.service && {deployment_runtime.health_check(args.runtime_profile)} && curl --fail --silent --max-time 20 http://127.0.0.1:8080/ready >/dev/null && {console_check}; then ln -sfn /opt/myos-better/releases/{digest} /opt/myos-better/healthy; cp /etc/systemd/system/myos-better.service /opt/myos-better/healthy.service; exit 0; fi; sleep 1; done',
 'journalctl -u myos-better.service -n 60 --no-pager',
 'exit 1'
]
request={'DocumentName':'AWS-RunShellScript','InstanceIds':[outputs['Instance']],
         'Parameters':{'commands':commands,'executionTimeout':['300']},'Comment':'Install verified isolated MyOS better release'}
requestfile=reports/'deploy-request.json';requestfile.write_text(json.dumps(request))
command=aws('ssm','send-command','--cli-input-json','file://'+str(requestfile))['Command']['CommandId']
result={'account':verify()['account'],'region':'eu-west-1','stack':args.stack,'instance':outputs['Instance'],'table':outputs['Table'],
        'bucket':outputs['Artifacts'],'artifactSha256':digest,'commandId':command,'projections':args.projections,'workers':args.workers,'sessionEntries':args.session_entries,'sessionMiB':args.session_mib,'sessionUses':args.session_uses, 'combinedAcknowledgements':True, 'runtimeProfile':args.runtime_profile, 'edition':runtime_settings['edition'], 'supportedEditions':runtime_settings['supportedEditions'], 'console':console_settings, 'hostedProvider':provider_settings,'webhookCustody':webhook_settings}
result['independentOwners'] = args.independent_owners
result['publicDefinitions'] = args.public_definitions
result['ownerGasSchedule'] = 'STANDARD'
result['newDocumentEdition'] = 'myos.canonical-owners/2' if args.independent_owners else runtime_settings['edition']
(reports/'deployment.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result))
