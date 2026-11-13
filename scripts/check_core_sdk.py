"""Run the unmodified generated SDK against a bounded, isolated loopback server."""
import os
from pathlib import Path
import select
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    java_home = os.environ.get('JAVA_HOME')
    java = str(Path(java_home) / 'bin/java') if java_home else 'java'
    jars = sorted((ROOT / 'build/libs').glob('*-qualification.jar'))
    if len(jars) != 1:
        raise RuntimeError('Run ./gradlew qualificationJar installDist first')
    classpath = str(jars[0]) + os.pathsep + str(ROOT / 'build/install/myos-core-better/lib/*')
    mailbox = tempfile.TemporaryDirectory(prefix='myos-sdk-mailbox-')
    environment = dict(os.environ, MYOS_TEST_INVITATION_OUTBOX=mailbox.name)
    server = subprocess.Popen([java, '-cp', classpath, 'myos.better.DashboardSdkProbe'],
                              cwd=ROOT, env=environment, stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
    try:
        if not select.select([server.stdout], [], [], 20)[0]:
            raise RuntimeError('SDK fixture did not start within 20 seconds')
        url = server.stdout.readline().strip()
        fixture = server.stdout.readline().strip()
        if not url or not fixture:
            raise RuntimeError(f'SDK fixture exited before publishing its loopback address and import data (exit {server.poll()})')
        subprocess.run(['node', 'scripts/test-core-sdk.mjs', url, fixture], cwd=ROOT, env=environment, check=True, timeout=30)
    finally:
        server.stdin.close()
        try:
            server.wait(timeout=10)
        except subprocess.TimeoutExpired:
            server.terminate()
            server.wait(timeout=10)
        server.stdout.close()
        mailbox.cleanup()


if __name__ == '__main__':
    main()
