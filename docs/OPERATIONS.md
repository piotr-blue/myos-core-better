# Operating the canonical preview

The current preview is https://d2buikczdox1rt.cloudfront.net, on the isolated
`myos-better-canonical-20261108` stack in account `581571886327`, region `eu-west-1`.
The earlier November 5 deployment is separate. Do not use its instance, table or console settings
for this application. Read `reports/deployment.json` for the last submitted artifact and SSM command;
verify that command succeeded before treating it as the running release.

## Runtime and storage

An EC2/JDK 25 process serves the authenticated console and runs document workers. DynamoDB holds
immutable document records, histories, source frontiers and conditional publications. S3 holds
release artifacts and qualification outputs. CloudFront serves the preview origin; Cognito provides
sign-in. Existing provider, reader and webhook signing custody remains in Secrets Manager. The
application uses the instance role. Developer credentials are never placed in a deployment bundle.

Authored documents use the pinned Blue packages. `console-native` selects the supported capabilities
needed by the console. `MYOS_OWNERS_FAST=true` independently schedules canonical owners; it is a
host-storage choice, not another document language or gas policy. New histories use
`contracts-semantic-gas`. Incompatible experimental histories need explicit replay.

Static owner updates restore the causal members and consume historical child results. Structural
changes still have a conservative graph-wide fallback. Established cyclic-unit migration and
production high availability remain incomplete. See the [current qualification report](polish-2026-11-10/REPORT.md)
for precise correctness, measurement and deployment boundaries.

## Credentials and local operator access

Use an authorized AWS profile, or select the existing private properties file explicitly:

```sh
export MYOS_AWS_PROPERTIES=/absolute/path/to/private/aws-access.properties
python3 scripts/aws.py
python3 scripts/tunnel.py
```

The scripts verify account and region. The tunnel uses the official AWS Session Manager plugin;
no inbound operator port is opened. In another terminal:

```sh
curl --fail http://127.0.0.1:8080/health
```

The operator endpoint is separate from the authenticated public console. Avoid logging access tokens,
passwords, API keys or private signing material. The private properties file is not part of this repo.

## Build a reviewable release

Use JDK 25 and the pinned sibling library and package publications. Run commands sequentially because
the console build also rebuilds the SDK output:

```sh
./gradlew build qualificationJar
(cd sdk/typescript && npm ci && npm run check)
(cd core-ui && npm ci && npm run typecheck && npm test && npm run build)
python3 scripts/console_build.py
./gradlew installDist
python3 -m unittest discover -s scripts -p 'test_*.py'
```

`console_build.py` binds installed assets to exact source hashes, including the SDK. Do not copy old
console output into a new release or regenerate its manifest to disguise an unbuilt source change.
Build and tests use local fixtures; they do not deploy or modify AWS.

## Deploy to the current isolated preview

Coordinate exclusive access with any active benchmark first. The compatible update command is:

```sh
python3 scripts/deploy.py --console --runtime-profile console-native --independent-owners \
  --stack myos-better-canonical-20261108 \
  --console-stack myos-better-canonical-console-20261108 \
  --provider-manifest infra/canonical-preview-provider.json \
  --webhook-manifest infra/canonical-preview-webhooks.json \
  --reader-prefix myos-better-reader-canonical-v1- \
  --projections bounded --workers 2 \
  --session-entries 64 --session-mib 128 --session-uses 32
```

The distribution is content-addressed and verified on the instance. Deployment acquires the service
lock, retains the previous healthy release, updates the service and checks health. Failure triggers
rollback to the previous release. A returned command ID means submission, not success. Query the
recorded command with `aws ssm get-command-invocation`, then verify the live health and public login.
These runtime settings describe the current bounded preview; they are not production sizing advice.
The independent-owner path does not retain mutable user-document sessions across attempts.

## Bounded acceptance checks

```sh
python3 scripts/qualify_owner_preview.py --orders 10,100 --samples 3
```

This creates isolated synthetic documents and checks the exact Agreement receipt for every input,
plus Payment, Order and Agreement totals. It uses `MYOS_API_KEY` if supplied; otherwise it provisions
suppressed-email QA identities through authorized AWS access. Do not place credentials in shell
arguments or commit generated QA files. Results are written under ignored `reports/`.

Public source upload is limited to 1 MiB. An expanded 1,000-Order source may exceed that limit;
sequential child creation is a separate growth workload. Never report a rejected bulk upload as
successful initialization, or local in-memory storage timings as DynamoDB/HTTPS performance.

Record artifact hashes, fixture hashes, initialization, first use after restart and later operations
separately. Include signed admission, restore, execution, preparation, storage requests and commit
when explaining end-to-end latency. Preserve failed and timed-out runs in the report. Retain old
histories and release artifacts needed for audit; do not delete them as part of routine deployment.
