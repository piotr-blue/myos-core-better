import type { CreatedAccount, ContentsApi, ExactContentSubmission, Admission, BootstrapAdmission, BootstrapDocument, DocumentsApi, CreateDocument, DocumentAdmission, AppendEntry, PackageType, TypeInspection } from '../index.js';

// Valid server inputs rejected by the unconfigured OpenAPI 3.1 generator.
const source: CreateDocument = { document: 'name: SDK example' };
const reference: CreateDocument = { document: { blueId: 'exact-document' }, inputMode: 'exact' };
const pending: DocumentAdmission = {
  admissionId: 'receipt', kind: 'DOCUMENT', status: 'PENDING',
  completionScope: 'REGISTRATION_STORED', details: null,
  createdAt: null, completedAt: null,
};
const emptyHead: AppendEntry = { message: 'literal text', prevEntry: null };
const unconstrainedHead: AppendEntry = { message: { domainField: 123 } };

// Incorrect discriminators and completion scopes must not silently become `any`.
// @ts-expect-error A document admission cannot have a timeline kind.
const wrongKind: DocumentAdmission['kind'] = 'TIMELINE';
// @ts-expect-error A document admission only proves registration storage.
const wrongScope: DocumentAdmission['completionScope'] = 'BUSINESS_COMPLETED';
// @ts-expect-error Documents accept an object or source text, not a number.
const wrongDocument: CreateDocument = { document: 42 };

function sessionOf(admission: Admission) {
  if (admission.kind === 'DOCUMENT' && admission.details) return admission.details.sessionId;
}
function packageOf(type: TypeInspection | PackageType): string { return type.package; }
// @ts-expect-error The wire property is package; identity deserialization must not rename it.
type RenamedPackage = TypeInspection['_package'];
void [source, reference, pending, emptyHead, unconstrainedHead, wrongKind, wrongScope, wrongDocument, sessionOf, packageOf];

// Bootstrap accepts existing-account mappings and returns a durable receipt.
const bootstrap: BootstrapDocument = { document: 'name: shared', inputMode: 'source', packages: ['exact-package'],
  participantMappings: [{ channelName: 'owner', accountId: 'account' }, { channelName: 'peer', accountId: 'peer', representativeId: 'human' }] };
const pendingBootstrap: BootstrapAdmission = { ...pending, kind: 'DOCUMENT_BOOTSTRAP' };
const completedBootstrap: BootstrapAdmission = { ...pendingBootstrap, status: 'SUCCEEDED', details: { sessionId: 'session' } };
const failedBootstrap: BootstrapAdmission = { ...pendingBootstrap, status: 'FAILED', details: null };
// @ts-expect-error A JavaScript Set is not a JSON array of package identities.
const invalidPackageSet: BootstrapDocument = { document: {}, packages: new Set(['exact-package']) };
// @ts-expect-error Legacy participants are not accepted for new bootstrap requests.
const legacyParticipants: BootstrapDocument = { document: {}, participants: {} };
// @ts-expect-error Bootstrap does not accept initial messages.
const initialMessages: BootstrapDocument = { document: {}, initialMessages: [] };
// @ts-expect-error A participant mapping needs an account ID or ownership email selector.
const missingAccount: BootstrapDocument = { document: {}, participantMappings: [{ channelName: 'owner' }] };
// @ts-expect-error Bootstrap success details require the target session ID.
const missingBootstrapSession: BootstrapAdmission = { ...pendingBootstrap, details: {} };
// @ts-expect-error Bootstrap does not return per-role timelines in its receipt.
const bootstrapTimelines: BootstrapAdmission = { ...pendingBootstrap, details: { sessionId: 'session', timelines: {} } };
type BootstrapResponse = Awaited<ReturnType<DocumentsApi['bootstrapDocument']>>;
const acceptedResponse: BootstrapResponse = pendingBootstrap;
function bootstrapOutcome(result: BootstrapResponse): BootstrapAdmission { return result; }
// Email selects its identity's owned account, provisioning a personal account if needed.
const emailSelector: BootstrapDocument = { document: {}, participantMappings: [{ channelName: "owner", email: "owner@example.test" }] };
function bootstrapSessionOf(admission: Admission) {
  if (admission.kind === 'DOCUMENT_BOOTSTRAP' && admission.details) return admission.details.sessionId;
}
void [bootstrap, pendingBootstrap, completedBootstrap, failedBootstrap, legacyParticipants, initialMessages,
  missingAccount, missingBootstrapSession, bootstrapTimelines, acceptedResponse, bootstrapOutcome, emailSelector, bootstrapSessionOf];

const exactContent: ExactContentSubmission = { content: { mode: 'exact', text: 'literal' } };
const scalarContent: ExactContentSubmission = { content: false };
// @ts-expect-error Public PUT requires content, not native exact text.
const nativeContent: ExactContentSubmission = { mode: 'exact', text: '{}' };
// @ts-expect-error PUT has no inputMode.
const sourceContent: ExactContentSubmission = { content: {}, inputMode: 'source' };
// @ts-expect-error PUT has no goal.
const goalContent: ExactContentSubmission = { content: {}, goal: 'definition' };
// @ts-expect-error The presentation endpoint was removed.
type RemovedView = ContentsApi['getContentView'];
// @ts-expect-error The collection endpoint was removed.
type RemovedCollection = ContentsApi['getContentCollection'];
void [exactContent, scalarContent, nativeContent, sourceContent, goalContent];

// SDK helpers retain the specific admission kind and narrow initialized documents.
import { waitForAdmission, waitForDocument, waitFor } from '../index.js';
import type { AdmissionsApi } from '../index.js';
async function helperTypes(admissions: AdmissionsApi, documents: DocumentsApi, accepted: DocumentAdmission) {
  const receipt: DocumentAdmission = await waitForAdmission(admissions, accepted);
  const initialized = await waitForDocument(documents, 'session');
  const identity: string = initialized.documentId;
  const reference = initialized.lastStable.documentRef;
  const count: number = await waitFor(async signal => {
    signal.throwIfAborted();
    return 1;
  }, value => value === 1);
  return { receipt, identity, reference, count };
}
void helperTypes;

const projectedTimelinePage: import('../index.js').TimelineEntriesPage = {
  timelineRef: { blueId: 'timeline' }, items: [], nextPageToken: null, hasMore: false,
  paginationMode: 'PROJECTED_RETAINED_ENTRIES',
};
void projectedTimelinePage;

const owner: import('../index.js').MembershipRole = 'OWNER';
// @ts-expect-error Ownership can only be assigned to a joined member.
const ownerInvite: import('../index.js').InvitationRole = 'OWNER';
const conversion: import('../index.js').UpdateAccountProfile = { expectedRevision: 1, kind: 'BUSINESS', displayName: 'Studio' };
// @ts-expect-error Business cannot be converted into Personal.
const reverseConversion: import('../index.js').UpdateAccountProfile = { expectedRevision: 1, kind: 'PERSONAL' };
void [owner, ownerInvite, conversion, reverseConversion];

// Directory Account images and matched User avatars are independent.
import type { AccountApi, AccountLookupItem, LookupAccounts, UpdateMyProfile, UserProfile } from '../index.js';
const lookupSelectors: LookupAccounts = { accountIds: ['company'], emails: ['anna@example.test'], q: 'ann' };
// @ts-expect-error At least one selector is required.
const unboundedDirectory: LookupAccounts = { limit: 20 };
const clearName: UpdateMyProfile = { expectedRevision: 0, firstName: null };
// @ts-expect-error A profile command must actually select a name field.
const emptyProfile: UpdateMyProfile = { expectedRevision: 0 };
const directoryItem: AccountLookupItem = { accountId: 'company', kind: 'BUSINESS', displayName: 'Company', website: null,
  matchedUsers: [{ userId: 'anna', email: 'anna@example.test', firstName: null, lastName: null, imageUrl: null }],
  matchedUsersHasMore: false };
const accountImage: AccountLookupItem['imageUrl'] = 'https://core.example.test/api/blue/v1/accounts/company/image/version';
// @ts-expect-error Image URLs are output-only; images must use the upload endpoint.
const arbitraryImage: import('../index.js').AccountProfileInput = { displayName: 'Company', imageUrl: accountImage };
void arbitraryImage;
// @ts-expect-error The singular resolve endpoint has been removed.
type OldResolve = AccountApi['resolveAccount'];
function profileResponse(api: AccountApi): Promise<UserProfile> {
  return api.updateMyProfile({ idempotencyKey: '00000000-0000-0000-0000-000000000001', updateMyProfile: clearName });
}
void [lookupSelectors, unboundedDirectory, clearName, emptyProfile, directoryItem, profileResponse];

// Account creation accepts durable source data before background registration finishes.
const pendingAccount: CreatedAccount = { accountId: 'account', agentId: 'agent', receiptId: 'receipt',
  membershipGeneration: 1, membershipRevision: 1, provisioning: 'PENDING' };
void [pendingAccount, invalidPackageSet];
