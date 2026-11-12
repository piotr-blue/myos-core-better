import test from 'node:test';
import assert from 'node:assert/strict';
import { Configuration, DocumentsApi } from '../dist/index.js';

test('bootstrap preserves the exact package array in the HTTP request',async()=>{
 let submitted;
 const api=new DocumentsApi(new Configuration({basePath:'https://core.example.test',fetchApi:async(url,init)=>{submitted={url,init};return new Response(JSON.stringify({admissionId:'accepted',status:'PENDING',kind:'DOCUMENT_BOOTSTRAP'}),{status:202,headers:{'Content-Type':'application/json'}});}}));
 await api.bootstrapDocument({idempotencyKey:'same-intent',bootstrapDocument:{document:'name: Example',packages:['exact-package-one','exact-package-two']}});
 assert.equal(submitted.url,'https://core.example.test/api/blue/v1/documents/bootstrap');
 assert.deepEqual(JSON.parse(submitted.init.body),{document:'name: Example',packages:['exact-package-one','exact-package-two']});
 assert.equal(new Headers(submitted.init.headers).get('Idempotency-Key'),'same-intent');
});
