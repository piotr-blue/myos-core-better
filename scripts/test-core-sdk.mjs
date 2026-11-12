// Black-box generated SDK checks against the isolated real Better HTTP service.
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { PackagesAndTypesApi, AdminApi, AccountApi, APIKeysApi, ContentsApi, AdmissionsApi, TimelinesApi, DocumentsApi, EventsApi, WebhooksApi, Configuration, ResponseError } from '../sdk/typescript/dist/index.js';
const basePath = process.argv[2];
assert.match(basePath, /^http:\/\/127\.0\.0\.1:\d+$/);
const configuration = (headers = {}) => new Configuration({basePath, headers: {
  Authorization: 'Bearer sdk-test-user', Origin: 'https://console.example', ...headers,
}});
const account = new AccountApi(configuration());
const me = await account.getMe({});
const deniedAdmin = await new AdminApi(configuration()).getAdminSession({}).then(() => assert.fail('Ordinary owner became administrator'), error => error);
assert.ok(deniedAdmin instanceof ResponseError);
assert.equal(deniedAdmin.response.status,403);
assert.equal((await deniedAdmin.response.json()).code,'ADMIN_REQUIRED');
const adminConfiguration = configuration({Authorization:'Bearer sdk-test-admin', 'X-MyOS-Account':'unrelated-workspace'});
const adminMe = await new AccountApi(adminConfiguration).getMe({});
const adminApi = new AdminApi(adminConfiguration);
const adminSession = await adminApi.getAdminSession({xMyOSSessionContext:adminMe.sessionContext});
assert.equal(adminSession.userId,adminMe.userId);
assert.equal(adminSession.email,'admin@example.invalid');
assert.equal(adminSession.sessionContext,adminMe.sessionContext);
await assert.rejects(adminApi.getAdminSession({xMyOSSessionContext:me.sessionContext}), error => error instanceof ResponseError && error.response.status === 409);
console.log('Generated Core SDK passed: separate administrator authority, base-session guard and ordinary-owner denial.');
assert.equal(me.accountModel, 'MEMBERSHIPS_V1');
assert.equal(me.accounts.length, 1);
const selected = new AccountApi(configuration({'X-MyOS-Account': me.preferences.defaultAccountId}));
const workspace = await selected.getAccount({});
assert.equal(workspace.userId, me.userId);
assert.notEqual(workspace.sessionContext, me.sessionContext);
const lookupIds = [me.userId, adminMe.userId].sort();
const lookupFirst = await account.lookupAccounts({lookupAccounts:{accountIds:[...lookupIds].reverse(),limit:1},xMyOSSessionContext:me.sessionContext});
assert.equal(lookupFirst.items[0].accountId,lookupIds[0]);
assert.equal(lookupFirst.hasMore,true);
const lookupNext = await account.lookupAccounts({lookupAccounts:{accountIds:[...lookupIds,lookupIds[0]],limit:1,pageToken:lookupFirst.nextPageToken},xMyOSSessionContext:me.sessionContext});
assert.equal(lookupNext.items[0].accountId,lookupIds[1]);
assert.equal(lookupNext.hasMore,false);
assert.deepEqual(lookupFirst.items[0].matchedUsers,[]);
assert.equal('email' in lookupFirst.items[0],false);
await assert.rejects(new AccountApi(adminConfiguration).lookupAccounts({lookupAccounts:{accountIds:lookupIds,limit:1,pageToken:lookupFirst.nextPageToken},xMyOSSessionContext:adminMe.sessionContext}),error=>error instanceof ResponseError&&error.response.status===400);
console.log('Generated Core SDK passed: exact public account-ID lookup, no idempotency key, scoped pagination and private-field exclusion.');

const profileCommand = {idempotencyKey: randomUUID(), xMyOSSessionContext: me.sessionContext,
  updateMyProfile: {firstName:'SDK', lastName:'Check', expectedRevision:me.profileRevision}};
const profile = await account.updateMyProfile(profileCommand);
assert.equal(profile.firstName, 'SDK');
assert.deepEqual(await account.updateMyProfile(profileCommand), profile);
await assert.rejects(account.updateMyProfile({...profileCommand,idempotencyKey:randomUUID()}),
  error => error instanceof ResponseError && error.response.status === 409);
const keyApi = new APIKeysApi(configuration({'X-MyOS-Account': workspace.accountId,
  'X-MyOS-Session-Context': workspace.sessionContext}));
const keyCommand={idempotencyKey:randomUUID(), createApiKey:{name:'SDK test'}};
const created = await keyApi.createApiKey(keyCommand);
assert.deepEqual(await keyApi.createApiKey(keyCommand),created);
assert.equal(created.apiKey.effective,true);
assert.equal(typeof created.secret,'string');
assert.equal((await keyApi.listApiKeys({})).items[0].id,created.apiKey.id);
const machine = new Configuration({basePath,headers:{Authorization:'Bearer '+created.secret}});
await assert.rejects(new AdminApi(machine).getAdminSession({}), error => error instanceof ResponseError && error.response.status === 403);
assert.equal((await new AccountApi(machine).getAccount({})).userId,workspace.userId);
assert.deepEqual((await new DocumentsApi(machine).listDocuments({})).items,[]);
await keyApi.revokeApiKey({keyId:created.apiKey.id,idempotencyKey:randomUUID()});
assert.equal((await keyApi.listApiKeys({})).items[0].effective,false);
await assert.rejects(keyApi.createApiKey(keyCommand),error=>error instanceof ResponseError&&error.response.status===409);
await assert.rejects(new AccountApi(machine).getAccount({}),error=>error instanceof ResponseError&&error.response.status===401);
const stranger = new AccountApi(configuration({'X-MyOS-Account':'foreign-account'}));
await assert.rejects(stranger.getAccount({}),error=>error instanceof ResponseError && error.response.status===403);
const scoped = configuration({'X-MyOS-Account':workspace.accountId,'X-MyOS-Session-Context':workspace.sessionContext});
const contents = new ContentsApi(scoped), admissions = new AdmissionsApi(scoped);
const contentCommand = {idempotencyKey:randomUUID(),createContent:{inputMode:'source', content:"name: SDK content\ncount: {type: Integer, value: '9007199254740993'}"}};
const stored = await contents.createContent(contentCommand);
assert.equal(stored.status,'SUCCEEDED');
assert.equal(stored.completionScope,'CONTENT_STORED');
assert.deepEqual(await contents.createContent(contentCommand),stored);
assert.deepEqual(await admissions.getAdmission({admissionId:stored.admissionId}),stored);
const value = await contents.getContent({blueId:stored.details.contentRef.blueId});
assert.equal(value.count.value,'9007199254740993');
const invalid = await contents.createContent({idempotencyKey:randomUUID(),createContent:{inputMode:'source', content:'type: Integer\nvalue: -1\nschema: {minimum: 1}'}});
assert.equal(invalid.status,'FAILED');
assert.deepEqual(await admissions.getAdmission({admissionId:invalid.admissionId}),invalid);
const listedAdmissions = await admissions.listAdmissions({limit:1,kind:'CONTENT'});
assert.equal(listedAdmissions.items.length,1);
assert.equal(listedAdmissions.items[0].admissionId,invalid.admissionId);
assert.ok(listedAdmissions.nextPageToken);
const olderAdmissions = await admissions.listAdmissions({limit:1,kind:'CONTENT',pageToken:listedAdmissions.nextPageToken});
assert.equal(olderAdmissions.items[0].admissionId,stored.admissionId);
assert.equal(olderAdmissions.nextPageToken,null);
const failedAdmissions = await admissions.listAdmissions({status:'FAILED'});
assert.equal(failedAdmissions.items[0].admissionId,invalid.admissionId);
assert.equal(failedAdmissions.items[0].problem.status,422);
assert.equal(typeof failedAdmissions.items[0].problem.title,'string');
const timelines = new TimelinesApi(scoped);
const timelineCommand = {idempotencyKey:randomUUID(), createTimeline:{name:'SDK timeline'}};
const allocated = await timelines.createTimeline(timelineCommand);
assert.equal(allocated.status,'SUCCEEDED');
assert.deepEqual(await timelines.createTimeline(timelineCommand),allocated);
const timelineId = allocated.details.timelineId;
assert.equal((await timelines.getTimeline({timelineId})).owned,true);
const entryCommand = {timelineId,idempotencyKey:randomUUID(),appendEntry:{message:{amount:1},prevEntry:null}};
const entry = await timelines.appendEntry(entryCommand);
assert.equal(entry.status,'SUCCEEDED');
assert.equal(entry.completionScope,'TIMELINE_ENTRY_STORED');
assert.deepEqual(await timelines.appendEntry(entryCommand),entry);
const entries = await timelines.listTimelineEntries({timelineId});
assert.equal(entries.paginationMode,'PROJECTED_RETAINED_ENTRIES');
assert.equal(entries.items[0].blueId,entry.details.entryRef.blueId);
assert.deepEqual(await contents.getContent({blueId:entries.items[0].blueId}),entries.items[0].entry);
const detail = await timelines.getTimelineEntry({timelineId,entryBlueId:entries.items[0].blueId});
assert.deepEqual(detail,entries.items[0]);
const second = await timelines.appendEntry({timelineId,idempotencyKey:randomUUID(),appendEntry:{message:{amount:2},prevEntry:entries.items[0].blueId}});
assert.equal(second.status,'SUCCEEDED');
await assert.rejects(timelines.appendEntry({...entryCommand,idempotencyKey:randomUUID()}),error=>error instanceof ResponseError && error.response.status===409);
// Existing managed reader custody imports another owner's signed Timeline without append authority.
const fixture = JSON.parse(process.argv[3]);
const importCommand = {idempotencyKey:randomUUID(), importTimeline:{endpoint:fixture.endpoint,
  timelineId:fixture.timelineId, readerKeyId:fixture.readerKeyId, name:'SDK partner'}};
const imported = await timelines.importTimeline(importCommand);
assert.equal(imported.timelineId,fixture.timelineId);assert.equal(imported.owned,false);
assert.equal((await timelines.importTimeline(importCommand)).timelineId,fixture.timelineId);
await timelines.retryTimeline({timelineId:fixture.timelineId,idempotencyKey:randomUUID()});
let importedEntries;
for(let attempt=0;attempt<100;attempt++){
 importedEntries=await timelines.listTimelineEntries({timelineId:fixture.timelineId});
 if(importedEntries.items.length)break;
 await new Promise(resolve=>setTimeout(resolve,50));
}
assert.equal(importedEntries.paginationMode,'RETAINED_IMPORTED_ENTRIES');
assert.equal(importedEntries.items[0].blueId,fixture.entryBlueId);
assert.deepEqual(await timelines.getTimelineEntry({timelineId:fixture.timelineId,entryBlueId:fixture.entryBlueId}),importedEntries.items[0]);
assert.equal((await timelines.getTimeline({timelineId:fixture.timelineId})).status,'AVAILABLE');
assert.equal((await timelines.listTimelines({q:'SDK partner'})).items[0].timelineId,fixture.timelineId);
await assert.rejects(timelines.appendEntry({timelineId:fixture.timelineId,idempotencyKey:randomUUID(),appendEntry:{message:{amount:1}}}),error=>error instanceof ResponseError&&error.response.status===403);
console.log('Generated Core SDK passed: managed Timeline import, retry, signed retained history, direct entry lookup and append denial.');
const documents = new DocumentsApi(scoped);
const documentCommand = {idempotencyKey:randomUUID(),createDocument:{document:await readFile(new URL('../examples/counter.blue',import.meta.url),'utf8')}};
const createdDocument = await documents.createDocument(documentCommand);
assert.equal(createdDocument.status,'SUCCEEDED');
assert.deepEqual(await documents.createDocument(documentCommand),createdDocument);
const sessionId = createdDocument.details.sessionId;
const document = await documents.getDocument({sessionId});
assert.equal(document.name,'Hello Counter');
assert.equal(document.lastStable.epoch,0);
assert.notEqual(document.documentId,document.lastStable.documentRef.blueId);
const state = await contents.getContent({blueId:document.lastStable.documentRef.blueId});
assert.ok(state);
const history = await documents.getDocumentHistory({sessionId});
assert.equal(history.items.length,1);
assert.deepEqual(history.items[0].receiptRef,document.lastStable.receiptRef);
const epoch = await documents.getDocumentEpochsEpoch({sessionId,epoch:0});
assert.deepEqual(epoch.documentRef,document.lastStable.documentRef);
const events = await documents.getDocumentEpochsEpochEvents({sessionId,epoch:0});
assert.equal(events.kind,'INITIALIZE');
assert.equal(events.emitted.length,1);
assert.ok(await contents.getContent({blueId:events.emitted[0].eventRef.blueId}));
assert.equal((await documents.getDocumentOperations({sessionId})).items[0].name,'increment');
console.log('Generated Core SDK passed: accounts, contents, admissions, signed timelines, document initialization, retained history/epochs, emitted events and operation discovery.');

const storedParentType = await contents.createContent({idempotencyKey:randomUUID(), createContent:{
  goal:'definition', content:'description: SDK private parent\ncount: {type: Integer}'}});
assert.equal(storedParentType.status,'SUCCEEDED');
const storedChildType = await contents.createContent({idempotencyKey:randomUUID(), createContent:{
  goal:'definition', content:`description: SDK private child
type: {blueId: "${storedParentType.details.contentRef.blueId}"}
label: {type: Text}`}});
assert.equal(storedChildType.status,'SUCCEEDED');
const typedRequest = {idempotencyKey:randomUUID(),createDocument:{document:`name: SDK typed document
type: {blueId: "${storedChildType.details.contentRef.blueId}"}
count: 7
label: inherited type`}};
const typedDocument = await documents.createDocument(typedRequest);
assert.equal(typedDocument.status,'SUCCEEDED',JSON.stringify(typedDocument));
assert.deepEqual(await documents.createDocument(typedRequest),typedDocument);
const typedMetadata = await documents.getDocument({sessionId:typedDocument.details.sessionId});
const typedOriginal = await contents.getContent({blueId:typedMetadata.documentId});
assert.equal(typedOriginal.type.blueId,storedChildType.details.contentRef.blueId);
assert.equal((await documents.getDocumentContent({sessionId:typedDocument.details.sessionId,
  view:'operation_context',path:'/count'})).valueJson,'7');
console.log('Generated Core SDK passed: stored private type inheritance, typed native initialization, original source identity and replay.');


// The original form-preparation API is a read-only proposal, followed by real native execution.
const preparedBootstrap = await documents.bootstrapDocument({idempotencyKey:randomUUID(),bootstrapDocument:{
  document:`name: SDK prepared input
count: 0
contracts:
  buyer: {type: Coordination/Timeline Channel}
  setAmount:
    type: Contracts/Sequential Workflow Operation
    channel: buyer
    request: {amount: {type: Integer}}
    steps:
      - type: Contracts/Update Document
        changeset:
          - op: replace
            path: /count
            val: {$binding: call/request/amount}
`, participantMappings:[{channelName:'buyer',accountId:workspace.accountId}]}});
assert.equal(preparedBootstrap.status,'SUCCEEDED',JSON.stringify(preparedBootstrap));
const preparedSession = preparedBootstrap.details.sessionId;
const reviewed = await documents.getDocument({sessionId:preparedSession});
const discovered = await documents.getDocumentOperations({sessionId:preparedSession});
const operation = discovered.items.find(op=>op.name==='setAmount');
assert.equal(operation.routes.length,1);
const route = operation.routes[0];
const requestJson = '{"amount": 9007199254740993123456789}';
const proposal = {operationName:'setAmount', reviewedPublication:{documentRef:reviewed.lastStable.documentRef,epoch:reviewed.lastStable.epoch},route,requestJson};
const admissionBoundary = await admissions.listAdmissions({limit:100});
const proposed = await documents.prepareDocumentOperation({sessionId:preparedSession,prepareDocumentOperation:proposal});
assert.equal(proposed.validation.status,'valid');
assert.equal(proposed.requestJson,requestJson);
assert.ok(proposed.messageJson.includes('"request":'+requestJson));
assert.equal(proposed.definition.descriptor.fields[0].name,'amount');
assert.deepEqual((await documents.getDocument({sessionId:preparedSession})).lastStable,reviewed.lastStable);
assert.deepEqual(await admissions.listAdmissions({limit:100}),admissionBoundary);
const contextQuery = {sessionId:preparedSession,view:'operation_context',documentBlueId:reviewed.lastStable.documentRef.blueId,epoch:reviewed.lastStable.epoch};
const context = await documents.getDocumentContent({...contextQuery,path:'/count'});
assert.equal(context.valueJson,'0');
assert.equal(context.sessionId,preparedSession);
assert.deepEqual(context.reviewedPublication,proposal.reviewedPublication);
const outline = await documents.getDocumentContent({...contextQuery,childrenOffset:0});
assert.ok(outline.children.some(child=>child.path==='/contracts'));
assert.ok(outline.children.some(child=>child.path==='/count'));
await assert.rejects(documents.getDocumentContent({...contextQuery,epoch:1}),e=>e instanceof ResponseError&&e.response.status===409);

const invalidInput = await documents.prepareDocumentOperation({sessionId:preparedSession,prepareDocumentOperation:{...proposal,requestJson:'{"amount":"wrong"}'}});
assert.equal(invalidInput.validation.status,'invalid');
assert.equal(invalidInput.messageJson,undefined);
assert.equal(invalidInput.validation.issues[0].path,'/amount');
for (const changed of [{...proposal,reviewedPublication:{...proposal.reviewedPublication,epoch:1}},
  {...proposal,reviewedPublication:{...proposal.reviewedPublication,documentRef:{blueId:reviewed.documentId}}}])
  await assert.rejects(documents.prepareDocumentOperation({sessionId:preparedSession,prepareDocumentOperation:changed}),e=>e instanceof ResponseError&&e.response.status===409);
for (const field of ['timeline','actor','channel'])
  await assert.rejects(documents.prepareDocumentOperation({sessionId:preparedSession,prepareDocumentOperation:{...proposal,route:{...route,[field]:'foreign'}}}),e=>e instanceof ResponseError&&e.response.status===403);
const foreignDocuments = new DocumentsApi(configuration({'X-MyOS-Account':'foreign-account'}));
await assert.rejects(foreignDocuments.prepareDocumentOperation({sessionId:preparedSession,prepareDocumentOperation:proposal}),e=>e instanceof ResponseError&&e.response.status===403);
const invokeCommand = {sessionId:preparedSession,idempotencyKey:randomUUID(),invokeOperation:{timeline:{blueId:route.timeline},actor:route.actor,inputMode:'source',message:proposed.messageJson}};
const accepted = await documents.invokeOperation(invokeCommand).catch(async error => {
  if(error instanceof ResponseError) throw new Error('Prepared invocation failed: '+await error.response.text());
  throw error;
});
assert.equal(accepted.status,'SUCCEEDED');
assert.deepEqual(await documents.invokeOperation(invokeCommand),accepted);
const start = Date.now();let processed;
do {
  processed=await documents.getDocument({sessionId:preparedSession});
  if(processed.lastStable.epoch===1)break;
  await new Promise(resolve=>setTimeout(resolve,25));
} while(Date.now()-start<8000);
assert.equal(processed.lastStable.epoch,1);
const processedContent = await contents.getContent({blueId:processed.lastStable.documentRef.blueId});
assert.equal(processedContent.count.value,'9007199254740993123456789');
const proofCommand={timelineId:route.timeline,idempotencyKey:randomUUID(),limit:1,providerSelectors:{limit:1}};
const proof=await timelines.postTimelineGuarantees(proofCommand);
assert.equal(proof.paginationMode,'BOUNDED_PROVIDER_RANGE');
assert.ok(proof.items.some(item=>item.blueId===accepted.details.entryRef.blueId));
assert.deepEqual(await timelines.postTimelineGuarantees(proofCommand),proof);
assert.deepEqual(await contents.getContent({blueId:proof.guaranteeRef.blueId}),proof.guarantee);
assert.deepEqual(await contents.getContent({blueId:proof.attestationRef.blueId}),proof.attestation);
await assert.rejects(timelines.postTimelineGuarantees({...proofCommand,providerSelectors:{limit:2}}),e=>e instanceof ResponseError&&e.response.status===400);
await assert.rejects(timelines.postTimelineGuarantees({...proofCommand,limit:2,providerSelectors:{limit:2}}),e=>e instanceof ResponseError&&e.response.status===409);
await assert.rejects(timelines.postTimelineGuarantees({timelineId:fixture.timelineId,idempotencyKey:randomUUID(),providerSelectors:{}}),e=>e instanceof ResponseError&&e.response.status===422);
console.log('Generated Core SDK passed: signed provider guarantees, durable replay, exact retained proof content, selector conflicts and imported-read denial.');
const operationReceipts = await admissions.listAdmissions({kind:'OPERATION',sessionId:preparedSession});
assert.deepEqual(operationReceipts.items,[accepted]);
await assert.rejects(documents.prepareDocumentOperation({sessionId:preparedSession,prepareDocumentOperation:proposal}),e=>e instanceof ResponseError&&e.response.status===409);
console.log('Generated Core SDK passed: canonical operation preparation without admission or mutation, exact integers, stale publication and route rejection, native execution and replay.');
const eventApi = new EventsApi(scoped);
const retainedProcessing = await documents.getDocumentProcessingHistory({sessionId:preparedSession});
assert.equal(retainedProcessing.items.length,5);
assert.equal(retainedProcessing.historyScope,'RETAINED_EVENTS');
const rootEpochs = await eventApi.listEvents({sessionId:preparedSession,type:'DOCUMENT_EPOCH_ADVANCED',order:'desc'});
assert.equal(rootEpochs.items.length,2);
assert.equal(rootEpochs.items[0].object.blueId,processed.lastStable.documentRef.blueId);
assert.deepEqual(await eventApi.getEvent({eventId:rootEpochs.items[0].id}),rootEpochs.items[0]);
console.log('Generated Core SDK passed: native root publication events and retained processing history.');


// Original SDK evidence selectors, bounded pinned pages and exact original references.
const evidence = await documents.getDocumentEvidence({sessionId:preparedSession,epoch:1,limit:1});
assert.equal(evidence.target.epoch,1);
assert.equal(evidence.target.documentId,processed.documentId);
assert.deepEqual(evidence.target.positionRef,processed.lastStable.positionRef);
const evidenceItems=[...evidence.items];
for(let token=evidence.nextPageToken;token;){
  const page=await documents.getDocumentEvidence({sessionId:preparedSession,pageToken:token,limit:1});
  assert.deepEqual(page.target,evidence.target);
  evidenceItems.push(...page.items);token=page.nextPageToken;
}
const byPosition=await documents.getDocumentEvidence({sessionId:preparedSession,positionBlueId:processed.lastStable.positionRef.blueId});
assert.deepEqual(byPosition.items,evidenceItems);
assert.ok(evidenceItems.some(item=>item.kind==='PROCESSING_RECEIPT'&&item.ref.blueId===processed.lastStable.receiptRef.blueId));
assert.ok(evidenceItems.every(item=>item.value!==undefined));
const byEntry=await documents.getDocumentEvidence({sessionId:preparedSession,entryBlueId:accepted.details.entryRef.blueId});
assert.equal(byEntry.target.epoch,1);
assert.deepEqual(byEntry.target.entryRef,accepted.details.entryRef);
assert.ok(byEntry.items.some(item=>item.ref.blueId===accepted.details.entryRef.blueId));
await assert.rejects(documents.getDocumentEvidence({sessionId:preparedSession,epoch:1,entryBlueId:accepted.details.entryRef.blueId}),e=>e instanceof ResponseError&&e.response.status===400);
console.log('Generated Core SDK passed: exact retained evidence by epoch, position and signed entry, with pinned pagination.');

// Unlink removes the root registration, not its content or native execution history.
assert.equal(processed.canUnlink,true);
const unlinkCommand = {sessionId:preparedSession,idempotencyKey:randomUUID()};
const unlinked = await documents.unlinkDocument(unlinkCommand);
assert.equal(unlinked.kind,'REMOVE');
assert.equal(unlinked.status,'SUCCEEDED');
assert.equal(unlinked.completionScope,'REGISTRATION_UNLINKED');
assert.deepEqual(unlinked.details,{sessionId:preparedSession,processingChanged:false});
assert.deepEqual(await documents.unlinkDocument(unlinkCommand),unlinked);
assert.deepEqual(await admissions.getAdmission({admissionId:unlinked.admissionId}),unlinked);
assert.deepEqual((await documents.listDocuments({sessionId:preparedSession})).items,[]);
for (const read of [()=>documents.getDocument({sessionId:preparedSession}),
  ()=>documents.getDocumentHistory({sessionId:preparedSession}),
  ()=>documents.getDocumentContent({sessionId:preparedSession}),
  ()=>documents.getDocumentOperations({sessionId:preparedSession}),
  ()=>documents.getDocumentEvidence({sessionId:preparedSession,epoch:1})])
  await assert.rejects(read(),e=>e instanceof ResponseError&&e.response.status===404);
assert.deepEqual(await contents.getContent({blueId:processed.lastStable.documentRef.blueId}),processedContent);
console.log('Generated Core SDK passed: root unlink receipt, replay, hidden document endpoints and retained exact content.');

const relinkRequest = {idempotencyKey:randomUUID(),createDocument:{
  document:processed.lastStable.documentRef,inputMode:'exact'}};
const relinked = await documents.createDocument(relinkRequest);
assert.equal(relinked.status,'SUCCEEDED');
const alias = relinked.details.sessionId;
assert.notEqual(alias,preparedSession);
assert.deepEqual(await documents.createDocument(relinkRequest),relinked);
const aliasDocument = await documents.getDocument({sessionId:alias});
assert.equal(aliasDocument.instanceId,preparedSession);
assert.deepEqual(aliasDocument.lastStable,processed.lastStable);
const aliasDiscovered = await documents.getDocumentOperations({sessionId:alias});
const aliasRoute = aliasDiscovered.items.find(op=>op.name==='setAmount').routes[0];
const aliasProposal = await documents.prepareDocumentOperation({sessionId:alias,prepareDocumentOperation:{
  operationName:'setAmount',reviewedPublication:{documentRef:aliasDocument.lastStable.documentRef,epoch:aliasDocument.lastStable.epoch},
  route:aliasRoute,requestJson:'{"amount":42}'}});
const aliasEntry = await documents.invokeOperation({sessionId:alias,idempotencyKey:randomUUID(),invokeOperation:{
  timeline:{blueId:aliasRoute.timeline},actor:aliasRoute.actor,message:aliasProposal.messageJson,inputMode:'source'}});
assert.equal(aliasEntry.status,'SUCCEEDED');
let aliasCurrent;const aliasStart=Date.now();
do {
  aliasCurrent=await documents.getDocument({sessionId:alias});
  if(aliasCurrent.lastStable.epoch===2) break;
  await new Promise(resolve=>setTimeout(resolve,25));
} while(Date.now()-aliasStart<8000);
assert.equal(aliasCurrent.lastStable.epoch,2);
const aliasedEvidence=await documents.getDocumentEvidence({sessionId:alias,positionBlueId:processed.lastStable.positionRef.blueId});
assert.equal(aliasedEvidence.target.sessionId,alias);
assert.equal(aliasedEvidence.target.epoch,1);
assert.deepEqual(aliasedEvidence.items,byPosition.items);
assert.equal((await documents.getDocumentContent({sessionId:alias,view:'operation_context',path:'/count'})).valueJson,'42');
assert.equal((await documents.getDocumentInputsEntryblueid({sessionId:alias,entryBlueId:aliasEntry.details.entryRef.blueId})).localHistory.length,1);
await assert.rejects(documents.getDocument({sessionId:preparedSession}),e=>e instanceof ResponseError&&e.response.status===404);
console.log('Generated Core SDK passed: re-registration preserves history and instance identity, then executes a prepared native operation through the new handle.');
const personalMembers = await selected.listAccountMembers();
assert.equal(personalMembers.items.length,1);
assert.equal(personalMembers.items[0].userId,me.userId);
assert.equal(personalMembers.items[0].email,me.email);
assert.equal(personalMembers.items[0].role,'OWNER');
assert.deepEqual((await selected.listAccountInvitations()).items,[]);
assert.deepEqual((await selected.listAccountAgents()).items,[]);
const team = await account.createAccount({idempotencyKey:randomUUID(),createAccount:{kind:'BUSINESS',profile:{displayName:'SDK roster'}}});
const teamApi = new AccountApi(configuration({'X-MyOS-Account':team.accountId}));
assert.equal((await teamApi.listAccountMembers()).items[0].role,'ADMIN');
const teamAgents = (await teamApi.listAccountAgents()).items;
assert.equal(teamAgents.length,1);
assert.equal(teamAgents[0].agentId,team.agentId);
assert.equal(teamAgents[0].execution,'DISABLED');
assert.equal(teamAgents[0].actorStatus,'UNQUALIFIED');
console.log('Generated Core SDK passed: real personal/workspace members, invitation lists and provisioned agent metadata.');

// Original account invitations: private fixture delivery, acceptance, removal and new generation.
{
const invitationContext = (await teamApi.getAccount({})).sessionContext;
const inviteCommand = {xMyOSSessionContext:invitationContext,idempotencyKey:randomUUID(),
 createInvitation:{email:'admin@example.invalid',role:'WRITE'}};
const issuedRaw = await teamApi.createAccountInvitationRaw(inviteCommand);
assert.equal(issuedRaw.raw.status,201);
const issued = await issuedRaw.value();
assert.equal(issued.status,'PENDING');assert.equal(issued.token,undefined);assert.equal(issued.tokenDigest,undefined);
assert.deepEqual(await teamApi.createAccountInvitation(inviteCommand),issued);
const unused = await teamApi.createAccountInvitation({...inviteCommand,idempotencyKey:randomUUID(),createInvitation:{email:'admin@example.invalid',role:'ADMIN'}});
async function deliveredToken(invitation){
 const file=join(process.env.MYOS_TEST_INVITATION_OUTBOX,invitation.invitationId+'.json');
 for(let attempt=0;attempt<100;attempt++){
  try {
   const delivery=JSON.parse(await readFile(file,'utf8'));
   assert.equal(delivery.recipient,'admin@example.invalid');
   const link=new URL(delivery.link);assert.equal(link.origin,'https://console.example');
   const values=new URLSearchParams(link.hash.slice('#invitation?'.length));
   assert.equal(values.get('invitationId'),invitation.invitationId);return values.get('token');
  }catch(error){if(error.code!=='ENOENT')throw error;await new Promise(r=>setTimeout(r,25));}
 }
 assert.fail('Private fixture delivery did not complete');
}
const accepting = new AccountApi(configuration({Authorization:'Bearer sdk-test-admin'}));
const acceptance = {invitationId:issued.invitationId,xMyOSSessionContext:adminMe.sessionContext,
 acceptInvitation:{token:await deliveredToken(issued),expectedRevision:1}};
const accepted = await accepting.acceptAccountInvitation(acceptance);
assert.equal(accepted.accountId,team.accountId);assert.equal(accepted.membershipGeneration,1);
assert.equal((await teamApi.createAccountInvitation(inviteCommand)).status,'ACCEPTED');
assert.equal((await teamApi.listAccountMembers()).items.find(x=>x.userId===adminMe.userId).role,'WRITE');
assert.equal((await accepting.getMe({})).accounts.some(x=>x.accountId===team.accountId),true);
const guestSelected = new AccountApi(configuration({Authorization:'Bearer sdk-test-admin','X-MyOS-Account':team.accountId}));
const guestContext = (await guestSelected.getAccount({})).sessionContext;
const guestKeys = new APIKeysApi(configuration({Authorization:'Bearer sdk-test-admin','X-MyOS-Account':team.accountId,'X-MyOS-Session-Context':guestContext}));
const guestKey = await guestKeys.createApiKey({idempotencyKey:randomUUID(),createApiKey:{name:'Old membership generation'}});
const oldCredential = new AccountApi(configuration({Authorization:'Bearer '+guestKey.secret,'X-MyOS-Account':team.accountId}));
assert.equal((await oldCredential.getAccount({})).accountId,team.accountId);

const demoted = await teamApi.updateAccountMembership({userId:adminMe.userId,xMyOSSessionContext:invitationContext,idempotencyKey:randomUUID(),
 updateMembership:{expectedGeneration:1,expectedRevision:1,role:'READ_ALL',status:'ACTIVE'}});
await teamApi.updateAccountMembership({userId:adminMe.userId,xMyOSSessionContext:invitationContext,idempotencyKey:randomUUID(),
 updateMembership:{expectedGeneration:1,expectedRevision:demoted.revision,role:'READ_ALL',status:'REMOVED'}});
assert.deepEqual(await accepting.acceptAccountInvitation(acceptance),accepted);
assert.equal((await teamApi.listAccountMembers()).items.find(x=>x.userId===adminMe.userId).status,'REMOVED');
await assert.rejects(accepting.acceptAccountInvitation({invitationId:unused.invitationId,xMyOSSessionContext:adminMe.sessionContext,
 acceptInvitation:{token:await deliveredToken(unused),expectedRevision:1}}),e=>e instanceof ResponseError&&e.response.status===409);
const reinvite=await teamApi.createAccountInvitation({...inviteCommand,idempotencyKey:randomUUID()});
assert.equal(reinvite.recipientUserId,adminMe.userId);
const rejoined=await accepting.acceptAccountInvitation({invitationId:reinvite.invitationId,xMyOSSessionContext:adminMe.sessionContext,
 acceptInvitation:{token:await deliveredToken(reinvite),expectedRevision:1}});
assert.equal(rejoined.membershipGeneration,2);assert.equal(rejoined.membershipRevision,4);
await assert.rejects(oldCredential.getAccount({}),e=>e instanceof ResponseError&&e.response.status===401);
await assert.rejects(guestSelected.getAccount({xMyOSSessionContext:guestContext}),e=>e instanceof ResponseError&&e.response.status===409);

const revoked=await teamApi.revokeAccountInvitation({invitationId:unused.invitationId,xMyOSSessionContext:invitationContext,
 idempotencyKey:randomUUID(),revokeInvitation:{expectedRevision:1}});
assert.equal(revoked.status,'REVOKED');assert.equal(revoked.revision,2);
console.log('Generated Core SDK passed: encrypted invitation delivery, original response shapes, acceptance replay, removal, stale-link rejection, rejoin and revocation.');
}


// Original AccountApi Agent defaults, mutation/replay, stale revisions and bounded connections.
const agentsApi = new AccountApi(scoped);
const agentCommand = {idempotencyKey:randomUUID(),createAccountAgent:{name:'SDK external Agent'}};
const agent = await agentsApi.createAccountAgent(agentCommand);
assert.equal(agent.role,'READ_SELECTED');
assert.equal(agent.inclusion,false);
assert.equal(agent.kind,'EXTERNAL');
assert.deepEqual(await agentsApi.createAccountAgent(agentCommand),agent);
const agentPause = {agentId:agent.agentId,idempotencyKey:randomUUID(),updateAccountAgent:{status:'PAUSED',expectedRevision:agent.revision}};
const pausedAgent = await agentsApi.updateAccountAgent(agentPause);
assert.equal(pausedAgent.status,'PAUSED');
assert.equal(pausedAgent.revision,2);
assert.deepEqual(await agentsApi.updateAccountAgent(agentPause),pausedAgent);
await assert.rejects(agentsApi.updateAccountAgent({...agentPause,idempotencyKey:randomUUID()}), e=>e instanceof ResponseError&&e.response.status===409);
assert.deepEqual((await agentsApi.listAgentConnections({agentId:agent.agentId})).items,[]);
assert.equal((await agentsApi.listAccountAgents({})).items.find(item=>item.agentId===agent.agentId).status,'PAUSED');
console.log('Generated Core SDK passed: Agent registration, settings, exact replay, stale revision rejection and connection listing. OAuth/execution remain unimplemented.');

// Original webhook configuration contract. INACTIVE avoids sending to any external receiver.
const callbacks = new WebhooksApi(configuration());
const callbackCommand = {idempotencyKey:randomUUID(), webhookCreate:{name:'SDK callback',url:'https://receiver.example/callback',status:'INACTIVE',events:[{type:'DOCUMENT_CREATED'}],filters:{sessionIds:[]}}};
const callbackResponse = await callbacks.createWebhookRaw(callbackCommand);
assert.equal(callbackResponse.raw.status,201);
const callback = await callbackResponse.value();
assert.equal(callbackResponse.raw.headers.get('Location'),'/api/blue/v1/webhooks/'+callback.id);
assert.match(callback.signingSecret,/^whsec_/);
assert.deepEqual(await callbacks.createWebhook(callbackCommand),callback);
assert.equal((await callbacks.getWebhook({webhookId:callback.id})).signingSecret,undefined);
assert.equal((await callbacks.listWebhooks({status:'INACTIVE'})).items.some(item=>item.id===callback.id),true);
const changedCallback=await callbacks.postWebhook({webhookId:callback.id,idempotencyKey:randomUUID(),webhookWrite:{name:'Updated callback'}});
assert.equal(changedCallback.name,'Updated callback');assert.deepEqual(changedCallback.events,callback.events);
assert.deepEqual((await callbacks.listWebhookEvents({webhookId:callback.id})).items,[]);
assert.equal((await callbacks.getWebhookHealth({webhookId:callback.id})).queued,0);
const deleteCallback={webhookId:callback.id,idempotencyKey:randomUUID()};
await callbacks.deleteWebhook(deleteCallback);await callbacks.deleteWebhook(deleteCallback);
await assert.rejects(callbacks.getWebhook({webhookId:callback.id}),error=>error instanceof ResponseError&&error.response.status===404);
assert.deepEqual(await callbacks.createWebhook(callbackCommand),callback);
console.log('Generated Core SDK passed: webhook create/read/update/list/health/delete and secret-safe durable replay. Delivery I/O is qualified separately.');

// Original membership command: Owner release, exact replay and last-admin protection.
const memberApi = new AccountApi(scoped);
const releaseOwner = {userId:me.userId,idempotencyKey:randomUUID(),updateMembership:{
  expectedGeneration:1,expectedRevision:1,role:'ADMIN',status:'ACTIVE'}};
const releasedOwner=await memberApi.updateAccountMembership(releaseOwner);
assert.equal(releasedOwner.role,'ADMIN');assert.equal(releasedOwner.revision,2);
assert.equal(releasedOwner.accountId,workspace.accountId);
assert.deepEqual(await memberApi.updateAccountMembership(releaseOwner),releasedOwner);
assert.equal((await memberApi.getAccount({})).membership.role,'ADMIN');
await assert.rejects(memberApi.updateAccountMembership({...releaseOwner,idempotencyKey:randomUUID()}),
  e=>e instanceof ResponseError&&e.response.status===409);
await assert.rejects(memberApi.updateAccountMembership({userId:me.userId,idempotencyKey:randomUUID(),
  updateMembership:{expectedGeneration:1,expectedRevision:2,role:'ADMIN',status:'REMOVED'}}),
  e=>e instanceof ResponseError&&e.response.status===409);
assert.equal((await documents.getDocument({sessionId})).sessionId,sessionId);
console.log('Generated Core SDK passed: membership Owner release, exact replay, stale-revision rejection and last-admin protection.');

// Original Timeline append form: initial identity, authored target insertion and reattached handle.
const timelineCall={timelineId:aliasRoute.timeline,idempotencyKey:randomUUID(),appendEntry:{
 documentId:aliasDocument.documentId,inputMode:'source',actor:aliasRoute.actor,
 message:'type: Contracts/Operation Request\nchannel: buyer\noperation: setAmount\nrequest: {amount: 43}'}};
const timelineAccepted=await timelines.appendEntry(timelineCall);
assert.equal(timelineAccepted.status,'SUCCEEDED',JSON.stringify(timelineAccepted));
assert.equal(timelineAccepted.details.documentId,aliasDocument.documentId);
assert.deepEqual(await timelines.appendEntry(timelineCall),timelineAccepted);
const timelineStart=Date.now();let timelineCurrent;
do {
 timelineCurrent=await documents.getDocument({sessionId:alias});
 if(timelineCurrent.lastStable.epoch===3)break;
 await new Promise(resolve=>setTimeout(resolve,25));
} while(Date.now()-timelineStart<8000);
assert.equal(timelineCurrent.lastStable.epoch,3);
assert.equal((await documents.getDocumentContent({sessionId:alias,view:'operation_context',path:'/count'})).valueJson,'43');
const publishedTimelineMessage=await contents.getContent({blueId:timelineAccepted.details.messageRef.blueId});
assert.equal(publishedTimelineMessage.document.blueId,aliasDocument.documentId);
assert.deepEqual(await timelines.appendEntry(timelineCall),timelineAccepted);
await assert.rejects(timelines.appendEntry({...timelineCall,idempotencyKey:randomUUID(),appendEntry:{...timelineCall.appendEntry,documentId:timelineCurrent.lastStable.documentRef.blueId}}),e=>e instanceof ResponseError&&e.response.status===422);
console.log('Generated Core SDK passed: Timeline form targeting, authored target insertion, exact replay, reattached registration, native execution and version-ID rejection.');

// Account library choices are durable authoring metadata, not runtime capability installation.
{
const packageApi = new PackagesAndTypesApi(scoped);
const catalogue = await packageApi.getPackages({});
const library = catalogue.items.find(p => p.name === 'ManualPayments');
assert.ok(library && library.canRemove && !library.managed);
const removePackage = {packageId:library.id,idempotencyKey:randomUUID()};
const removedPackage = await packageApi.deletePackage(removePackage);
assert.equal(removedPackage.status,'SUCCEEDED');
assert.equal(removedPackage.completionScope,'PACKAGE_ACTION_APPLIED');
assert.equal(removedPackage.details.packageId,library.blueId);
assert.equal(removedPackage.details.installed,false);
assert.deepEqual(await packageApi.deletePackage(removePackage),removedPackage);
assert.ok((await packageApi.getPackages({})).available.some(p => p.id === library.id && p.canInstall));
const alias = 'ManualPayments/' + library.types[0].name;
const missingAlias = await contents.createContent({idempotencyKey:randomUUID(),createContent:{inputMode:'source',goal:'definition',content:{type:alias}}});
assert.equal(missingAlias.status,'FAILED');
assert.equal(missingAlias.problem.code,'type_not_installed');
const installedPackage = await packageApi.postPackage({packageId:library.id,idempotencyKey:randomUUID()});
assert.equal(installedPackage.status,'SUCCEEDED');
assert.equal(installedPackage.details.installed,true);
const authored = await contents.createContent({idempotencyKey:randomUUID(),createContent:{inputMode:'source',goal:'definition',content:{type:alias}}});
assert.equal(authored.status,'SUCCEEDED');
const nativePackage = catalogue.items.find(p => p.name === 'Contracts');
const protectedPackage = await packageApi.deletePackage({packageId:nativePackage.id,idempotencyKey:randomUUID()});
assert.equal(protectedPackage.status,'FAILED');
assert.equal(protectedPackage.problem.code,'managed_package');
console.log('Generated Core SDK passed: package remove/install, retained receipts, missing source aliases and managed runtime protection.');
}
