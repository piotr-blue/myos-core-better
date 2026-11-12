/** Generated SDK owns Core authentication headers, routes and response decoding. */
import { isDeepStrictEqual } from 'node:util';
import { AccountApi, Configuration } from '../sdk/typescript/dist/index.js';

try {
  let input = '';
  for await (const chunk of process.stdin) {
    input += chunk;
    if (Buffer.byteLength(input) > 128 * 1024) throw new Error('Input bound exceeded');
  }
  const { origin, tokens, runtime, registrationSelection } = JSON.parse(input);
  const url = new URL(origin);
  if (url.protocol !== 'https:' || url.origin !== origin || tokens.length !== 2) throw new Error('Invalid probe target');
  const accounts = new Set();
  for (const token of tokens) {
    const api = new AccountApi(new Configuration({ basePath: origin, accessToken: token }));
    const account = await api.getAccount({}, { signal: AbortSignal.timeout(20_000) });
    if (!account.accountId || !account.capabilities.manageAccount || accounts.has(account.accountId)) {
      throw new Error('Qualification requires distinct current Admin Accounts');
    }
    accounts.add(account.accountId);
    const response = await api.getInfoRaw({}, { signal: AbortSignal.timeout(20_000) });
    const info = await response.value();
    if (response.raw.headers.get('X-MyOS-Read-Source') !== 'projection'
        || !isDeepStrictEqual(info.runtime, runtime)
        || !isDeepStrictEqual(info.registrationExecutionSelections, [registrationSelection])) {
      throw new Error('Projection, current runtime or exact registration selection differs');
    }
  }
  process.stdout.write(JSON.stringify({ status: 'passed', accounts: 2,
    projectedCurrentRuntime: true, exactRegistrationSelection: true }));
} catch {
  // Never emit tokens, account metadata, error bodies or SDK request diagnostics.
  process.stdout.write(JSON.stringify({ status: 'failed' }));
  process.exitCode = 1;
}
