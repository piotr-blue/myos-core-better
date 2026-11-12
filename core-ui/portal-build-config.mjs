export function portalBuildConfig(env = process.env) {
  const base = env.CORE_UI_BASE || '/';
  const api = env.CORE_UI_API_ORIGIN || '';
  const web = env.CORE_UI_PORTAL_ORIGIN || '';
  if (!/^\/(?:[a-z0-9-]+\/)*$/.test(base)) throw new Error('CORE_UI_BASE must be an absolute directory path');
  for (const value of [api, web].filter(Boolean)) {
    const url = new URL(value);
    const localHttp = env.MYOS_RELEASE !== '1' && url.protocol === 'http:' && url.hostname.endsWith('.localhost');
    if (url.origin !== value || (url.protocol !== 'https:' && !localHttp)) throw new Error('Portal and API origins must be exact HTTPS or local HTTP origins');
  }
  if (Boolean(api) !== Boolean(web)) throw new Error('Configure both shared portal and API origins');
  const mcp = env.CORE_UI_EXTERNAL_AGENT_MCP_URL;
  if (mcp) {
    const url = new URL(mcp);
    if (url.protocol !== 'https:' || url.username || url.password || url.search || url.hash || url.pathname !== '/mcp' || url.href !== mcp)
      throw new Error('External Agent MCP URL must be an exact HTTPS /mcp endpoint without credentials');
  }
  const mcpClientId = env.CORE_UI_EXTERNAL_AGENT_OAUTH_CLIENT_ID;
  if (mcpClientId && (!mcp || !/^[A-Za-z0-9_.-]{1,100}$/.test(mcpClientId)))
    throw new Error('External Agent OAuth client ID requires an MCP endpoint and a registered client identifier');
  const dotsCimd = env.CORE_UI_EXTERNAL_AGENT_DOTS_CIMD;
  if (dotsCimd && (!mcp || !['0', '1'].includes(dotsCimd)))
    throw new Error('Dots CIMD requires an MCP endpoint and an explicit 0 or 1');
  return { base, api, web, ...(dotsCimd ? { dotsCimd: dotsCimd === '1' } : {}), ...(mcp ? { mcp } : {}), ...(mcpClientId ? { mcpClientId } : {}) };
}
