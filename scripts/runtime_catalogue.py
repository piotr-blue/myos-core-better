"""Verify the deployed profile's manifest and immutable carrier inventory before upload."""
import hashlib
import json
from pathlib import Path


def verify(distribution, lock):
    distribution = Path(distribution).resolve()
    lock = json.loads(Path(lock).read_text())
    manifest = (distribution / lock['manifest']).resolve()
    manifest.relative_to(distribution)
    if hashlib.sha256(manifest.read_bytes()).hexdigest() != lock['manifestSha256']:
        raise ValueError('Runtime manifest differs from the pinned deployed profile')
    inputs = json.loads(manifest.read_text())
    if {entry['archive'] for entry in inputs['packages']} != set(lock['files']):
        raise ValueError('Runtime package inventory differs')
    for name, digest in lock['files'].items():
        package = (manifest.parent / name).resolve()
        package.relative_to(manifest.parent)
        if hashlib.sha256(package.read_bytes()).hexdigest() != digest:
            raise ValueError('Runtime package carrier differs: ' + name)
