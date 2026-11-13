"""Kill and reopen MyOS processes against a loopback-only DynamoDB emulator."""

import argparse
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import tempfile
import time
from urllib.parse import urlsplit
import uuid

ROOT = Path(__file__).resolve().parents[1]


def loopback_endpoint(value):
    parsed = urlsplit(value)
    if (
        parsed.scheme != "http"
        or parsed.hostname != "127.0.0.1"
        or not parsed.port
        or parsed.username is not None
        or parsed.password is not None
        or parsed.query
        or parsed.fragment
        or parsed.path not in ("", "/")
    ):
        raise argparse.ArgumentTypeError("Use http://127.0.0.1:PORT")
    return f"http://127.0.0.1:{parsed.port}"


def snapshot_classpath(classpath, directory):
    """Give restarted JVMs the same code even if a developer rebuilds the project."""
    entries = []
    for index, value in enumerate(classpath.strip().split(os.pathsep)):
        source = Path(value)
        if not source.exists():
            continue  # Gradle includes optional, empty resource directories.
        destination = directory / str(index) / source.name
        destination.parent.mkdir()
        if source.is_dir():
            shutil.copytree(source, destination)
        else:
            shutil.copyfile(source, destination)
        entries.append(str(destination))
    return os.pathsep.join(entries)


def run(endpoint, trials, output, scenario="command"):
    env = dict(
        os.environ,
        AWS_ACCESS_KEY_ID="test",
        AWS_SECRET_ACCESS_KEY="test",
        AWS_REGION="eu-west-1",
        AWS_DEFAULT_REGION="eu-west-1",
        AWS_EC2_METADATA_DISABLED="true",
    )
    for key in ("AWS_SESSION_TOKEN", "AWS_PROFILE", "AWS_DEFAULT_PROFILE"):
        env.pop(key, None)
    subprocess.run([ROOT / "gradlew", "processRecoveryClasspath"], cwd=ROOT, check=True)
    classpath = (ROOT / "build/process-recovery-classpath.txt").read_text()
    cli = ["aws", "--endpoint-url", endpoint, "--region", "eu-west-1", "dynamodb"]
    stack = json.loads((ROOT / "infra/stack.json").read_text())["Resources"]
    schema = next(
        v["Properties"] for v in stack.values() if v["Type"] == "AWS::DynamoDB::Table"
    )
    definition = {
        k: schema[k]
        for k in (
            "AttributeDefinitions",
            "KeySchema",
            "BillingMode",
            "GlobalSecondaryIndexes",
        )
    }
    table = "blue-quality-process-" + uuid.uuid4().hex
    definition["TableName"] = table
    subprocess.run(
        cli + ["create-table", "--cli-input-json", json.dumps(definition)],
        env=env,
        check=True,
        stdout=subprocess.DEVNULL,
    )
    try:
        subprocess.run(
            cli + ["wait", "table-exists", "--table-name", table], env=env, check=True
        )
        rows = []
        with tempfile.TemporaryDirectory() as temp:
            worker = [
                "java",
                "-Xmx256m",
                "-cp",
                snapshot_classpath(classpath, Path(temp)),
                "myos.better.ProcessRecoveryProbe",
            ]
            for trial in range(trials):
                boundary = "BEFORE" if trial % 2 == 0 else "AFTER"
                marker = Path(temp) / f"boundary-{trial}"
                options = [endpoint, table, f"recovery-{trial}", marker, scenario]
                with tempfile.TemporaryFile(mode="w+") as log:
                    process = subprocess.Popen(
                        worker + [boundary, *options], cwd=ROOT, stdout=log, stderr=log
                    )
                    try:
                        deadline = time.monotonic() + 120
                        while not marker.exists():
                            if (
                                process.poll() is not None
                                or time.monotonic() >= deadline
                            ):
                                log.seek(0)
                                raise RuntimeError(
                                    "Worker missed the receipt commit: "
                                    + log.read()[-2000:]
                                )
                            time.sleep(0.05)
                    finally:
                        process.kill()
                        process.wait(timeout=15)
                if process.returncode != -signal.SIGKILL:
                    raise RuntimeError(f"Unexpected writer exit {process.returncode}")
                recovered = subprocess.check_output(
                    worker + ["RECOVER", *options],
                    cwd=ROOT,
                    text=True,
                    timeout=210 if scenario == "owner-init" else 120,
                )
                row = json.loads(recovered.splitlines()[-1])
                if row["boundary"] != boundary:
                    raise AssertionError(row)
                rows.append(dict(row, trial=trial, writerExit=process.returncode))
                print(
                    f"Trial {trial + 1}: {boundary.lower()} commit, recovered {row['afterRetry']}",
                    flush=True,
                )
        result = (
            json.dumps(
                {
                    "fixture": "Loopback DynamoDB emulator with the real MyOS DynamoStore",
                    "scenario": scenario,
                    "trials": rows,
                },
                indent=2,
            )
            + "\n"
        )
        if output:
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_text(result)
        else:
            print(result)
    finally:
        subprocess.run(
            cli + ["delete-table", "--table-name", table],
            env=env,
            check=True,
            stdout=subprocess.DEVNULL,
        )


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--endpoint", required=True, type=loopback_endpoint)
    parser.add_argument(
        "--trials", type=int, choices=range(2, 33), default=8, metavar="2..32"
    )
    parser.add_argument("--output", type=Path)
    parser.add_argument(
        "--scenario", choices=("command", "owner-init"), default="command"
    )
    args = parser.parse_args()
    run(args.endpoint, args.trials, args.output, args.scenario)
