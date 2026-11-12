import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { mkdtemp, mkdir, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { prepareSchemas } from './prepare-schemas.mjs';

const root = fileURLToPath(new URL('../', import.meta.url));
const repo = path.resolve(root, '../..');
const check = process.argv.slice(2).includes('--check');
if (process.argv.slice(2).some(arg => arg !== '--check')) throw new Error('Usage: generate.mjs [--check]');
const sha256 = value => createHash('sha256').update(value).digest('hex');
const configBytes = await readFile(path.join(root, 'generator.json'));
const config = JSON.parse(configBytes);
const sourceBytes = await readFile(path.join(repo, 'core-ui/public/openapi.json'));
const source = JSON.parse(sourceBytes);
const cache = path.join(repo, 'reports/sdk-generator');
await mkdir(cache, { recursive: true });
const jar = path.join(cache, `openapi-generator-cli-${config.version}.jar`);
let jarBytes;
try { jarBytes = await readFile(jar); } catch (error) {
  if (error.code !== 'ENOENT') throw error;
  const url = `https://repo.maven.apache.org/maven2/org/openapitools/openapi-generator-cli/${config.version}/openapi-generator-cli-${config.version}.jar`;
  const response = await fetch(url, { signal: AbortSignal.timeout(120_000) });
  if (!response.ok) throw new Error(`Generator download failed: HTTP ${response.status}`);
  jarBytes = Buffer.from(await response.arrayBuffer());
  if (sha256(jarBytes) !== config.sha256) throw new Error('Generator download checksum mismatch');
  await writeFile(jar, jarBytes);
}
if (sha256(jarBytes) !== config.sha256) throw new Error('Cached generator checksum mismatch');

async function files(directory, prefix = '') {
  const result = new Map();
  for (const item of await readdir(directory, { withFileTypes: true })) {
    const name = path.posix.join(prefix, item.name);
    if (item.isDirectory()) {
      for (const entry of await files(path.join(directory, item.name), name)) result.set(...entry);
    } else result.set(name, await readFile(path.join(directory, item.name), 'utf8'));
  }
  return result;
}

const temporary = await mkdtemp(path.join(tmpdir(), 'myos-sdk-'));
try {
  const input = path.join(temporary, 'openapi.json');
  const output = path.join(temporary, 'generated');
  const configFile = path.join(temporary, 'config.json');
  await writeFile(input, JSON.stringify(prepareSchemas(source)));
  await writeFile(configFile, JSON.stringify({
    additionalProperties: config.additionalProperties, nameMappings: config.nameMappings,
    typeMappings: config.typeMappings,
  }));
  const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin/java') : 'java';
  try {
    const log = execFileSync(java, ['-jar', jar, 'generate', '-i', input,
      '-g', config.generatorName, '-o', output, '-c', configFile,
      '--global-property', 'apiDocs=false,modelDocs=false,apiTests=false,modelTests=false'],
    { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024, stdio: ['ignore', 'pipe', 'pipe'] });
    await writeFile(path.join(cache, 'generation.log'), log);
  } catch (error) {
    process.stderr.write(error.stdout ?? '');
    process.stderr.write(error.stderr ?? '');
    throw error;
  }
  // Only source files belong in the SDK; tool metadata includes machine-local paths.
  const expected = new Map([...await files(output)]
    .filter(([name]) => name.endsWith('.ts'))
    .map(([name, content]) => [name, content.replace(/[\t ]+$/gm, '').trimEnd() + '\n']));
  const provenance = JSON.stringify({
    source: 'core-ui/public/openapi.json',
    sourceSha256: sha256(sourceBytes),
    generatorVersion: config.version,
    generatorSha256: config.sha256,
    configSha256: sha256(configBytes),
    generationScriptSha256: sha256(await readFile(fileURLToPath(import.meta.url))),
    schemaPreparationSha256: sha256(await readFile(new URL('./prepare-schemas.mjs', import.meta.url))),
  }, null, 2) + '\n';
  const destination = path.join(root, 'generated');
  if (check) {
    const actual = await files(destination);
    const changed = [...new Set([...expected.keys(), ...actual.keys()])]
      .filter(name => expected.get(name) !== actual.get(name));
    if (await readFile(path.join(root, 'generation.json'), 'utf8') !== provenance) changed.push('../generation.json');
    if (changed.length) throw new Error(`Generated SDK is stale; run npm run generate:\n${changed.join('\n')}`);
    console.log(`PASS: ${expected.size} generated files reproduce from the committed Core Swagger`);
  } else {
    await rm(destination, { recursive: true, force: true });
    for (const [name, content] of expected) {
      await mkdir(path.dirname(path.join(destination, name)), { recursive: true });
      await writeFile(path.join(destination, name), content);
    }
    await writeFile(path.join(root, 'generation.json'), provenance);
    console.log(`Generated ${expected.size} source files from the Core Swagger`);
  }
} finally {
  await rm(temporary, { recursive: true, force: true });
}
