import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('console_build', Path(__file__).with_name('console_build.py'))
build = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build)


class ConsoleBuildTest(unittest.TestCase):
    def test_transitive_sources_and_assets_are_bound_without_local_dependency_outputs(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            console = root / 'core-ui'
            paths = ['core-ui/package.json', 'core-ui/package-lock.json', 'core-ui/tsconfig.json',
                     'core-ui/vite.config.ts', 'core-ui/index.html', 'core-ui/build.mjs',
                     'core-ui/src/main.tsx', 'core-ui/observability/browser.mjs',
                     'sdk/typescript/generated/index.ts', 'sdk/typescript/node_modules/ignore.js',
                     'sdk/typescript/dist/ignore.js', 'account-ui/account.tgz',
                     'packages/current.json', 'runtime/blue-core-service/src/main/resources/package-catalog/access-events-defaults.json']
            for name in paths:
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(name)
            dist = console / 'dist'
            dist.mkdir()
            (dist / 'index.html').write_text('built')
            manifest = {'inputs': build.inputs(console), 'assets': {
                'index.html': build.hashlib.sha256(b'built').hexdigest()}}
            (dist / 'build-manifest.json').write_text(json.dumps(manifest))
            build.verify(console, dist)
            self.assertFalse(any('/node_modules/' in name or '/dist/' in name for name in manifest['inputs']))
            for name in ['core-ui/build.mjs', 'sdk/typescript/generated/index.ts', 'account-ui/account.tgz', 'packages/current.json']:
                path = root / name
                path.write_text('changed')
                with self.assertRaisesRegex(ValueError, 'source changed'):
                    build.verify(console, dist)
                path.write_text(name)
            (dist / 'unexpected.js').write_text('extra')
            with self.assertRaisesRegex(ValueError, 'Unexpected'):
                build.verify(console, dist)


if __name__ == '__main__':
    unittest.main()
