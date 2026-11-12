"""Bind the console distribution to its exact authored inputs and assets."""
import hashlib,json
from pathlib import Path

def inputs(console):
    paths = {console/name for name in ('package.json','package-lock.json','tsconfig.json','vite.config.ts','index.html')}
    paths.update(console.glob('*.mjs'))
    for folder in ('src','public','observability'):
        paths.update(p for p in (console/folder).rglob('*') if p.is_file())
    # The reused dashboard compiles the generated SDK and shared account UI too.
    root=console.parent
    for folder in ('sdk/typescript','account-ui'):
        paths.update(p for p in (root/folder).rglob('*') if p.is_file()
                     and not any(part in {'node_modules','dist','.git'} for part in p.relative_to(root/folder).parts))
    for name in ('packages/current.json','runtime/blue-core-service/src/main/resources/package-catalog/access-events-defaults.json'):
        path=root/name
        if path.is_file():paths.add(path)
    import os
    return {os.path.relpath(p,console):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(paths)}

def verify(console, installed):
    expected=inputs(console)
    recorded=json.loads((installed/'build-manifest.json').read_text())
    if recorded['inputs']!=expected:raise ValueError('Console source changed after its build')
    for name,digest in recorded['assets'].items():
        p=installed/name
        if not p.is_file() or hashlib.sha256(p.read_bytes()).hexdigest()!=digest:raise ValueError('Console asset changed: '+name)
    if set(recorded['assets'])!={str(p.relative_to(installed)) for p in installed.rglob('*') if p.is_file() and p.name!='build-manifest.json'}:raise ValueError('Unexpected console asset inventory')

if __name__=='__main__':
    console=Path(__file__).resolve().parents[1]/'core-ui'
    dist=console/'dist'
    assets={str(p.relative_to(dist)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(dist.rglob('*')) if p.is_file() and p.name!='build-manifest.json'}
    if 'index.html' not in assets:raise ValueError('Build the console first')
    (dist/'build-manifest.json').write_text(json.dumps({'inputs':inputs(console),'assets':assets},indent=2)+'\n')
    verify(console,dist)
    print('Console source and asset inventory verified')
