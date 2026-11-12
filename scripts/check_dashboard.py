"""Exercise the unchanged dashboard in a browser against an isolated native host."""
import argparse
import os
from pathlib import Path
import select
import shutil
import socket
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--journey', choices=['dashboard', 'agents', 'webhooks', 'keys', 'members', 'imports', 'timeline-operation', 'invitations', 'packages'], default='dashboard')
    args = parser.parse_args()
    script = 'scripts/inspect-' + args.journey + '.mjs'
    java_home = os.environ.get('JAVA_HOME')
    java = str(Path(java_home) / 'bin/java') if java_home else 'java'
    with tempfile.TemporaryDirectory(prefix='myos-dashboard-browser-') as temporary:
        frozen = Path(temporary)
        jars = [*sorted((ROOT / 'build/install/myos-core-better/lib').glob('*.jar')),
                *sorted((ROOT / 'build/libs').glob('*-qualification.jar'))]
        if not any(jar.name.endswith('-qualification.jar') for jar in jars):
            raise RuntimeError('Run qualificationJar installDist before this check')
        for jar in jars:
            shutil.copy2(jar, frozen / jar.name)
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0))
            port = listener.getsockname()[1]
        environment = dict(os.environ)
        if args.journey == 'invitations':
            mailbox = frozen / 'mailbox'
            mailbox.mkdir(mode=0o700)
            environment['MYOS_TEST_INVITATION_OUTBOX'] = str(mailbox)
        server = subprocess.Popen([java, '-cp', str(frozen / '*'),
                                   'myos.better.DashboardSdkProbe', str(port)], cwd=ROOT, env=environment,
                                  stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
        try:
            if not select.select([server.stdout], [], [], 20)[0]:
                raise RuntimeError('Dashboard fixture did not start within 20 seconds')
            ready = server.stdout.readline().strip()
            fixture = server.stdout.readline().strip()
            if ready != f'http://127.0.0.1:{port}':
                raise RuntimeError('Dashboard fixture failed to start')
            subprocess.run(['node', script, f'http://localhost:{port}', fixture],
                           cwd=ROOT, env=environment, check=True, timeout=60)
        finally:
            server.stdin.close()
            try:
                server.wait(timeout=10)
            except subprocess.TimeoutExpired:
                server.terminate()
                server.wait(timeout=10)
            server.stdout.close()
            if server.returncode:
                raise RuntimeError(f'Dashboard fixture exited {server.returncode}')


if __name__ == '__main__':
    main()
