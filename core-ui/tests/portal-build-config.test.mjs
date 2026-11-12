import {test} from 'node:test';
import assert from 'node:assert/strict';
import {portalBuildConfig} from '../portal-build-config.mjs';
test('local HTTP previews remain forbidden in release builds',()=>{
 const env={CORE_UI_API_ORIGIN:'http://core.api.myos.localhost:4554',CORE_UI_PORTAL_ORIGIN:'http://myos.localhost:4553'};
 assert.equal(portalBuildConfig(env).api,env.CORE_UI_API_ORIGIN);
 assert.throws(()=>portalBuildConfig({...env,MYOS_RELEASE:'1'}));
 assert.throws(()=>portalBuildConfig({...env,CORE_UI_API_ORIGIN:'http://core.api.myos.localhost.evil.test'}));
});
test('shared portal builds require paired exact HTTPS origins and a directory base',()=>{
 assert.deepEqual(portalBuildConfig({}),{base:'/',api:'',web:''});
 const config={CORE_UI_BASE:'/developers/app/',CORE_UI_API_ORIGIN:'https://api.developers.test',CORE_UI_PORTAL_ORIGIN:'https://portal.test'};
 assert.deepEqual(portalBuildConfig(config),{base:'/developers/app/',api:'https://api.developers.test',web:'https://portal.test'});
 for(const changed of [{CORE_UI_BASE:'//evil/'},{CORE_UI_API_ORIGIN:'http://api.test'},{CORE_UI_API_ORIGIN:'https://user:password@api.test'},{CORE_UI_API_ORIGIN:'https://api.test/path'},{CORE_UI_PORTAL_ORIGIN:''}]) assert.throws(()=>portalBuildConfig({...config,...changed}));
});
test('external connection instructions use only an explicit HTTPS MCP endpoint',()=>{
 const endpoint='https://relay.example.test/mcp';
 assert.equal(portalBuildConfig({CORE_UI_EXTERNAL_AGENT_MCP_URL:endpoint}).mcp,endpoint);
 assert.equal(portalBuildConfig({CORE_UI_EXTERNAL_AGENT_MCP_URL:endpoint,CORE_UI_EXTERNAL_AGENT_OAUTH_CLIENT_ID:'myos-dots'}).mcpClientId,'myos-dots');
 assert.throws(()=>portalBuildConfig({CORE_UI_EXTERNAL_AGENT_OAUTH_CLIENT_ID:'myos-dots'}));
 assert.throws(()=>portalBuildConfig({CORE_UI_EXTERNAL_AGENT_MCP_URL:endpoint,CORE_UI_EXTERNAL_AGENT_OAUTH_CLIENT_ID:'invalid client id'}));
 for(const invalid of ['http://relay.example.test/mcp','https://user:secret@relay.example.test/mcp',endpoint+'?token=secret',endpoint+'#fragment','https://relay.example.test/other'])
  assert.throws(()=>portalBuildConfig({CORE_UI_EXTERNAL_AGENT_MCP_URL:invalid}));
});

test('Dots CIMD instructions require an explicitly enabled provider',()=>{
 const endpoint='https://relay.example.test/mcp';
 assert.equal(portalBuildConfig({CORE_UI_EXTERNAL_AGENT_MCP_URL:endpoint,CORE_UI_EXTERNAL_AGENT_DOTS_CIMD:'1'}).dotsCimd,true);
 assert.equal(portalBuildConfig({CORE_UI_EXTERNAL_AGENT_MCP_URL:endpoint,CORE_UI_EXTERNAL_AGENT_DOTS_CIMD:'0'}).dotsCimd,false);
 assert.throws(()=>portalBuildConfig({CORE_UI_EXTERNAL_AGENT_DOTS_CIMD:'1'}));
 assert.throws(()=>portalBuildConfig({CORE_UI_EXTERNAL_AGENT_MCP_URL:endpoint,CORE_UI_EXTERNAL_AGENT_DOTS_CIMD:'true'}));
});
