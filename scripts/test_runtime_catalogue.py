import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from runtime_catalogue import verify


class RuntimeCatalogueTest(unittest.TestCase):
    def test_rejects_changed_manifest_carrier_or_inventory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            package = root/'package.bluepkg'
            package.write_bytes(b'pinned carrier')
            manifest = root/'runtime.json'
            manifest.write_text(json.dumps({'packages': [{'archive': package.name}]}))
            lock = root/'lock.json'
            inputs = {'manifest': manifest.name, 'manifestSha256': hashlib.sha256(manifest.read_bytes()).hexdigest(),
                      'files': {package.name: hashlib.sha256(package.read_bytes()).hexdigest()}}
            lock.write_text(json.dumps(inputs))
            verify(root, lock)
            package.write_bytes(b'changed carrier')
            with self.assertRaises(ValueError): verify(root, lock)
            package.write_bytes(b'pinned carrier')
            inputs['files']['extra.bluepkg'] = '0'*64
            lock.write_text(json.dumps(inputs))
            with self.assertRaises(ValueError): verify(root, lock)
            manifest.write_text('{}')
            with self.assertRaises(ValueError): verify(root, lock)
