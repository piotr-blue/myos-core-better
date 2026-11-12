# Private Timeline custody on AWS — iteration 133

**Decision: keep the qualification tools.** The exact deployed release 132 passed a real private
Timeline acquisition, native execution and restart check on AWS. This iteration changes qualification
code and documentation only. It does not enable Timeline processing in the ordinary console.

## What ran

`PrivateTimelineServiceProbe` starts the actual `Main` service against DynamoDB with an empty durable
source directory. A loopback HTTPS provider uses the real hosted Timeline publisher, signed reader
requests, access-control BEX and public control Timeline. Provider keys are ephemeral. The receiving
reader's private key is generated in memory and held in a single temporary Secrets Manager secret;
only its ARN and pinned version enter the service configuration and report.

The test adopts the private source and its independent public control source, verifies signed
discovery, registers custody through the operator API, then creates a receiving child in an agreement.
The authored receiving policy explicitly identifies the reader and initial grant. Transport permission
to fetch a message and permission to execute its original occurrence are checked separately.

| Step | Child / agreement count | Verified result |
| --- | ---: | --- |
| Register the pinned secret twice | 0 / 0 | Identical registration is idempotent; another account cannot reuse it |
| Append amount 42 | 42 / 42 | Automatic acquisition, native execution and parent propagation occur once |
| Stop Main, append another 42, start a fresh Main | 84 / 84 | Directory, custody, subscriptions and history restore from DynamoDB; offline arrival runs once |
| Revoke the reader; append amount 99 | 84 / 84 | Private acquisition stops; independent public control acquisition continues |
| Grant current access again | 84 / 84 | Fetching resumes, but the occurrence created during revocation remains `PENDING_ACCESS` |

The receiver ends at version 3. The pending amount 99 is neither consumed nor executed. A later grant
does not retroactively authorize an earlier occurrence. Receiving-policy rebase remains unfinished;
this is an explicit boundary, not a claim that the pending entry will eventually execute.

## Evidence and timing

Successful SSM command: `b5e7c39b-01b4-4074-a49a-42969b8c51b1`.
The test used a separate synthetic account and did not mutate the user's 512-document agreement.
The normal eight-worker service was restored with native admission and Timeline processing disabled.

- Host: `1eab9c53bf85ce89edbd7843ac467e8b270bfc941672045cd294682dd63c3e86`
- Library: `1f096aa4fae9a0a0dc1799d6f1c1dbe8af9999143003ae034cf0d6e3da9ccf71`
- Qualification tool: `3602f66adf96fd22e38db0a8bc846e8235ef07d653fba5db72db7886b5e06054`

The first private entry took **1859.236 ms through the parent** in this cold functional run, including
polling, source verification, durable storage and observation. There was one timing sample and no
warm performance comparison. This does not demonstrate fast private Timeline processing and must not
be substituted for the separate host-links payment measurements. Cold source/replay cost still needs
measurement and optimization before making latency claims for this path.

Local inline private admission/parent preflight passed. The existing public automatic-directory
Main/HTTPS regression passed. All 31 selected qualification-controller tests passed after the final
IAM correction. `check` and the qualification JAR build passed; unchanged production Java tests were
up-to-date, with the 2364 library / 374 host test results retained from iteration 132.

## Bounded permissions and cleanup

The launcher records an exact fresh account, secret name, role policy and SSM command. It validates
the deployed runtime hashes, holds the deployment lock, bounds the subprocess lifetime and restores
the ordinary service in an EXIT trap. Cleanup refuses to act while a command is live or submission
is unresolved. It checks both resource identities and the unchanged policy before deletion.

The first command, `27aed3a4-d72c-4b19-b207-49c656dc58aa`, failed at `CreateSecret`, before native
business execution. Its service restoration and temporary-policy cleanup passed. The corrected
creation permission uses `Resource: "*"` constrained to the exact generated secret name, description
and region. Read/delete permissions use only that name's exact ARN prefix and six-character suffix.
A 15-second IAM propagation wait occurs before stopping the ordinary service. Because both policy
scope and propagation delay changed, this evidence does not isolate which caused the first denial.

AWS policy simulation allowed the expected creation and denied wrong-name, wrong-region and
wrong-description requests. The successful run deleted its temporary secret; terminal controller
cleanup removed the temporary IAM policy. No production provider identity was changed.

The permission choices follow AWS's [Secrets Manager policy examples](https://docs.aws.amazon.com/secretsmanager/latest/userguide/auth-and-access_iam-policies.html)
and [supported condition keys](https://docs.aws.amazon.com/service-authorization/latest/reference/list_secretsmanager.html).
No private key, secret body or account password is written to reports or command lines.

Run explicitly, with the existing isolated deployment available:

```bash
python3 scripts/run_private_custody.py submit
# Use the exact metadata path returned above; do not submit again after a polling timeout.
python3 scripts/run_private_custody.py status reports/private-custody-<id>-command.json
# Only after the same command reaches a terminal state:
python3 scripts/run_private_custody.py cleanup reports/private-custody-<id>-command.json
```

This temporarily stops the ordinary service. It is an operator qualification, not a console action
or an ordinary build test. Source adoption, protected reader provisioning and authored receiver
configuration still need an owner-console workflow. Timeline creation, append, content inspection and
Sharing already work in the console; append still does not automatically invoke a business operation.

Raw results, both command lifecycles, policy simulation, source ledgers and cleanup evidence are in
`reports/overnight-2026-11-05/private-custody-133/`, indexed by `manifest-133.json` in its parent.
