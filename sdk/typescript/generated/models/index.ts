/* tslint:disable */
/* eslint-disable */
/**
 *
 * @export
 * @interface AcceptInvitation
 */
export interface AcceptInvitation {
    /**
     *
     */
    token: string;
    /**
     *
     */
    expectedRevision: number;
}
/**
 *
 * @export
 * @interface AcceptedInvitation
 */
export interface AcceptedInvitation {
    /**
     *
     */
    invitationId: string;
    /**
     *
     */
    accountId: string;
    /**
     *
     */
    userId: string;
    /**
     *
     */
    membershipGeneration: number;
    /**
     *
     */
    membershipRevision: number;
    /**
     *
     */
    role: InvitationRole;
    /**
     *
     */
    receiptId: string;
}


/**
 *
 * @export
 * @interface AccountAgent
 */
export interface AccountAgent {
    /**
     *
     */
    accountId: string;
    /**
     *
     */
    agentId: string;
    /**
     *
     */
    kind: AccountAgentKindEnum;
    /**
     *
     */
    role: AccountAgentRoleEnum;
    /**
     *
     */
    inclusion: boolean;
    /**
     *
     */
    execution: AccountAgentExecutionEnum;
    /**
     *
     */
    actorStatus: AccountAgentActorStatusEnum;
    /**
     *
     */
    revision: number;
    /**
     * Explicitly configured and qualified native execution profiles. This capability declaration is not a worker liveness assertion.
     */
    executionProfiles?: Array<string>;
    /**
     *
     */
    authoring?: AgentAuthoringProfile;
    /**
     *
     */
    name?: string;
    /**
     *
     */
    status?: AccountAgentStatusEnum;
}


/**
 * @export
 */
export const AccountAgentKindEnum = {
    BuiltIn: 'BUILT_IN',
    External: 'EXTERNAL',
} as const;
export type AccountAgentKindEnum = typeof AccountAgentKindEnum[keyof typeof AccountAgentKindEnum];

/**
 * @export
 */
export const AccountAgentRoleEnum = {
    ReadAll: 'READ_ALL',
    ReadSelected: 'READ_SELECTED',
} as const;
export type AccountAgentRoleEnum = typeof AccountAgentRoleEnum[keyof typeof AccountAgentRoleEnum];

/**
 * @export
 */
export const AccountAgentExecutionEnum = {
    Disabled: 'DISABLED',
    Enabled: 'ENABLED',
    External: 'EXTERNAL',
} as const;
export type AccountAgentExecutionEnum = typeof AccountAgentExecutionEnum[keyof typeof AccountAgentExecutionEnum];

/**
 * @export
 */
export const AccountAgentActorStatusEnum = {
    Unqualified: 'UNQUALIFIED',
    Qualified: 'QUALIFIED',
} as const;
export type AccountAgentActorStatusEnum = typeof AccountAgentActorStatusEnum[keyof typeof AccountAgentActorStatusEnum];

/**
 * @export
 */
export const AccountAgentStatusEnum = {
    Active: 'ACTIVE',
    Paused: 'PAUSED',
} as const;
export type AccountAgentStatusEnum = typeof AccountAgentStatusEnum[keyof typeof AccountAgentStatusEnum];

/**
 *
 * @export
 * @interface AccountAgentPage
 */
export interface AccountAgentPage {
    /**
     *
     */
    items: Array<AccountAgent>;
}
/**
 *
 * @export
 * @interface AccountCapabilities
 */
export interface AccountCapabilities {
    /**
     *
     */
    manageAccount: boolean;
    /**
     *
     */
    principalWrite: boolean;
    /**
     *
     */
    accountWideRead: boolean;
    /**
     *
     */
    humanRepresentation: boolean;
    /**
     *
     */
    agentExecution: boolean;
    /**
     *
     */
    taskRuntime: boolean;
}
/**
 *
 * @export
 * @interface AccountIdentity
 */
export interface AccountIdentity {
    /**
     *
     */
    impersonation?: ImpersonationMetadata;
    /**
     *
     */
    accountId: string;
    /**
     *
     */
    kind: AccountKind;
    /**
     *
     */
    profile: AccountProfile;
    /**
     *
     */
    membership: MembershipSummary;
    /**
     *
     */
    revision: number;
    /**
     *
     */
    userId: string;
    /**
     *
     */
    email: string;
    /**
     *
     */
    sessionContext: string;
    /**
     *
     */
    sessionExpiresAt: number;
    /**
     *
     */
    accountModel: AccountIdentityAccountModelEnum;
    /**
     *
     */
    capabilities: AccountCapabilities;
    /**
     * Explicitly enabled native execution profiles; service liveness is checked separately.
     */
    executionProfiles?: Array<string>;
    /**
     *
     */
    authoring?: AgentAuthoringProfile;
}


/**
 * @export
 */
export const AccountIdentityAccountModelEnum = {
    MembershipsV1: 'MEMBERSHIPS_V1',
} as const;
export type AccountIdentityAccountModelEnum = typeof AccountIdentityAccountModelEnum[keyof typeof AccountIdentityAccountModelEnum];

/**
 *
 * @export
 * @interface AccountInvitation
 */
export interface AccountInvitation {
    /**
     *
     */
    invitationId: string;
    /**
     *
     */
    accountId: string;
    /**
     *
     */
    recipientEmail: string;
    /**
     * The specific User bound to the invitation, or null when acceptance is bound only to the verified recipient email.
     */
    recipientUserId?: string | null;
    /**
     *
     */
    role: InvitationRole;
    /**
     *
     */
    status: AccountInvitationStatusEnum;
    /**
     *
     */
    revision: number;
    /**
     *
     */
    expiresAt: number;
    /**
     *
     */
    createdAt: number;
    /**
     *
     */
    acceptedAt?: number | null;
    /**
     *
     */
    acceptedBy?: string | null;
}


/**
 * @export
 */
export const AccountInvitationStatusEnum = {
    Pending: 'PENDING',
    Accepted: 'ACCEPTED',
    Revoked: 'REVOKED',
} as const;
export type AccountInvitationStatusEnum = typeof AccountInvitationStatusEnum[keyof typeof AccountInvitationStatusEnum];

/**
 *
 * @export
 * @interface AccountInvitationPage
 */
export interface AccountInvitationPage {
    /**
     *
     */
    items: Array<AccountInvitation>;
}

/**
 *
 * @export
 */
export const AccountKind = {
    Personal: 'PERSONAL',
    Business: 'BUSINESS',
} as const;
export type AccountKind = typeof AccountKind[keyof typeof AccountKind];

/**
 *
 * @export
 * @interface AccountLookupItem
 */
export interface AccountLookupItem {
    /**
     *
     */
    accountId: string;
    /**
     *
     */
    kind: AccountKind;
    /**
     *
     */
    displayName: string;
    /**
     * Account website. HTTP(S) only; never fetched by this API. Null clears it on update; omission preserves it.
     */
    website: string | null;
    /**
     * Matching ACTIVE Users ordered by userId. Empty without an email match.
     */
    matchedUsers: Array<MatchedUser>;
    /**
     * More than ten Users match this account. Refine the email filter to narrow the preview.
     */
    matchedUsersHasMore: boolean;
    /**
     * Account logo/image. User portraits remain on matchedUsers.
     */
    imageUrl?: string | null;
}


/**
 *
 * @export
 * @interface AccountLookupPage
 */
export interface AccountLookupPage {
    /**
     *
     */
    items: Array<AccountLookupItem>;
    /**
     *
     */
    hasMore: boolean;
    /**
     *
     */
    nextPageToken: string | null;
}
/**
 *
 * @export
 * @interface AccountMember
 */
export interface AccountMember {
    /**
     * Current eligibility for ownership, rechecked atomically when the role is saved.
     */
    canOwnAccount?: boolean;
    /**
     * Current verified email of this account member.
     */
    email: string;
    /**
     *
     */
    userId: string;
    /**
     *
     */
    generation: number;
    /**
     *
     */
    revision: number;
    /**
     *
     */
    role: MembershipRole;
    /**
     *
     */
    status: AccountMemberStatusEnum;
}


/**
 * @export
 */
export const AccountMemberStatusEnum = {
    Active: 'ACTIVE',
    Removed: 'REMOVED',
} as const;
export type AccountMemberStatusEnum = typeof AccountMemberStatusEnum[keyof typeof AccountMemberStatusEnum];

/**
 *
 * @export
 * @interface AccountMemberPage
 */
export interface AccountMemberPage {
    /**
     *
     */
    items: Array<AccountMember>;
}
/**
 *
 * @export
 * @interface AccountProfile
 */
export interface AccountProfile {
    /**
     * Optional business address. Omit on update to preserve it; an empty string clears it. Unavailable for personal accounts.
     */
    address?: string;
    /**
     *
     */
    displayName: string;
    /**
     * Account website. HTTP(S) only; never fetched by this API. Null clears it on update; omission preserves it.
     */
    website?: string | null;
    /**
     * Versioned authenticated Core image URL owned by this Account, independent of User avatars. Omitted when no image is set.
     */
    readonly imageUrl?: string | null;
}
/**
 *
 * @export
 * @interface AccountProfileInput
 */
export interface AccountProfileInput {
    /**
     * Optional business address. Omit on update to preserve it; an empty string clears it. Unavailable for personal accounts.
     */
    address?: string;
    /**
     *
     */
    displayName: string;
    /**
     * Account website. HTTP(S) only; never fetched by this API. Null clears it on update; omission preserves it.
     */
    website?: string | null;
}
/**
 *
 * @export
 * @interface AccountSummary
 */
export interface AccountSummary {
    /**
     *
     */
    accountId: string;
    /**
     *
     */
    kind: AccountKind;
    /**
     *
     */
    profile: AccountProfile;
    /**
     *
     */
    membership: MembershipSummary;
}


/**
 *
 * @export
 * @interface AdminDirectorySync
 */
export interface AdminDirectorySync {
    /**
     *
     */
    baselineAt: number | null;
    /**
     *
     */
    lastCompletedAt: number | null;
    /**
     *
     */
    inProgress: boolean;
}
/**
 *
 * @export
 * @interface AdminNotifications
 */
export interface AdminNotifications {
    /**
     *
     */
    recipients: Array<string>;
    /**
     *
     */
    revision: number;
}
/**
 *
 * @export
 * @interface AdminSession
 */
export interface AdminSession {
    /**
     *
     */
    userId: string;
    /**
     *
     */
    email: string;
    /**
     *
     */
    sessionContext: string;
}
/**
 *
 * @export
 * @interface AdminUser
 */
export interface AdminUser {
    /**
     *
     */
    id: string;
    /**
     *
     */
    userId: string | null;
    /**
     *
     */
    email: string;
    /**
     *
     */
    status: string;
    /**
     *
     */
    verified: boolean;
    /**
     *
     */
    enabled: boolean;
    /**
     *
     */
    createdAt: number | null;
    /**
     *
     */
    verifiedAt: number | null;
    /**
     *
     */
    verificationObservedAt: number | null;
    /**
     *
     */
    firstName?: string | null;
    /**
     *
     */
    lastName?: string | null;
}
/**
 *
 * @export
 * @interface AdminUserDetail
 */
export interface AdminUserDetail {
    /**
     *
     */
    user: AdminUser;
    /**
     *
     */
    workspaces: Array<AdminWorkspace>;
}
/**
 *
 * @export
 * @interface AdminUserPage
 */
export interface AdminUserPage {
    /**
     *
     */
    items: Array<AdminUser>;
    /**
     *
     */
    total: number;
    /**
     *
     */
    limit: number;
    /**
     *
     */
    offset: number;
    /**
     *
     */
    sync?: AdminDirectorySync;
}
/**
 *
 * @export
 * @interface AdminWorkspace
 */
export interface AdminWorkspace {
    /**
     *
     */
    accountId: string;
    /**
     *
     */
    kind: AccountKind;
    /**
     *
     */
    status: string;
    /**
     *
     */
    role: MembershipRole;
    /**
     *
     */
    createdAt: number | null;
    /**
     *
     */
    membershipStatus?: string;
    /**
     *
     */
    profile: AccountProfile;
}


/**
 * @type Admission
 *
 * @export
 */
export type Admission = { kind: 'CONTENT' } & ContentAdmission | { kind: 'CONTENT_REMOVE' } & ContentRemoveAdmission | { kind: 'DOCUMENT' } & DocumentAdmission | { kind: 'DOCUMENT_BOOTSTRAP' } & BootstrapAdmission | { kind: 'OPERATION' } & OperationAdmission | { kind: 'PACKAGE_INSTALL' } & PackageInstallAdmission | { kind: 'PACKAGE_REMOVE' } & PackageRemoveAdmission | { kind: 'PAUSE' } & PauseAdmission | { kind: 'REMOVE' } & RemoveAdmission | { kind: 'RESUME' } & ResumeAdmission | { kind: 'RETRY' } & RetryAdmission | { kind: 'TIMELINE' } & TimelineAdmission | { kind: 'TIMELINE_ENTRY' } & TimelineEntryAdmission;
/**
 * Durable host receipt. Poll GET /admissions/{admissionId}; the HTTP Location header is independent of this JSON. SUCCEEDED proves only completionScope. FAILED is still HTTP 200 when fetched. Replay observes the same receipt. READ_SELECTED callers receive their own membership-generation receipt metadata with details null; retrieve native results through the independently authorized document and content routes. Error details are redacted for those callers.
 * @export
 * @interface AdmissionCommon
 */
export interface AdmissionCommon {
    /**
     *
     */
    admissionId: string;
    /**
     *
     */
    status: AdmissionCommonStatusEnum;
    /**
     *
     */
    problem?: AdmissionProblem;
    /**
     *
     */
    createdAt: string | null;
    /**
     *
     */
    completedAt: string | null;
    /**
     *
     */
    completionScope: AdmissionCommonCompletionScopeEnum;
}


/**
 * @export
 */
export const AdmissionCommonStatusEnum = {
    Pending: 'PENDING',
    Succeeded: 'SUCCEEDED',
    Failed: 'FAILED',
} as const;
export type AdmissionCommonStatusEnum = typeof AdmissionCommonStatusEnum[keyof typeof AdmissionCommonStatusEnum];

/**
 * @export
 */
export const AdmissionCommonCompletionScopeEnum = {
    RegistrationStored: 'REGISTRATION_STORED',
    TimelineEntryStored: 'TIMELINE_ENTRY_STORED',
    TimelineStored: 'TIMELINE_STORED',
    ContentStored: 'CONTENT_STORED',
    RetentionRemoved: 'RETENTION_REMOVED',
    ControlApplied: 'CONTROL_APPLIED',
    RegistrationUnlinked: 'REGISTRATION_UNLINKED',
    PackageActionApplied: 'PACKAGE_ACTION_APPLIED',
} as const;
export type AdmissionCommonCompletionScopeEnum = typeof AdmissionCommonCompletionScopeEnum[keyof typeof AdmissionCommonCompletionScopeEnum];


/**
 *
 * @export
 */
export const AdmissionKind = {
    DocumentBootstrap: 'DOCUMENT_BOOTSTRAP',
    Document: 'DOCUMENT',
    Operation: 'OPERATION',
    TimelineEntry: 'TIMELINE_ENTRY',
    Timeline: 'TIMELINE',
    Content: 'CONTENT',
    ContentRemove: 'CONTENT_REMOVE',
    Pause: 'PAUSE',
    Resume: 'RESUME',
    Retry: 'RETRY',
    Remove: 'REMOVE',
    PackageInstall: 'PACKAGE_INSTALL',
    PackageRemove: 'PACKAGE_REMOVE',
} as const;
export type AdmissionKind = typeof AdmissionKind[keyof typeof AdmissionKind];

/**
 * Live listing; inserts or updates between pages may affect membership. Tokens are bound to account/resource/filters/limit.
 * @export
 * @interface AdmissionPage
 */
export interface AdmissionPage {
    /**
     *
     */
    items: Array<Admission>;
    /**
     *
     */
    nextPageToken: string | null;
    /**
     *
     */
    paginationMode?: string;
    /**
     *
     */
    total?: number;
    /**
     *
     */
    hasMore?: boolean;
}
/**
 *
 * @export
 * @interface AdmissionProblem
 */
export interface AdmissionProblem {
    /**
     *
     */
    type: string;
    /**
     *
     */
    status: number;
    /**
     *
     */
    code: string;
    /**
     *
     */
    title: string;
    /**
     *
     */
    detail: string;
}
/**
 * New native admission or changed native status. ref.admissionId is the host receipt identity.
 * @export
 * @interface AdmissionStatusEvent
 */
export interface AdmissionStatusEvent {
    /**
     *
     */
    id: string;
    /**
     *
     */
    sequence: number;
    /**
     *
     */
    created: string;
    /**
     *
     */
    accountId: string;
    /**
     *
     */
    type: AdmissionStatusEventTypeEnum;
    /**
     *
     */
    ref: AdmissionStatusEventAllOfRef;
    /**
     *
     */
    object: AdmissionStatusEventPayload;
}


/**
 * @export
 */
export const AdmissionStatusEventTypeEnum = {
    AdmissionStatusChanged: 'ADMISSION_STATUS_CHANGED',
} as const;
export type AdmissionStatusEventTypeEnum = typeof AdmissionStatusEventTypeEnum[keyof typeof AdmissionStatusEventTypeEnum];

/**
 *
 * @export
 * @interface AdmissionStatusEventAllOfRef
 */
export interface AdmissionStatusEventAllOfRef {
    /**
     *
     */
    admissionId: string;
}
/**
 * Admission status, action, result and problem at publication time. Event status values are lowercase; Admission resource statuses are uppercase.
 * @export
 * @interface AdmissionStatusEventPayload
 */
export interface AdmissionStatusEventPayload {
    /**
     *
     */
    status: AdmissionStatusEventPayloadStatusEnum;
    /**
     *
     */
    action: string;
    /**
     * Result recorded when the event was published. Its fields differ from Admission.details and may include id and location.
     */
    result: object | null;
    /**
     *
     */
    problem: object | null;
}


/**
 * @export
 */
export const AdmissionStatusEventPayloadStatusEnum = {
    Pending: 'pending',
    Succeeded: 'succeeded',
    Failed: 'failed',
} as const;
export type AdmissionStatusEventPayloadStatusEnum = typeof AdmissionStatusEventPayloadStatusEnum[keyof typeof AdmissionStatusEventPayloadStatusEnum];

/**
 *
 * @export
 * @interface AgentAuthoringProfile
 */
export interface AgentAuthoringProfile {
    /**
     *
     */
    profile: AgentAuthoringProfileProfileEnum;
    /**
     * SHA-256 of the trusted document authoring kit configured for this execution profile.
     */
    bundleSha256: string;
}


/**
 * @export
 */
export const AgentAuthoringProfileProfileEnum = {
    MyosDocumentAuthorV1: 'myos-document-author-v1',
} as const;
export type AgentAuthoringProfileProfileEnum = typeof AgentAuthoringProfileProfileEnum[keyof typeof AgentAuthoringProfileProfileEnum];

/**
 *
 * @export
 * @interface AgentConnection
 */
export interface AgentConnection {
    /**
     *
     */
    connectionId: string;
    /**
     *
     */
    agentId: string;
    /**
     *
     */
    clientId: string;
    /**
     *
     */
    status: AgentConnectionStatusEnum;
    /**
     *
     */
    scopes: Array<string>;
    /**
     *
     */
    revision: number;
    /**
     *
     */
    approvedAt?: number;
    /**
     *
     */
    revokedAt?: number;
}


/**
 * @export
 */
export const AgentConnectionStatusEnum = {
    Active: 'ACTIVE',
    Revoked: 'REVOKED',
} as const;
export type AgentConnectionStatusEnum = typeof AgentConnectionStatusEnum[keyof typeof AgentConnectionStatusEnum];

/**
 *
 * @export
 * @interface AgentConnectionPage
 */
export interface AgentConnectionPage {
    /**
     *
     */
    items: Array<AgentConnection>;
}
/**
 * Select exact instances, or account scope to include future readable documents. These selections are mutually exclusive.
 * @export
 * @interface AgentEventArguments
 */
export interface AgentEventArguments {
    /**
     *
     */
    scope?: AgentEventArgumentsScopeEnum;
    /**
     *
     */
    instanceIds?: Array<string>;
}


/**
 * @export
 */
export const AgentEventArgumentsScopeEnum = {
    Account: 'account',
} as const;
export type AgentEventArgumentsScopeEnum = typeof AgentEventArgumentsScopeEnum[keyof typeof AgentEventArgumentsScopeEnum];

/**
 *
 * @export
 * @interface AgentEventDelivery
 */
export interface AgentEventDelivery {
    /**
     *
     */
    mode: AgentEventDeliveryModeEnum;
    /**
     *
     */
    url: string;
}


/**
 * @export
 */
export const AgentEventDeliveryModeEnum = {
    Webhook: 'webhook',
} as const;
export type AgentEventDeliveryModeEnum = typeof AgentEventDeliveryModeEnum[keyof typeof AgentEventDeliveryModeEnum];

/**
 *
 * @export
 * @interface AgentEventSubscribeDelivery
 */
export interface AgentEventSubscribeDelivery {
    /**
     *
     */
    mode: AgentEventSubscribeDeliveryModeEnum;
    /**
     *
     */
    url: string;
    /**
     * Provider-issued Standard Webhooks secret. Never place it in a tool or document.
     */
    secret: string;
}


/**
 * @export
 */
export const AgentEventSubscribeDeliveryModeEnum = {
    Webhook: 'webhook',
} as const;
export type AgentEventSubscribeDeliveryModeEnum = typeof AgentEventSubscribeDeliveryModeEnum[keyof typeof AgentEventSubscribeDeliveryModeEnum];

/**
 *
 * @export
 * @interface AgentEventSubscription
 */
export interface AgentEventSubscription {
    /**
     *
     */
    id: string;
    /**
     *
     */
    refreshBefore: string;
    /**
     *
     */
    cursor: string | null;
    /**
     *
     */
    truncated: boolean;
}
/**
 *
 * @export
 * @interface AgentRunArtifactFile
 */
export interface AgentRunArtifactFile {
    /**
     *
     */
    fileName: AgentRunArtifactFileFileNameEnum;
    /**
     *
     */
    sha256: string;
    /**
     *
     */
    sizeBytes: number;
}


/**
 * @export
 */
export const AgentRunArtifactFileFileNameEnum = {
    DocumentBlue: 'document.blue',
    ResultJson: 'result.json',
    EvidenceZip: 'evidence.zip',
} as const;
export type AgentRunArtifactFileFileNameEnum = typeof AgentRunArtifactFileFileNameEnum[keyof typeof AgentRunArtifactFileFileNameEnum];

/**
 *
 * @export
 * @interface ApiKey
 */
export interface ApiKey {
    /**
     *
     */
    id: string;
    /**
     *
     */
    name: string;
    /**
     *
     */
    keyPrefix: string;
    /**
     *
     */
    status: ApiKeyStatusEnum;
    /**
     *
     */
    created: string;
    /**
     *
     */
    revoked: string | null;
    /**
     *
     */
    membershipGeneration: number;
    /**
     *
     */
    effective: boolean;
}


/**
 * @export
 */
export const ApiKeyStatusEnum = {
    Active: 'active',
    Revoked: 'revoked',
} as const;
export type ApiKeyStatusEnum = typeof ApiKeyStatusEnum[keyof typeof ApiKeyStatusEnum];

/**
 * All of the current User's API keys for the selected account. No pagination or silent truncation.
 * @export
 * @interface ApiKeyPage
 */
export interface ApiKeyPage {
    /**
     *
     */
    items: Array<ApiKey>;
}
/**
 * Direct Operation Request messages require documentId naming the original lineage. Ordinary messages need no artificial target. Exact/full references are not rebuilt from latest document state.
 * @export
 * @interface AppendEntry
 */
export interface AppendEntry {
    /**
     *
     */
    inputMode?: InputMode;
    /**
     *
     */
    documentId?: string;
    /**
     *
     */
    actor?: string;
    /**
     * Omitted means no predecessor assertion; null requires an empty timeline; string must equal its current head. Replay keeps the original committed entry.
     */
    prevEntry?: string | null;
    /**
     * Native authority BlueId only; target mandateRef objects are unsupported.
     */
    authority?: string;
    /**
     * Complete Blue value. A singleton blueId object references an accessible existing exact value; objects with blueId and other fields follow native Blue validation without whole-value lookup. Nested references remain inside the value. Scalar strings are literal text. Fields named mode, format and text are ordinary domain fields here.
     */
    message: any | null;
}


/**
 * Live listing; inserts or updates between pages may affect membership. Tokens are bound to account/resource/filters/limit.
 * @export
 * @interface AttemptPage
 */
export interface AttemptPage {
    /**
     *
     */
    items: Array<DeliveryAttempt>;
    /**
     *
     */
    nextPageToken: string | null;
    /**
     *
     */
    paginationMode?: string;
    /**
     *
     */
    total?: number;
    /**
     *
     */
    hasMore?: boolean;
}
/**
 *
 * @export
 * @interface AuthenticatedIdentity
 */
export interface AuthenticatedIdentity {
    /**
     *
     */
    kind: AuthenticatedIdentityKindEnum;
    /**
     * User ID for HUMAN, Agent ID for AGENT. Never an impersonated identity.
     */
    subjectId: string;
    /**
     * Bound account for an Agent or account-scoped credential; otherwise null.
     */
    accountId: string | null;
    /**
     *
     */
    externalConnection?: ExternalConnectionIdentity;
}


/**
 * @export
 */
export const AuthenticatedIdentityKindEnum = {
    Human: 'HUMAN',
    Agent: 'AGENT',
} as const;
export type AuthenticatedIdentityKindEnum = typeof AuthenticatedIdentityKindEnum[keyof typeof AuthenticatedIdentityKindEnum];

/**
 *
 * @export
 * @interface BlueIdentityEventPayload
 */
export interface BlueIdentityEventPayload {
    /**
     *
     */
    blueId: string;
}
/**
 *
 * @export
 * @interface BlueReference
 */
export interface BlueReference {
    /**
     *
     */
    blueId: string;
}
/**
 *
 * @export
 * @interface BootstrapAdmission
 */
export interface BootstrapAdmission {
    /**
     *
     */
    admissionId: string;
    /**
     *
     */
    status: BootstrapAdmissionStatusEnum;
    /**
     *
     */
    problem?: AdmissionProblem;
    /**
     *
     */
    createdAt: string | null;
    /**
     *
     */
    completedAt: string | null;
    /**
     *
     */
    completionScope: BootstrapAdmissionCompletionScopeEnum;
    /**
     *
     */
    kind: BootstrapAdmissionKindEnum;
    /**
     *
     */
    details: BootstrapAdmissionAllOfDetails | null;
}


/**
 * @export
 */
export const BootstrapAdmissionStatusEnum = {
    Pending: 'PENDING',
    Succeeded: 'SUCCEEDED',
    Failed: 'FAILED',
} as const;
export type BootstrapAdmissionStatusEnum = typeof BootstrapAdmissionStatusEnum[keyof typeof BootstrapAdmissionStatusEnum];

/**
 * @export
 */
export const BootstrapAdmissionCompletionScopeEnum = {
    RegistrationStored: 'REGISTRATION_STORED',
} as const;
export type BootstrapAdmissionCompletionScopeEnum = typeof BootstrapAdmissionCompletionScopeEnum[keyof typeof BootstrapAdmissionCompletionScopeEnum];

/**
 * @export
 */
export const BootstrapAdmissionKindEnum = {
    DocumentBootstrap: 'DOCUMENT_BOOTSTRAP',
} as const;
export type BootstrapAdmissionKindEnum = typeof BootstrapAdmissionKindEnum[keyof typeof BootstrapAdmissionKindEnum];

/**
 *
 * @export
 * @interface BootstrapAdmissionAllOfDetails
 */
export interface BootstrapAdmissionAllOfDetails {
    /**
     *
     */
    sessionId: string;
}
/**
 *
 * @export
 * @interface BootstrapDocument
 */
export interface BootstrapDocument {
    /**
     * Optional complete exact selection returned by info.registrationExecutionSelections. Selects only this new registration; omission preserves the account default. All bootstrap participants must acknowledge the same selection before timeline resources are created. Existing source or version registrations with incompatible execution are rejected.
     */
    executionSelection?: RuntimeIdentities;
    /**
     * Exact package BlueIds from the verified catalogue. Installs their dependency closure and uses these definitions to resolve source type names. Version aliases are not accepted.
     */
    packages?: Array<string>;
    /**
     *
     */
    document: DocumentInput;
    /**
     *
     */
    inputMode?: InputMode;
    /**
     * Complete mappings for unbound participant channels. Each selects an existing accountId and optionally a human representativeId or singleton agentId. The initiating account must be a participant. Typed Parties in /parties and Activation can declare their own accounts; custom current-document Party positions require partyPath. Actual bootstrap adds each Party account's own singleton when its saved inclusion setting is enabled, preserving explicit human representatives. Accepted replay does not consult changed settings.
     */
    participantMappings?: Array<BootstrapParticipantMapping> | null;
}


/**
 * @type BootstrapParticipantMapping
 *
 * @export
 */
export type BootstrapParticipantMapping = BootstrapParticipantMappingOneOf | BootstrapParticipantMappingOneOf1;
/**
 *
 * @export
 * @interface BootstrapParticipantMappingOneOf
 */
export interface BootstrapParticipantMappingOneOf {
    /**
     *
     */
    channelName: string;
    /**
     *
     */
    accountId: string;
    /**
     * Resolves the account owned by this verified or invited identity. Mutually exclusive with accountId. A personal account is created when the identity has no owned account; unknown recipients are invited to sign up and verify this email. Resolution is pinned for retries.
     */
    email?: string;
    /**
     * Stable active human User ID in the selected account. Creates a human writer binding; selected-read access additionally requires the qualified frozen human-representation profile and native approval/access evidence. Mutually exclusive with agentId.
     */
    representativeId?: string;
    /**
     * The selected account's own singleton Agent ID. Creates a distinct Agent writer, never human or account-principal authority. Mutually exclusive with representativeId. Explicit assignment is independent of the automatic inclusion setting.
     */
    agentId?: string;
    /**
     * Native path to a typed Party in the current document; the empty string selects a root Party. Required for custom Party locations. Root /parties and Activation rosters bind automatically. Independently processed children and executable recipes are outside this scope.
     */
    partyPath?: string;
}
/**
 *
 * @export
 * @interface BootstrapParticipantMappingOneOf1
 */
export interface BootstrapParticipantMappingOneOf1 {
    /**
     *
     */
    channelName: string;
    /**
     *
     */
    accountId?: string;
    /**
     * Resolves the account owned by this verified or invited identity. Mutually exclusive with accountId. A personal account is created when the identity has no owned account; unknown recipients are invited to sign up and verify this email. Resolution is pinned for retries.
     */
    email: string;
    /**
     * Stable active human User ID in the selected account. Creates a human writer binding; selected-read access additionally requires the qualified frozen human-representation profile and native approval/access evidence. Mutually exclusive with agentId.
     */
    representativeId?: string;
    /**
     * The selected account's own singleton Agent ID. Creates a distinct Agent writer, never human or account-principal authority. Mutually exclusive with representativeId. Explicit assignment is independent of the automatic inclusion setting.
     */
    agentId?: string;
    /**
     * Native path to a typed Party in the current document; the empty string selects a root Party. Required for custom Party locations. Root /parties and Activation rosters bind automatically. Independently processed children and executable recipes are outside this scope.
     */
    partyPath?: string;
}
/**
 *
 * @export
 * @interface BootstrapParticipantMappingOneOfNot
 */
export interface BootstrapParticipantMappingOneOfNot {
}
/**
 *
 * @export
 * @interface BootstrapRecipient
 */
export interface BootstrapRecipient {
    /**
     *
     */
    accountId: string;
    /**
     *
     */
    sessionId: string | null;
    /**
     *
     */
    roles: Array<BootstrapRecipientRole>;
}
/**
 *
 * @export
 * @interface BootstrapRecipientRole
 */
export interface BootstrapRecipientRole {
    /**
     *
     */
    name: string;
    /**
     *
     */
    kind: BootstrapRecipientRoleKindEnum;
}


/**
 * @export
 */
export const BootstrapRecipientRoleKindEnum = {
    Participant: 'PARTICIPANT',
    Reader: 'READER',
} as const;
export type BootstrapRecipientRoleKindEnum = typeof BootstrapRecipientRoleKindEnum[keyof typeof BootstrapRecipientRoleKindEnum];

/**
 *
 * @export
 * @interface BootstrapRecipients
 */
export interface BootstrapRecipients {
    /**
     *
     */
    admissionId: string;
    /**
     *
     */
    recipients: Array<BootstrapRecipient>;
}
/**
 *
 * @export
 * @interface Channel
 */
export interface Channel {
    /**
     *
     */
    member: string;
    /**
     *
     */
    channel: string;
    /**
     *
     */
    generation: number;
    /**
     *
     */
    timeline: string;
    /**
     *
     */
    actor: string;
}
/**
 *
 * @export
 * @interface ChannelPage
 */
export interface ChannelPage {
    /**
     *
     */
    items: Array<Channel>;
    /**
     *
     */
    nextPageToken: string | null;
    /**
     *
     */
    paginationMode: ChannelPagePaginationModeEnum;
    /**
     *
     */
    total?: number;
}


/**
 * @export
 */
export const ChannelPagePaginationModeEnum = {
    Live: 'LIVE',
} as const;
export type ChannelPagePaginationModeEnum = typeof ChannelPagePaginationModeEnum[keyof typeof ChannelPagePaginationModeEnum];

/**
 *
 * @export
 * @interface ContentAdmission
 */
export interface ContentAdmission {
    /**
     *
     */
    admissionId: string;
    /**
     *
     */
    status: ContentAdmissionStatusEnum;
    /**
     *
     */
    problem?: AdmissionProblem;
    /**
     *
     */
    createdAt: string | null;
    /**
     *
     */
    completedAt: string | null;
    /**
     *
     */
    completionScope: ContentAdmissionCompletionScopeEnum;
    /**
     *
     */
    kind: ContentAdmissionKindEnum;
    /**
     *
     */
    details: ContentAdmissionDetails | null;
}


/**
 * @export
 */
export const ContentAdmissionStatusEnum = {
    Pending: 'PENDING',
    Succeeded: 'SUCCEEDED',
    Failed: 'FAILED',
} as const;
export type ContentAdmissionStatusEnum = typeof ContentAdmissionStatusEnum[keyof typeof ContentAdmissionStatusEnum];

/**
 * @export
 */
export const ContentAdmissionCompletionScopeEnum = {
    ContentStored: 'CONTENT_STORED',
} as const;
export type ContentAdmissionCompletionScopeEnum = typeof ContentAdmissionCompletionScopeEnum[keyof typeof ContentAdmissionCompletionScopeEnum];

/**
 * @export
 */
export const ContentAdmissionKindEnum = {
    Content: 'CONTENT',
} as const;
export type ContentAdmissionKindEnum = typeof ContentAdmissionKindEnum[keyof typeof ContentAdmissionKindEnum];

/**
 *
 * @export
 * @interface ContentAdmissionDetails
 */
export interface ContentAdmissionDetails {
    /**
     *
     */
    contentRef: BlueReference;
}
/**
 * New explicit content retention metadata; not every internal content publication.
 * @export
 * @interface ContentCreatedEvent
 */
export interface ContentCreatedEvent {
    /**
     *
     */
    id: string;
    /**
     *
     */
    sequence: number;
    /**
     *
     */
    created: string;
    /**
     *
     */
    accountId: string;
    /**
     *
     */
    type: ContentCreatedEventTypeEnum;
    /**
     *
     */
    ref: ContentCreatedEventAllOfRef;
    /**
     *
     */
    object: NamedResourceEventPayload;
}


/**
 * @export
 */
export const ContentCreatedEventTypeEnum = {
    ContentCreated: 'CONTENT_CREATED',
} as const;
export type ContentCreatedEventTypeEnum = typeof ContentCreatedEventTypeEnum[keyof typeof ContentCreatedEventTypeEnum];

/**
 *
 * @export
 * @interface ContentCreatedEventAllOfRef
 */
export interface ContentCreatedEventAllOfRef {
    /**
     *
     */
    blueId: string;
}
/**
 * Explicit retention metadata; createdAt is not a guaranteed immutable first-upload time.
 * @export
 * @interface ContentMetadata
 */
export interface ContentMetadata {
    /**
     *
     */
    blueId: string;
    /**
     *
     */
    name?: string | null;
    /**
     *
     */
    createdAt?: string | null;
    /**
     *
     */
    lastChangedAt?: string | null;
}
/**
 * Live listing; inserts or updates between pages may affect membership. Tokens are bound to account/resource/filters/limit.
 * @export
 * @interface ContentPage
 */
export interface ContentPage {
    /**
     *
     */
    items: Array<ContentMetadata>;
    /**
     *
     */
    nextPageToken: string | null;
    /**
     *
     */
    paginationMode?: string;
    /**
     *
     */
    total?: number;
    /**
     *
     */
    hasMore?: boolean;
}
/**
 *
 * @export
 * @interface ContentRemoveAdmission
 */
export interface ContentRemoveAdmission {
    /**
     *
     */
    admissionId: string;
    /**
     *
     */
    status: ContentRemoveAdmissionStatusEnum;
    /**
     *
     */
    problem?: AdmissionProblem;
    /**
     *
     */
    createdAt: string | null;
    /**
     *
     */
    completedAt: string | null;
    /**
     *
     */
    completionScope: ContentRemoveAdmissionCompletionScopeEnum;
    /**
     *
     */
    kind: ContentRemoveAdmissionKindEnum;
    /**
     *
     */
    details: ContentRemoveAdmissionDetails | null;
}


/**
 * @export
 */
export const ContentRemoveAdmissionStatusEnum = {
    Pending: 'PENDING',
    Succeeded: 'SUCCEEDED',
    Failed: 'FAILED',
} as const;
export type ContentRemoveAdmissionStatusEnum = typeof ContentRemoveAdmissionStatusEnum[keyof typeof ContentRemoveAdmissionStatusEnum];

/**
 * @export
 */
export const ContentRemoveAdmissionCompletionScopeEnum = {
    RetentionRemoved: 'RETENTION_REMOVED',
} as const;
export type ContentRemoveAdmissionCompletionScopeEnum = typeof ContentRemoveAdmissionCompletionScopeEnum[keyof typeof ContentRemoveAdmissionCompletionScopeEnum];

/**
 * @export
 */
export const ContentRemoveAdmissionKindEnum = {
    ContentRemove: 'CONTENT_REMOVE',
} as const;
export type ContentRemoveAdmissionKindEnum = typeof ContentRemoveAdmissionKindEnum[keyof typeof ContentRemoveAdmissionKindEnum];

/**
 *
 * @export
 * @interface ContentRemoveAdmissionDetails
 */
export interface ContentRemoveAdmissionDetails {
    /**
     *
     */
    contentRef: BlueReference;
    /**
     *
     */
    explicitRetention: ContentRemoveAdmissionDetailsExplicitRetentionEnum;
}


/**
 * @export
 */
export const ContentRemoveAdmissionDetailsExplicitRetentionEnum = {
    False: false,
} as const;
export type ContentRemoveAdmissionDetailsExplicitRetentionEnum = typeof ContentRemoveAdmissionDetailsExplicitRetentionEnum[keyof typeof ContentRemoveAdmissionDetailsExplicitRetentionEnum];

/**
 *
 * @export
 * @interface ControlAdmissionDetails
 */
export interface ControlAdmissionDetails {
    /**
     *
     */
    sessionId: string;
}
/**
 *
 * @export
 * @interface ControlEventPayload
 */
export interface ControlEventPayload {
    /**
     *
     */
    admissionId: string;
}
/**
 *
 * @export
 * @interface CreateAccount
 */
export interface CreateAccount {
    /**
     *
     */
    kind: AccountKind;
    /**
     *
     */
    profile: AccountProfileInput;
}


/**
 *
 * @export
 * @interface CreateAccountAgent
 */
export interface CreateAccountAgent {
    /**
     *
     */
    name: string;
    /**
     *
     */
    role?: CreateAccountAgentRoleEnum;
}


/**
 * @export
 */
export const CreateAccountAgentRoleEnum = {
    ReadSelected: 'READ_SELECTED',
    ReadAll: 'READ_ALL',
} as const;
export type CreateAccountAgentRoleEnum = typeof CreateAccountAgentRoleEnum[keyof typeof CreateAccountAgentRoleEnum];

/**
 *
 * @export
 * @interface CreateApiKey
 */
export interface CreateApiKey {
    /**
     *
     */
    name?: string;
}
/**
 * Definition goal preserves required slots for later instances for source, exact and reference inputs.
 * @export
 * @interface CreateContent
 */
export interface CreateContent {
    /**
     *
     */
    inputMode?: InputMode;
    /**
     * Complete Blue value. A singleton blueId object references an accessible existing exact value; objects with blueId and other fields follow native Blue validation without whole-value lookup. Nested references remain inside the value. Scalar strings are literal text. Fields named mode, format and text are ordinary domain fields here.
     */
    content: any | null;
    /**
     *
     */
    goal?: CreateContentGoalEnum;
}


/**
 * @export
 */
export const CreateContentGoalEnum = {
    Value: 'value',
    Definition: 'definition',
} as const;
export type CreateContentGoalEnum = typeof CreateContentGoalEnum[keyof typeof CreateContentGoalEnum];

/**
 * Registers one document. document accepts a Blue object or serialized JSON/YAML document text. inputMode defaults to source; exact preserves the supplied value. One document per request; bulk submissions are unsupported.
 * @export
 * @interface CreateDocument
 */
export interface CreateDocument {
    /**
     * Optional complete exact selection returned by info.registrationExecutionSelections. Selects only this new registration; omission preserves the account default. All bootstrap participants must acknowledge the same selection before timeline resources are created. Existing source or version registrations with incompatible execution are rejected.
     */
    executionSelection?: RuntimeIdentities;
    /**
     *
     */
    inputMode?: InputMode;
    /**
     *
     */
    document: DocumentInput;
    /**
     *
     */
    name?: string;
}


/**
 *
 * @export
 * @interface CreateInvitation
 */
export interface CreateInvitation {
    /**
     *
     */
    email: string;
    /**
     *
     */
    role: InvitationRole;
    /**
     *
     */
    targetUserId?: string;
}


/**
 * Host allocates the timeline identity. public and custom timeline IDs are unsupported.
 * @export
 * @interface CreateTimeline
 */
export interface CreateTimeline {
    /**
     *
     */
    name?: string;
    /**
     *
     */
    actor?: string;
}
/**
 *
 * @export
 * @interface CreatedAccount
 */
export interface CreatedAccount {
    /**
     *
     */
    accountId: string;
    /**
     *
     */
    membershipGeneration: number;
    /**
     *
     */
    membershipRevision: number;
    /**
     *
     */
    agentId: string;
    /**
     * PENDING means the account data is saved and background preparation is incomplete. READY means registration and authorization preparation completed and the account can be selected.
     */
    provisioning: CreatedAccountProvisioningEnum;
    /**
     *
     */
    receiptId: string;
}


/**
 * @export
 */
export const CreatedAccountProvisioningEnum = {
    Pending: 'PENDING',
    Ready: 'READY',
} as const;
export type CreatedAccountProvisioningEnum = typeof CreatedAccountProvisioningEnum[keyof typeof CreatedAccountProvisioningEnum];

/**
 *
 * @export
 * @interface CreatedApiKey
 */
export interface CreatedApiKey {
    /**
     *
     */
    apiKey: ApiKey;
    /**
     *
     */
    secret: string;
}
/**
 *
 * @export
 * @interface CreatedWebhook
 */
export interface CreatedWebhook {
    /**
     *
     */
    id: string;
    /**
     *
     */
    name?: string;
    /**
     *
     */
    url: string;
    /**
     *
     */
    status: CreatedWebhookStatusEnum;
    /**
     *
     */
    events: Array<WebhookSelector>;
    /**
     *
     */
    filters: WebhookFilters;
    /**
     *
     */
    callbackProtocol: CreatedWebhookCallbackProtocolEnum;
    /**
     *
     */
    createdAt?: string | null;
    /**
     *
     */
    updatedAt?: string | null;
    /**
     * Shown only for creation and valid creation replay; never returned by configuration reads.
     */
    signingSecret: string;
}


/**
 * @export
 */
export const CreatedWebhookStatusEnum = {
    Active: 'ACTIVE',
    Inactive: 'INACTIVE',
} as const;
export type CreatedWebhookStatusEnum = typeof CreatedWebhookStatusEnum[keyof typeof CreatedWebhookStatusEnum];

/**
 * @export
 */
export const CreatedWebhookCallbackProtocolEnum = {
    NativeV1: 'native-v1',
} as const;
export type CreatedWebhookCallbackProtocolEnum = typeof CreatedWebhookCallbackProtocolEnum[keyof typeof CreatedWebhookCallbackProtocolEnum];

/**
 * Native owning-set proof and body. Retain at its exact BlueId; do not discard proof when exporting a cyclic value.
 * @export
 * @interface CyclicEvidence
 */
export interface CyclicEvidence {
    /**
     *
     */
    proof: CyclicEvidenceProof;
    /**
     *
     */
    body: string;
}
/**
 * @type CyclicEvidenceProof
 * Native proof encoding is preserved, whether serialized text or an object.
 * @export
 */
export type CyclicEvidenceProof = object | string;
/**
 *
 * @export
 * @interface DecodedCacheStatistics
 */
export interface DecodedCacheStatistics {
    /**
     *
     */
    entries: number;
    /**
     *
     */
    estimatedBytes: number;
    /**
     *
     */
    budgetBytes: number;
    /**
     *
     */
    hits: number;
    /**
     *
     */
    misses: number;
    /**
     *
     */
    evictions: number;
    /**
     *
     */
    projectionReads: number;
    /**
     *
     */
    fingerprintNanos: number;
    /**
     *
     */
    rowEncodeNanos: number;
    /**
     *
     */
    retentionNanos: number;
    /**
     *
     */
    physicalContent: PhysicalContentCache;
}
/**
 *
 * @export
 * @interface DeleteAccountImage
 */
export interface DeleteAccountImage {
    /**
     *
     */
    expectedRevision: number;
}
/**
 *
 * @export
 * @interface DeleteMyAvatar
 */
export interface DeleteMyAvatar {
    /**
     *
     */
    expectedRevision: number;
}
/**
 * Retained webhookId/eventId delivery, available after staging. id identifies the delivery separately from eventId. Six delivery states describe transport, not receiver business completion. Reads survive configuration tombstones.
 * @export
 * @interface Delivery
 */
export interface Delivery {
    /**
     *
     */
    id: string;
    /**
     *
     */
    eventId: string;
    /**
     *
     */
    webhookId: string;
    /**
     *
     */
    status: DeliveryStatusEnum;
    /**
     *
     */
    attempts?: number;
    /**
     *
     */
    createdAt?: string | null;
    /**
     *
     */
    updatedAt?: string | null;
    /**
     *
     */
    responseStatus?: number | null;
    /**
     *
     */
    error?: string | null;
}


/**
 * @export
 */
export const DeliveryStatusEnum = {
    Pending: 'PENDING',
    Sending: 'SENDING',
    Retrying: 'RETRYING',
    Succeeded: 'SUCCEEDED',
    Failed: 'FAILED',
    Canceled: 'CANCELED',
} as const;
export type DeliveryStatusEnum = typeof DeliveryStatusEnum[keyof typeof DeliveryStatusEnum];

/**
 * Retained native attempt facts. Unknown outcomes after a crash are retained, not inferred successful.
 * @export
 * @interface DeliveryAttempt
 */
export interface DeliveryAttempt {
    /**
     *
     */
    deliveryId: string;
    /**
     *
     */
    number: number;
    /**
     *
     */
    status: DeliveryAttemptStatusEnum;
    /**
     *
     */
    startedAt?: string | null;
    /**
     *
     */
    completedAt?: string | null;
    /**
     *
     */
    responseStatus?: number | null;
    /**
     *
     */
    id: string;
    /**
     *
     */
    error?: string | null;
}


/**
 * @export
 */
export const DeliveryAttemptStatusEnum = {
    Started: 'STARTED',
    Unknown: 'UNKNOWN',
    Succeeded: 'SUCCEEDED',
    Failed: 'FAILED',
    Canceled: 'CANCELED',
} as const;
export type DeliveryAttemptStatusEnum = typeof DeliveryAttemptStatusEnum[keyof typeof DeliveryAttemptStatusEnum];

/**
 * Delivery selected by webhookId/eventId, its retained native event, and retained attempts. Internal scheduling and lease fields are excluded.
 * @export
 * @interface DeliveryDetail
 */
export interface DeliveryDetail {
    /**
     *
     */
    delivery: Delivery;
    /**
     *
     */
    event: Event;
    /**
     *
     */
    attempts: Array<DeliveryAttempt>;
}
/**
 * Live listing; inserts or updates between pages may affect membership. Tokens are bound to account/resource/filters/limit.
 * @export
 * @interface DeliveryPage
 */
export interface DeliveryPage {
    /**
     *
     */
    items: Array<Delivery>;
    /**
     *
     */
    nextPageToken: string | null;
    /**
     *
     */
    paginationMode?: string;
    /**
     *
     */
    total?: number;
    /**
     *
     */
    hasMore?: boolean;
}
/**
 * Cumulative native diagnostics. Nested and concurrent timing spans overlap and must not be summed as exclusive elapsed time.
 * @export
 * @interface Diagnostics
 */
export interface Diagnostics {
    /**
     * Named cumulative counters; keys are created when the corresponding activity occurs.
     */
    metrics: { [key: string]: number; };
    /**
     * Named cumulative counters; keys are created when the corresponding activity occurs.
     */
    coordination: { [key: string]: number; };
    /**
     *
     */
    storage: StorageStatistics;
    /**
     * Named cumulative counters; keys are created when the corresponding activity occurs.
     */
    host: { [key: string]: number; };
    /**
     *
     */
    eventSequence: number;
    /**
     *
     */
    observation: PublicationObservation;
}
/**
 * sessionId identifies a registration, instanceId the running document instance, and documentId its lineage. lastStable references identify a single stable version. This resource contains metadata; retrieve content separately.
 * @export
 * @interface Document
 */
export interface Document {
    /**
     * Declared type of the last stable version, when available.
     */
    typeBlueId?: string | null;
    /**
     * Root-to-type BlueIds separated and terminated by slash; metadata, not document content.
     */
    typePath?: string | null;
    /**
     * PENDING precedes stable metadata; UNAVAILABLE means exact ancestry could not be resolved.
     */
    typeStatus?: DocumentTypeStatusEnum;
    /**
     *
     */
    sessionId: string;
    /**
     *
     */
    instanceId: string;
    /**
     *
     */
    documentId: string | null;
    /**
     *
     */
    name: string;
    /**
     * Native description from the last stable publication, never generated overview.
     */
    description?: string | null;
    /**
     *
     */
    createdAt: string | null;
    /**
     *
     */
    lastStable: LastStable | null;
    /**
     * Immediate native control state. Core does not produce PAUSING.
     */
    control: DocumentControlEnum;
    /**
     * NOT_INITIALIZED means no initial stable publication is retained.
     */
    lifecycle: DocumentLifecycleEnum;
    /**
     *
     */
    processing: Processing;
    /**
     * Observed activity time when available; null when no dated fact is retained.
     */
    lastChangedAt: string | null;
    /**
     * Whether this document has a registration that can be unlinked.
     */
    canUnlink: boolean;
}


/**
 * @export
 */
export const DocumentTypeStatusEnum = {
    Indexed: 'INDEXED',
    Untyped: 'UNTYPED',
    Pending: 'PENDING',
    Unavailable: 'UNAVAILABLE',
} as const;
export type DocumentTypeStatusEnum = typeof DocumentTypeStatusEnum[keyof typeof DocumentTypeStatusEnum];

/**
 * @export
 */
export const DocumentControlEnum = {
    Running: 'RUNNING',
    Paused: 'PAUSED',
} as const;
export type DocumentControlEnum = typeof DocumentControlEnum[keyof typeof DocumentControlEnum];

/**
 * @export
 */
export const DocumentLifecycleEnum = {
    NotInitialized: 'NOT_INITIALIZED',
    Active: 'ACTIVE',
    Terminated: 'TERMINATED',
} as const;
export type DocumentLifecycleEnum = typeof DocumentLifecycleEnum[keyof typeof DocumentLifecycleEnum];

/**
 *
 * @export
 * @interface DocumentAdmission
 */
export interface DocumentAdmission {
    /**
     *
     */
    admissionId: string;
    /**
     *
     */
    status: DocumentAdmissionStatusEnum;
    /**
     *
     */
    problem?: AdmissionProblem;
    /**
     *
     */
    createdAt: string | null;
    /**
     *
     */
    completedAt: string | null;
    /**
     *
     */
    completionScope: DocumentAdmissionCompletionScopeEnum;
    /**
     *
     */
    kind: DocumentAdmissionKindEnum;
    /**
     *
     */
    details: DocumentAdmissionDetails | null;
}


/**
 * @export
 */
export const DocumentAdmissionStatusEnum = {
    Pending: 'PENDING',
    Succeeded: 'SUCCEEDED',
    Failed: 'FAILED',
} as const;
export type DocumentAdmissionStatusEnum = typeof DocumentAdmissionStatusEnum[keyof typeof DocumentAdmissionStatusEnum];

/**
 * @export
 */
export const DocumentAdmissionCompletionScopeEnum = {
    RegistrationStored: 'REGISTRATION_STORED',
} as const;
export type DocumentAdmissionCompletionScopeEnum = typeof DocumentAdmissionCompletionScopeEnum[keyof typeof DocumentAdmissionCompletionScopeEnum];

/**
 * @export
 */
export const DocumentAdmissionKindEnum = {
    Document: 'DOCUMENT',
} as const;
export type DocumentAdmissionKindEnum = typeof DocumentAdmissionKindEnum[keyof typeof DocumentAdmissionKindEnum];

/**
 * Current registration returns one sessionId. Previously retained multi-document receipts may include sessions and embeddedPublicRoots; new bulk submissions are unsupported.
 * @export
 * @interface DocumentAdmissionDetails
 */
export interface DocumentAdmissionDetails {
    /**
     *
     */
    sessionId: string;
    /**
     *
     */
    sessions?: Array<DocumentAdmissionDetailsSessionsInner>;
    /**
     *
     */
    embeddedPublicRoots?: Array<string>;
}
/**
 *
 * @export
 * @interface DocumentAdmissionDetailsSessionsInner
 */
export interface DocumentAdmissionDetailsSessionsInner {
    /**
     *
     */
    sessionId: string;
    /**
     *
     */
    name: string | null;
}
/**
 *
 * @export
 * @interface DocumentAgentRun
 */
export interface DocumentAgentRun {
    /**
     *
     */
    requestId: string;
    /**
     *
     */
    profile: string;
    /**
     *
     */
    nativeDisposition: string;
    /**
     * Worker observation only; provider completion never determines the native Task result.
     */
    executionStatus: DocumentAgentRunExecutionStatusEnum;
    /**
     * Output custody status, separate from the accepted native report.
     */
    artifactStatus: DocumentAgentRunArtifactStatusEnum;
    /**
     *
     */
    artifactsReady: boolean;
    /**
     *
     */
    files: Array<AgentRunArtifactFile>;
}


/**
 * @export
 */
export const DocumentAgentRunExecutionStatusEnum = {
    Running: 'RUNNING',
    AwaitingNativeReport: 'AWAITING_NATIVE_REPORT',
    Closed: 'CLOSED',
} as const;
export type DocumentAgentRunExecutionStatusEnum = typeof DocumentAgentRunExecutionStatusEnum[keyof typeof DocumentAgentRunExecutionStatusEnum];

/**
 * @export
 */
export const DocumentAgentRunArtifactStatusEnum = {
    NotApplicable: 'NOT_APPLICABLE',
    Pending: 'PENDING',
    Ready: 'READY',
    Invalid: 'INVALID',
    Unavailable: 'UNAVAILABLE',
} as const;
export type DocumentAgentRunArtifactStatusEnum = typeof DocumentAgentRunArtifactStatusEnum[keyof typeof DocumentAgentRunArtifactStatusEnum];

/**
 *
 * @export
 * @interface DocumentAgentRunPage
 */
export interface DocumentAgentRunPage {
    /**
     *
     */
    items: Array<DocumentAgentRun>;
}
/**
 * Two native variants: host registration publishes {name} with registration sessionId; first stable managed document publishes {blueId} with native instance identity in ref.sessionId.
 * @export
 * @interface DocumentCreatedEvent
 */
export interface DocumentCreatedEvent {
    /**
     *
     */
    id: string;
    /**
     *
     */
    sequence: number;
    /**
     *
     */
    created: string;
    /**
     *
     */
    accountId: string;
    /**
     *
     */
    type: DocumentCreatedEventTypeEnum;
    /**
     *
     */
    ref: DocumentCreatedEventAllOfRef;
    /**
     *
     */
    object: DocumentCreatedEventAllOfObject;
}


/**
 * @export
 */
export const DocumentCreatedEventTypeEnum = {
    DocumentCreated: 'DOCUMENT_CREATED',
} as const;
export type DocumentCreatedEventTypeEnum = typeof DocumentCreatedEventTypeEnum[keyof typeof DocumentCreatedEventTypeEnum];

/**
 * @type DocumentCreatedEventAllOfObject
 *
 * @export
 */
export type DocumentCreatedEventAllOfObject = BlueIdentityEventPayload | NamedResourceEventPayload;
/**
 *
 * @export
 * @interface DocumentCreatedEventAllOfRef
 */
export interface DocumentCreatedEventAllOfRef {
    /**
     *
     */
    sessionId: string;
}
/**
 * New stable native epoch with emitted references and native gas; ref.sessionId is native instance identity.
 * @export
 * @interface DocumentEpochEvent
 */
export interface DocumentEpochEvent {
    /**
     *
     */
    id: string;
    /**
     *
     */
    sequence: number;
    /**
     *
     */
    created: string;
    /**
     *
     */
    accountId: string;
    /**
     *
     */
    type: DocumentEpochEventTypeEnum;
    /**
     *
     */
    ref: DocumentCreatedEventAllOfRef;
    /**
     *
     */
    object: EpochAdvancedEventPayload;
}


/**
 * @export
 */
export const DocumentEpochEventTypeEnum = {
    DocumentEpochAdvanced: 'DOCUMENT_EPOCH_ADVANCED',
} as const;
export type DocumentEpochEventTypeEnum = typeof DocumentEpochEventTypeEnum[keyof typeof DocumentEpochEventTypeEnum];

/**
 * Entering ERROR/HOST_INCOMPLETE or changed issue in that state; ref.sessionId is native instance identity.
 * @export
 * @interface DocumentErrorEvent
 */
export interface DocumentErrorEvent {
    /**
     *
     */
    id: string;
    /**
     *
     */
    sequence: number;
    /**
     *
     */
    created: string;
    /**
     *
     */
    accountId: string;
    /**
     *
     */
    type: DocumentErrorEventTypeEnum;
    /**
     *
     */
    ref: DocumentCreatedEventAllOfRef;
    /**
     *
     */
    object: ProcessingErrorEventPayload;
}


/**
 * @export
 */
export const DocumentErrorEventTypeEnum = {
    DocumentProcessingError: 'DOCUMENT_PROCESSING_ERROR',
} as const;
export type DocumentErrorEventTypeEnum = typeof DocumentErrorEventTypeEnum[keyof typeof DocumentErrorEventTypeEnum];

/**
 * Selected retained document, history-position, processing-receipt and immediate receiving material. The target and retained history boundary are fixed for this traversal. This subset does not advertise evidenceSetId, completeness, portability, assurance or host verification. Raw host diagnostics remain available from their existing endpoints.
 * @export
 * @interface DocumentEvidence
 */
export interface DocumentEvidence {
    /**
     *
     */
    target: EvidenceTarget;
    /**
     *
     */
    items: Array<ExactEvidenceRecord>;
    /**
     *
     */
    nextPageToken: string | null;
}
/**
 * Native history publication; ref.sessionId is native instance identity, including unstable APPLY and COMPLETE.
 * @export
 * @interface DocumentHistoryEvent
 */
export interface DocumentHistoryEvent {
    /**
     *
     */
    id: string;
    /**
     *
     */
    sequence: number;
    /**
     *
     */
    created: string;
    /**
     *
     */
    accountId: string;
    /**
     *
     */
    type: DocumentHistoryEventTypeEnum;
    /**
     *
     */
    ref: DocumentCreatedEventAllOfRef;
    /**
     *
     */
    object: NativeHistoryEventPayload;
}


/**
 * @export
 */
export const DocumentHistoryEventTypeEnum = {
    DocumentProcessingHistoryAppended: 'DOCUMENT_PROCESSING_HISTORY_APPENDED',
} as const;
export type DocumentHistoryEventTypeEnum = typeof DocumentHistoryEventTypeEnum[keyof typeof DocumentHistoryEventTypeEnum];

/**
 * @type DocumentInput
 * One Blue document object or serialized JSON/YAML document text. A singleton blueId object resolves an existing exact document. A serialized document must parse to an object. One document per request; bulk submissions are unsupported.
 * @export
 */
export type DocumentInput = object | string;
/**
 *
 * @export
 * @interface DocumentInputNot
 */
export interface DocumentInputNot {
    /**
     *
     */
    documents: object;
    /**
     *
     */
    edges?: any | null;
    /**
     *
     */
    publicRoots?: any | null;
    /**
     *
     */
    definitions?: any | null;
}
/**
 * Tokens are bound to account, resource, filters and limit. Changes to the accessible selection return page_token_expired; restart from the first page. Typed queries also bind observed membership and stable type metadata. No historical snapshot is retained, and processing-only changes can still be observed between pages when not filtering by status.
 * @export
 * @interface DocumentPage
 */
export interface DocumentPage {
    /**
     *
     */
    items: Array<Document>;
    /**
     *
     */
    nextPageToken: string | null;
    /**
     *
     */
    paginationMode?: DocumentPagePaginationModeEnum;
    /**
     * Present with either type selector. Visible distinct instances with pending/unavailable type metadata, before other filters.
     */
    incompleteCount?: number;
    /**
     * Opaque visible selection fingerprint; typed queries also bind membership and type metadata. This is not a retained historical snapshot. Omitted in INITIAL.
     */
    observationVersion?: string;
    /**
     *
     */
    total?: number;
    /**
     *
     */
    hasMore?: boolean;
}


/**
 * @export
 */
export const DocumentPagePaginationModeEnum = {
    ObservationBound: 'OBSERVATION_BOUND',
} as const;
export type DocumentPagePaginationModeEnum = typeof DocumentPagePaginationModeEnum[keyof typeof DocumentPagePaginationModeEnum];

/**
 * Successful native pause command; ref.sessionId is its requested handle.
 * @export
 * @interface DocumentPausedEvent
 */
export interface DocumentPausedEvent {
    /**
     *
     */
    id: string;
    /**
     *
     */
    sequence: number;
    /**
     *
     */
    created: string;
    /**
     *
     */
    accountId: string;
    /**
     *
     */
    type: DocumentPausedEventTypeEnum;
    /**
     *
     */
    ref: DocumentCreatedEventAllOfRef;
    /**
     *
     */
    object: ControlEventPayload;
}


/**
 * @export
 */
export const DocumentPausedEventTypeEnum = {
    DocumentProcessingPaused: 'DOCUMENT_PROCESSING_PAUSED',
} as const;
export type DocumentPausedEventTypeEnum = typeof DocumentPausedEventTypeEnum[keyof typeof DocumentPausedEventTypeEnum];

/**
 * Successful document delete/unlink. ref.sessionId is the requested registration/control handle, not proof that shared execution terminated.
 * @export
 * @interface DocumentRemovedEvent
 */
export interface DocumentRemovedEvent {
    /**
     *
     */
    id: string;
    /**
     *
     */
    sequence: number;
    /**
     *
     */
    created: string;
    /**
     *
     */
    accountId: string;
    /**
     *
     */
    type: DocumentRemovedEventTypeEnum;
    /**
     *
     */
    ref: DocumentCreatedEventAllOfRef;
    /**
     *
     */
    object: ControlEventPayload;
}


/**
 * @export
 */
export const DocumentRemovedEventTypeEnum = {
    DocumentRemoved: 'DOCUMENT_REMOVED',
} as const;
export type DocumentRemovedEventTypeEnum = typeof DocumentRemovedEventTypeEnum[keyof typeof DocumentRemovedEventTypeEnum];

/**
 * Successful native resume command; ref.sessionId is its requested handle.
 * @export
 * @interface DocumentResumedEvent
 */
export interface DocumentResumedEvent {
    /**
     *
     */
    id: string;
    /**
     *
     */
    sequence: number;
    /**
     *
     */
    created: string;
    /**
     *
     */
    accountId: string;
    /**
     *
     */
    type: DocumentResumedEventTypeEnum;
    /**
     *
     */
    ref: DocumentCreatedEventAllOfRef;
    /**
     *
     */
    object: ControlEventPayload;
}


/**
 * @export
 */
export const DocumentResumedEventTypeEnum = {
    DocumentProcessingResumed: 'DOCUMENT_PROCESSING_RESUMED',
} as const;
export type DocumentResumedEventTypeEnum = typeof DocumentResumedEventTypeEnum[keyof typeof DocumentResumedEventTypeEnum];

/**
 *
 * @export
 * @interface DocumentRetry
 */
export interface DocumentRetry {
    /**
     * Exact failed evaluation from processing.reason.details. An explicit retry may spend additional gas even if it fails again.
     */
    gasEvaluation?: string;
}
/**
 *
 * @export
 * @interface EncodingCacheStatistics
 */
export interface EncodingCacheStatistics {
    /**
     *
     */
    entries: number;
    /**
     *
     */
    estimatedBytes: number;
    /**
     *
     */
    budgetBytes: number;
    /**
     *
     */
    hits: number;
    /**
     *
     */
    misses: number;
    /**
     *
     */
    evictions: number;
    /**
     *
     */
    chunkEntries: number;
    /**
     *
     */
    chunkBytes: number;
    /**
     *
     */
    chunkHits: number;
    /**
     *
     */
    chunkMisses: number;
}
/**
 *
 * @export
 * @interface EndImpersonationResult
 */
export interface EndImpersonationResult {
    /**
     *
     */
    ended: EndImpersonationResultEndedEnum;
}


/**
 * @export
 */
export const EndImpersonationResultEndedEnum = {
    True: true,
} as const;
export type EndImpersonationResultEndedEnum = typeof EndImpersonationResultEndedEnum[keyof typeof EndImpersonationResultEndedEnum];

/**
 *
 * @export
 * @interface EntryAdmissionDetails
 */
export interface EntryAdmissionDetails {
    /**
     *
     */
    timelineId: string;
    /**
     *
     */
    entryRef: BlueReference;
    /**
     *
     */
    messageRef: BlueReference;
    /**
     *
     */
    timestamp: number;
    /**
     *
     */
    documentId: string | null;
}
/**
 *
 * @export
 * @interface EpochAdvancedEventPayload
 */
export interface EpochAdvancedEventPayload {
    /**
     *
     */
    blueId: string;
    /**
     *
     */
    epoch: number;
    /**
     *
     */
    historyNumber: number;
    /**
     *
     */
    emitted: Array<EpochAdvancedEventPayloadEmittedInner>;
    /**
     *
     */
    gas: number | null;
}
/**
 *
 * @export
 * @interface EpochAdvancedEventPayloadEmittedInner
 */
export interface EpochAdvancedEventPayloadEmittedInner {
    /**
     *
     */
    id: string;
    /**
     *
     */
    event: BlueReference;
}
/**
 * Cause of a stable publication. A cause identifies why this result exists; it is not a platform-notification type.
 *
 * | kind | Meaning |
 * | --- | --- |
 * | `INITIALIZATION` | The first stable state was established; entryRef is null. |
 * | `TIMELINE_ENTRY` | Processing an original Timeline Entry produced this publication. |
 * | `EMBEDDED_REVISION_APPLICATION` | A managed source revision was applied to this document. |
 * | `CATCH_UP_COMPLETED` | Required history application reached its fixed boundary and a stable result was published. |
 * | `EVENT_ONLY` | An execution emitted events without changing the exact document state. |
 * @export
 * @interface EpochCause
 */
export interface EpochCause {
    /**
     * Reason this stable revision was published.
     */
    kind: EpochCauseKindEnum;
    /**
     * Actual original input when retained for this publication; null for initialization or when no input correlation exists.
     */
    entryRef: BlueReference | null;
}


/**
 * @export
 */
export const EpochCauseKindEnum = {
    Initialization: 'INITIALIZATION',
    TimelineEntry: 'TIMELINE_ENTRY',
    EmbeddedRevisionApplication: 'EMBEDDED_REVISION_APPLICATION',
    CatchUpCompleted: 'CATCH_UP_COMPLETED',
    EventOnly: 'EVENT_ONLY',
} as const;
export type EpochCauseKindEnum = typeof EpochCauseKindEnum[keyof typeof EpochCauseKindEnum];

/**
 * Events emitted by this document member; shared receipt emissions from other members are excluded.
 * @export
 * @interface EpochEvents
 */
export interface EpochEvents {
    /**
     *
     */
    sessionId: string;
    /**
     *
     */
    epoch: number;
    /**
     *
     */
    kind: string;
    /**
     *
     */
    triggeredBy: Array<BlueReference>;
    /**
     *
     */
    emitted: Array<EpochEventsEmittedInner>;
    /**
     *
     */
    receipts: Array<BlueReference>;
}
/**
 *
 * @export
 * @interface EpochEventsEmittedInner
 */
export interface EpochEventsEmittedInner {
    /**
     *
     */
    eventRef: BlueReference;
    /**
     *
     */
    receiptRef: BlueReference;
}
/**
 * @type Event
 * The 12 event categories used by notifications and webhook callbacks. Event payloads preserve the values recorded at publication time and may differ from current resource representations. Session filters match ref.sessionId directly. Historical result fields, including id and location, remain available.
 * @export
 */
export type Event = { type: 'ACCOUNT_GAS_CHANGED' } & GasChangedEvent | { type: 'ADMISSION_STATUS_CHANGED' } & AdmissionStatusEvent | { type: 'CONTENT_CREATED' } & ContentCreatedEvent | { type: 'DOCUMENT_CREATED' } & DocumentCreatedEvent | { type: 'DOCUMENT_EPOCH_ADVANCED' } & DocumentEpochEvent | { type: 'DOCUMENT_PROCESSING_ERROR' } & DocumentErrorEvent | { type: 'DOCUMENT_PROCESSING_HISTORY_APPENDED' } & DocumentHistoryEvent | { type: 'DOCUMENT_PROCESSING_PAUSED' } & DocumentPausedEvent | { type: 'DOCUMENT_PROCESSING_RESUMED' } & DocumentResumedEvent | { type: 'DOCUMENT_REMOVED' } & DocumentRemovedEvent | { type: 'TIMELINE_CREATED' } & TimelineCreatedEvent | { type: 'TIMELINE_ENTRY_CREATED' } & TimelineEntryCreatedEvent;

/**
 *
 * @export
 */
export const EventCategory = {
    DocumentCreated: 'DOCUMENT_CREATED',
    DocumentRemoved: 'DOCUMENT_REMOVED',
    DocumentEpochAdvanced: 'DOCUMENT_EPOCH_ADVANCED',
    DocumentProcessingHistoryAppended: 'DOCUMENT_PROCESSING_HISTORY_APPENDED',
    DocumentProcessingError: 'DOCUMENT_PROCESSING_ERROR',
    DocumentProcessingPaused: 'DOCUMENT_PROCESSING_PAUSED',
    DocumentProcessingResumed: 'DOCUMENT_PROCESSING_RESUMED',
    TimelineCreated: 'TIMELINE_CREATED',
    TimelineEntryCreated: 'TIMELINE_ENTRY_CREATED',
    AdmissionStatusChanged: 'ADMISSION_STATUS_CHANGED',
    ContentCreated: 'CONTENT_CREATED',
    AccountGasChanged: 'ACCOUNT_GAS_CHANGED',
} as const;
export type EventCategory = typeof EventCategory[keyof typeof EventCategory];

/**
 * Immutable retained native-v1 event envelope; sequence is account-local.
 * @export
 * @interface EventCommon
 */
export interface EventCommon {
    /**
     *
     */
    id: string;
    /**
     *
     */
    sequence: number;
    /**
     *
     */
    created: string;
    /**
     *
     */
    accountId: string;
}
/**
 * Ascending caught-up pages retain a continuation token for future events. Descending history is finite. Tokens are bound to filters and sort direction.
 * @export
 * @interface EventPage
 */
export interface EventPage {
    /**
     *
     */
    items: Array<Event>;
    /**
     *
     */
    nextPageToken: string | null;
    /**
     *
     */
    paginationMode?: EventPagePaginationModeEnum;
    /**
     *
     */
    total?: number;
    /**
     *
     */
    hasMore?: boolean;
    /**
     *
     */
    historyScope?: EventPageHistoryScopeEnum;
}


/**
 * @export
 */
export const EventPagePaginationModeEnum = {
    Follow: 'FOLLOW',
    Pinned: 'PINNED',
} as const;
export type EventPagePaginationModeEnum = typeof EventPagePaginationModeEnum[keyof typeof EventPagePaginationModeEnum];

/**
 * @export
 */
export const EventPageHistoryScopeEnum = {
    RetainedEvents: 'RETAINED_EVENTS',
} as const;
export type EventPageHistoryScopeEnum = typeof EventPageHistoryScopeEnum[keyof typeof EventPageHistoryScopeEnum];

/**
 *
 * @export
 * @interface EvidenceTarget
 */
export interface EvidenceTarget {
    /**
     * Opaque handle of this document registration in the caller’s scope. It is not a document lineage or an exact version BlueId.
     */
    sessionId: string;
    /**
     * Identity of the document lineage, shared by its versions; distinct from the registration handle and exact content BlueId.
     */
    documentId: string;
    /**
     * Stable epoch selected by the target, or null when the target is not a stable publication.
     */
    epoch: number | null;
    /**
     * Actual retained History Position selected by this target, never a fabricated latest-state reference.
     */
    positionRef: BlueReference | null;
    /**
     * Original input entry selected by this target, or null when the target is not entry-specific.
     */
    entryRef: BlueReference | null;
}
/**
 * Exact Blue content to retain under the BlueId in the path. The content is not source-preprocessed and a singleton blueId object is not a lookup shorthand.
 * @export
 * @interface ExactContentSubmission
 */
export interface ExactContentSubmission {
    /**
     * A complete Blue value, without a transport wrapper. Objects, arrays and scalar values retain their data and numeric precision. Cyclic members contain references rather than an expanded graph. Fields named mode, text, proof and body are ordinary data.
     */
    content: any | null;
}
/**
 * Retained exact material selected for this target. References identify original content; this page does not claim a closed or independently verified proof set.
 * @export
 * @interface ExactEvidenceRecord
 */
export interface ExactEvidenceRecord {
    /**
     * Discriminator selecting the meaning and schema of this record; distinct from an event notification category.
     */
    kind: ExactEvidenceRecordKindEnum;
    /**
     * Exact Blue reference identifying this retained value.
     */
    ref: BlueReference;
    /**
     * Exact retained Blue value, decoded without a native transport or cyclic owning-set proof.
     */
    value?: any | null;
}


/**
 * @export
 */
export const ExactEvidenceRecordKindEnum = {
    Content: 'CONTENT',
    HistoryPosition: 'HISTORY_POSITION',
    ProcessingReceipt: 'PROCESSING_RECEIPT',
    Receiving: 'RECEIVING',
} as const;
export type ExactEvidenceRecordKindEnum = typeof ExactEvidenceRecordKindEnum[keyof typeof ExactEvidenceRecordKindEnum];

/**
 *
 * @export
 * @interface ExactText
 */
export interface ExactText {
    /**
     *
     */
    mode: ExactTextModeEnum;
    /**
     *
     */
    format?: ExactTextFormatEnum;
    /**
     *
     */
    text: string;
}


/**
 * @export
 */
export const ExactTextModeEnum = {
    Exact: 'exact',
} as const;
export type ExactTextModeEnum = typeof ExactTextModeEnum[keyof typeof ExactTextModeEnum];

/**
 * @export
 */
export const ExactTextFormatEnum = {
    Json: 'json',
    Yaml: 'yaml',
} as const;
export type ExactTextFormatEnum = typeof ExactTextFormatEnum[keyof typeof ExactTextFormatEnum];

/**
 * Present only for a current OAuth Agent credential. No human identity or credential secret is returned.
 * @export
 * @interface ExternalConnectionIdentity
 */
export interface ExternalConnectionIdentity {
    /**
     *
     */
    connectionId: string;
    /**
     *
     */
    clientId: string;
    /**
     *
     */
    resource: string;
    /**
     * Access token expiry as Unix milliseconds.
     */
    expiresAt: number;
    /**
     *
     */
    name: string;
    /**
     *
     */
    scopes: Array<string>;
}
/**
 *
 * @export
 * @interface GasChangedEvent
 */
export interface GasChangedEvent {
    /**
     *
     */
    id: string;
    /**
     *
     */
    sequence: number;
    /**
     *
     */
    created: string;
    /**
     *
     */
    accountId: string;
    /**
     *
     */
    type: GasChangedEventTypeEnum;
    /**
     *
     */
    ref: GasChangedEventAllOfRef;
    /**
     *
     */
    object: GasChangedEventAllOfObject;
}


/**
 * @export
 */
export const GasChangedEventTypeEnum = {
    AccountGasChanged: 'ACCOUNT_GAS_CHANGED',
} as const;
export type GasChangedEventTypeEnum = typeof GasChangedEventTypeEnum[keyof typeof GasChangedEventTypeEnum];

/**
 * Committed after-state, before-state and causes from the same publication. Balance includes reserved gas; available excludes it.
 * @export
 * @interface GasChangedEventAllOfObject
 */
export interface GasChangedEventAllOfObject {
    /**
     *
     */
    credited: string;
    /**
     *
     */
    spent: string;
    /**
     *
     */
    reserved: string;
    /**
     *
     */
    available: string;
    /**
     *
     */
    generation: string;
    /**
     *
     */
    balance: string;
    /**
     *
     */
    before: GasEventBalance;
    /**
     *
     */
    causes: Array<GasEventCause>;
}
/**
 *
 * @export
 * @interface GasChangedEventAllOfRef
 */
export interface GasChangedEventAllOfRef {
    /**
     *
     */
    accountId: string;
}
/**
 *
 * @export
 * @interface GasEventBalance
 */
export interface GasEventBalance {
    /**
     *
     */
    credited: string;
    /**
     *
     */
    spent: string;
    /**
     *
     */
    reserved: string;
    /**
     *
     */
    available: string;
    /**
     *
     */
    balance: string;
    /**
     *
     */
    generation: string;
}
/**
 *
 * @export
 * @interface GasEventCause
 */
export interface GasEventCause {
    /**
     *
     */
    kind: GasEventCauseKindEnum;
    /**
     *
     */
    id: string;
    /**
     *
     */
    units: string;
    /**
     *
     */
    instance?: string;
    /**
     *
     */
    evaluation?: string;
    /**
     *
     */
    phase?: string;
    /**
     *
     */
    debit?: string;
    /**
     *
     */
    usage?: string;
    /**
     *
     */
    returned?: string;
    /**
     *
     */
    disposition?: string;
    /**
     *
     */
    issuer?: string;
    /**
     *
     */
    evidence?: string;
}


/**
 * @export
 */
export const GasEventCauseKindEnum = {
    Credit: 'CREDIT',
    Reserved: 'RESERVED',
    Settled: 'SETTLED',
    Released: 'RELEASED',
} as const;
export type GasEventCauseKindEnum = typeof GasEventCauseKindEnum[keyof typeof GasEventCauseKindEnum];

/**
 *
 * @export
 * @interface GasSummary
 */
export interface GasSummary {
    /**
     *
     */
    credited: string;
    /**
     *
     */
    spent: string;
    /**
     *
     */
    balance: string;
    /**
     *
     */
    reserved: string;
    /**
     *
     */
    available: string;
    /**
     *
     */
    generation: string;
    /**
     *
     */
    publicationSequence: string;
    /**
     *
     */
    accountId: string;
    /**
     *
     */
    enforcement: string;
    /**
     *
     */
    tariff: string;
}
/**
 * Bounded retained history pinned on first page.
 * @export
 * @interface HistoryPage
 */
export interface HistoryPage {
    /**
     *
     */
    items: Array<HistoryPosition>;
    /**
     *
     */
    nextPageToken: string | null;
    /**
     *
     */
    paginationMode?: string;
    /**
     *
     */
    total?: number;
    /**
     *
     */
    hasMore?: boolean;
}
/**
 * One retained native transition, including unstable transitions. COMPLETE remains a native kind and can reuse a receipt without additional gas; it is not renamed PROCESS.
 * @export
 * @interface HistoryPosition
 */
export interface HistoryPosition {
    /**
     * Exact reference to the retained History Position; not an epoch number or a reference to the latest document.
     */
    positionRef: BlueReference;
    /**
     * Exact predecessor History Position; null only when the position has no predecessor.
     */
    predecessorRef: BlueReference | null;
    /**
     * Discriminator selecting the meaning and schema of this record; distinct from an event notification category.
     */
    kind: HistoryPositionKindEnum;
    /**
     * Stable epoch retained at this History Position. Several completed non-stable transitions may retain the same epoch; only stable=true denotes a stable publication.
     */
    epoch: number;
    /**
     * Whether this retained transition is a stable publication. Completed non-stable transitions remain in history.
     */
    stable: boolean;
    /**
     * Exact document state recorded at this history position. stable indicates whether it is an externally published stable snapshot.
     */
    documentRef: BlueReference;
    /**
     * Exact reference to the receipt retained for this transition.
     */
    receiptRef: BlueReference;
}


/**
 * @export
 */
export const HistoryPositionKindEnum = {
    Initialize: 'INITIALIZE',
    Process: 'PROCESS',
    Apply: 'APPLY',
    Complete: 'COMPLETE',
} as const;
export type HistoryPositionKindEnum = typeof HistoryPositionKindEnum[keyof typeof HistoryPositionKindEnum];

/**
 *
 * @export
 * @interface ImpersonationMetadata
 */
export interface ImpersonationMetadata {
    /**
     *
     */
    actorUserId: string;
    /**
     *
     */
    actorEmail: string;
    /**
     *
     */
    userId: string;
    /**
     *
     */
    email: string;
    /**
     *
     */
    createdAt: number;
    /**
     *
     */
    expiresAt: number;
    /**
     *
     */
    sessionContext: string;
}
/**
 *
 * @export
 * @interface ImpersonationSession
 */
export interface ImpersonationSession {
    /**
     *
     */
    token: string;
    /**
     *
     */
    userId: string;
    /**
     *
     */
    email: string;
    /**
     *
     */
    expiresAt: number;
    /**
     *
     */
    impersonation: ImpersonationMetadata;
}
/**
 * @type ImportTimeline
 *
 * @export
 */
export type ImportTimeline = ImportTimelineOneOf | ImportTimelineOneOf1;
/**
 *
 * @export
 * @interface ImportTimelineAccess
 */
export interface ImportTimelineAccess {
    /**
     * Optional complete advertised execution selection for this access follower. Omission preserves the account default. Repeating the same import recovers the same registration; an existing follower bound to another selection is rejected and never replaced. Importing public access evidence does not change the provider grant.
     */
    executionSelection?: RuntimeIdentities;
    /**
     *
     */
    endpoint: string;
    /**
     *
     */
    accessId: string;
}
/**
 *
 * @export
 * @interface ImportTimelineOneOf
 */
export interface ImportTimelineOneOf {
    /**
     *
     */
    endpoint: string;
    /**
     *
     */
    timelineId: string;
    /**
     * An existing reader-key identifier owned by this account. Mutually exclusive with readerPublicKey.
     */
    readerKeyId: string;
    /**
     * The exact public reader key declared by a party. Must resolve to a live private credential already owned by this account; this does not create a key or grant access.
     */
    readerPublicKey?: string;
    /**
     *
     */
    name?: string;
}
/**
 *
 * @export
 * @interface ImportTimelineOneOf1
 */
export interface ImportTimelineOneOf1 {
    /**
     *
     */
    endpoint: string;
    /**
     *
     */
    timelineId: string;
    /**
     * An existing reader-key identifier owned by this account. Mutually exclusive with readerPublicKey.
     */
    readerKeyId?: string;
    /**
     * The exact public reader key declared by a party. Must resolve to a live private credential already owned by this account; this does not create a key or grant access.
     */
    readerPublicKey: string;
    /**
     *
     */
    name?: string;
}
/**
 *
 * @export
 * @interface ImportedAccessDocument
 */
export interface ImportedAccessDocument {
    /**
     *
     */
    sessionId: string;
    /**
     *
     */
    id: string;
    /**
     *
     */
    name: string;
    /**
     *
     */
    documentId: string;
}
/**
 * Provider-backed timeline metadata and current import availability, including the account's own timelines (owned=true) and read imports (owned=false). timelineId is the canonical native timeline identity and matches operations[].routes[].timeline. This shape has no blueId field; the presence of endpoint does not imply read-only access. Fetch exact timeline content through its timelineId reference when needed.
 * @export
 * @interface ImportedTimeline
 */
export interface ImportedTimeline {
    /**
     *
     */
    timelineId: string;
    /**
     *
     */
    name: string;
    /**
     *
     */
    endpoint: string;
    /**
     *
     */
    readerKeyId?: string;
    /**
     *
     */
    owned: boolean;
    /**
     *
     */
    status: ImportedTimelineStatusEnum;
    /**
     *
     */
    createdAt?: string;
    /**
     *
     */
    lastChangedAt?: string;
    /**
     *
     */
    lastFetchedAt?: string;
    /**
     *
     */
    completeBefore?: number;
    /**
     *
     */
    head?: string | null;
    /**
     *
     */
    issue?: ImportedTimelineIssue;
}


/**
 * @export
 */
export const ImportedTimelineStatusEnum = {
    Pending: 'PENDING',
    Available: 'AVAILABLE',
    Unavailable: 'UNAVAILABLE',
    RetryRequired: 'RETRY_REQUIRED',
} as const;
export type ImportedTimelineStatusEnum = typeof ImportedTimelineStatusEnum[keyof typeof ImportedTimelineStatusEnum];

/**
 * Retained imported entries and their original values in native order. Import polling may add entries between pages. This page is not a signed completeness proof.
 * @export
 * @interface ImportedTimelineEntriesPage
 */
export interface ImportedTimelineEntriesPage {
    /**
     *
     */
    timelineRef: BlueReference;
    /**
     *
     */
    items: Array<TimelineEntry>;
    /**
     *
     */
    nextPageToken: string | null;
    /**
     *
     */
    hasMore: boolean;
    /**
     *
     */
    paginationMode: ImportedTimelineEntriesPagePaginationModeEnum;
}


/**
 * @export
 */
export const ImportedTimelineEntriesPagePaginationModeEnum = {
    RetainedImportedEntries: 'RETAINED_IMPORTED_ENTRIES',
} as const;
export type ImportedTimelineEntriesPagePaginationModeEnum = typeof ImportedTimelineEntriesPagePaginationModeEnum[keyof typeof ImportedTimelineEntriesPagePaginationModeEnum];

/**
 *
 * @export
 * @interface ImportedTimelineIssue
 */
export interface ImportedTimelineIssue {
    /**
     *
     */
    code: string;
    /**
     *
     */
    availability: boolean;
    /**
     *
     */
    category: ImportedTimelineIssueCategoryEnum;
    /**
     *
     */
    timeline: string;
    /**
     *
     */
    endpoint: string;
    /**
     *
     */
    message: string;
    /**
     *
     */
    attempts: number;
    /**
     * Additional native validation diagnostics, when available.
     */
    diagnostic?: any | null;
}


/**
 * @export
 */
export const ImportedTimelineIssueCategoryEnum = {
    Availability: 'AVAILABILITY',
    InvalidEvidence: 'INVALID_EVIDENCE',
} as const;
export type ImportedTimelineIssueCategoryEnum = typeof ImportedTimelineIssueCategoryEnum[keyof typeof ImportedTimelineIssueCategoryEnum];

/**
 *
 * @export
 * @interface Info
 */
export interface Info {
    /**
     *
     */
    name: string;
    /**
     *
     */
    accountId: string;
    /**
     *
     */
    email: string | null;
    /**
     *
     */
    principalType: InfoPrincipalTypeEnum;
    /**
     *
     */
    capabilities: Array<string>;
    /**
     *
     */
    limits: InfoLimits;
    /**
     *
     */
    runtime: RuntimeIdentities;
    /**
     * Exact selections supported for new registrations by this account owner. Empty means explicit selection is unavailable. This does not replace the account runtime or change existing documents. Older servers may omit the field.
     */
    registrationExecutionSelections?: Array<RuntimeIdentities>;
    /**
     *
     */
    executionPolicy: InfoExecutionPolicy;
}


/**
 * @export
 */
export const InfoPrincipalTypeEnum = {
    User: 'user',
    ApiKey: 'api-key',
} as const;
export type InfoPrincipalTypeEnum = typeof InfoPrincipalTypeEnum[keyof typeof InfoPrincipalTypeEnum];

/**
 * The selected edition's policy. The current edition defaults to and permits 10000000 gas units. Historical captures retain their original policies.
 * @export
 * @interface InfoExecutionPolicy
 */
export interface InfoExecutionPolicy {
    /**
     *
     */
    gasLimit: number;
    /**
     *
     */
    standardGasLimit: number;
    /**
     *
     */
    temporaryHostOverride: boolean;
}
/**
 *
 * @export
 * @interface InfoLimits
 */
export interface InfoLimits {
    /**
     *
     */
    requestBytes: number;
}

/**
 * Omission equals source, including idempotency. source resolves names and prepares the supplied value; exact preserves it. Null is invalid. A complete singleton blueId reference resolves an existing exact value in either mode.
 * @export
 */
export const InputMode = {
    Source: 'source',
    Exact: 'exact',
} as const;
export type InputMode = typeof InputMode[keyof typeof InputMode];


/**
 *
 * @export
 */
export const InvitationRole = {
    Admin: 'ADMIN',
    Write: 'WRITE',
    ReadAll: 'READ_ALL',
    ReadSelected: 'READ_SELECTED',
} as const;
export type InvitationRole = typeof InvitationRole[keyof typeof InvitationRole];

/**
 * The resolved native message supplies its operation, channel, arguments and target, including exact-version conditions. The session path validates lineage and never injects a target. Native inherited, derived and nested Operation Requests remain recognized. Success means input stored, not document execution.
 * @export
 * @interface InvokeOperation
 */
export interface InvokeOperation {
    /**
     *
     */
    inputMode?: InputMode;
    /**
     *
     */
    timeline: BlueReference;
    /**
     * Complete Blue value. A singleton blueId object references an accessible existing exact value; objects with blueId and other fields follow native Blue validation without whole-value lookup. Nested references remain inside the value. Scalar strings are literal text. Fields named mode, format and text are ordinary domain fields here.
     */
    message: any | null;
    /**
     *
     */
    actor?: string;
    /**
     * Native authority BlueId only; target mandateRef objects are unsupported.
     */
    authority?: string;
    /**
     * Agent-only selection of a native mandate held by this account and granted to this Agent. Mutually exclusive with authority. Core publishes the native Mandate Authority claim; native execution still checks approval, scope, logical order and revocation.
     */
    mandateSessionId?: string;
    /**
     * Omitted means no predecessor assertion; null requires an empty timeline; string must equal its current head. Replay keeps the original committed entry.
     */
    prevEntry?: string | null;
}


/**
 *
 * @export
 * @interface LastStable
 */
export interface LastStable {
    /**
     * Stable publication number within the document lineage; zero identifies the initial stable publication.
     */
    epoch: number;
    /**
     * Exact Blue reference to the document value at the selected publication.
     */
    documentRef: BlueReference;
    /**
     * Exact History Position for this publication; distinct from the document value and epoch number.
     */
    positionRef: BlueReference;
    /**
     * Exact Processing Receipt for the completed computation represented by this publication.
     */
    receiptRef: BlueReference;
}
/**
 * @type LookupAccounts
 *
 * @export
 */
export type LookupAccounts = LookupAccountsOneOf | LookupAccountsOneOf1 | LookupAccountsOneOf2;
/**
 *
 * @export
 * @interface LookupAccountsOneOf
 */
export interface LookupAccountsOneOf {
    /**
     * Exact account IDs. Duplicates are normalized before pagination.
     */
    accountIds: Array<string>;
    /**
     * Exact verified User emails, trimmed and lowercased. One email may match several Users and accounts.
     */
    emails?: Array<string>;
    /**
     * Trimmed case-insensitive email prefix or account-name substring. SQL wildcard characters are literal.
     */
    q?: string;
    /**
     *
     */
    limit?: number;
    /**
     * Opaque nextPageToken from the previous page with the same selectors and limit.
     */
    pageToken?: string | null;
}
/**
 *
 * @export
 * @interface LookupAccountsOneOf1
 */
export interface LookupAccountsOneOf1 {
    /**
     * Exact account IDs. Duplicates are normalized before pagination.
     */
    accountIds?: Array<string>;
    /**
     * Exact verified User emails, trimmed and lowercased. One email may match several Users and accounts.
     */
    emails: Array<string>;
    /**
     * Trimmed case-insensitive email prefix or account-name substring. SQL wildcard characters are literal.
     */
    q?: string;
    /**
     *
     */
    limit?: number;
    /**
     * Opaque nextPageToken from the previous page with the same selectors and limit.
     */
    pageToken?: string | null;
}
/**
 *
 * @export
 * @interface LookupAccountsOneOf2
 */
export interface LookupAccountsOneOf2 {
    /**
     * Exact account IDs. Duplicates are normalized before pagination.
     */
    accountIds?: Array<string>;
    /**
     * Exact verified User emails, trimmed and lowercased. One email may match several Users and accounts.
     */
    emails?: Array<string>;
    /**
     * Trimmed case-insensitive email prefix or account-name substring. SQL wildcard characters are literal.
     */
    q: string;
    /**
     *
     */
    limit?: number;
    /**
     * Opaque nextPageToken from the previous page with the same selectors and limit.
     */
    pageToken?: string | null;
}
/**
 *
 * @export
 * @interface ManagedRelationship
 */
export interface ManagedRelationship {
    /**
     * Direction relative to the requested session.
     */
    direction: ManagedRelationshipDirectionEnum;
    /**
     * Stable retained identity of this managed occurrence, not a guessed channel label.
     */
    occurrenceId: string;
    /**
     * Actual occurrence generation distinguishing reattachment or replacement at the same path.
     */
    generation: number;
    /**
     * Document pointer identifying the managed occurrence in the receiving target document.
     */
    path: string;
    /**
     * HISTORY_PENDING means a retained unfinished catch-up goal identifies the required position. UNRESOLVED means no required position is established from retained goal evidence; a healthy occurrence can also be unresolved after its goal is removed. Core does not advertise LIVE without occurrence-bound applied-position proof.
     */
    state: ManagedRelationshipStateEnum;
    /**
     * Source endpoint of this retained managed dependency relationship. Its document identity does not grant access to a source registration.
     */
    source: RelationshipEndpoint;
    /**
     * Target endpoint of this retained managed dependency relationship. An unavailable endpoint reference is not synthesized from the current document.
     */
    target: RelationshipEndpoint;
    /**
     *
     */
    requiredPositionRef: ManagedRelationshipRequiredPositionRef;
}


/**
 * @export
 */
export const ManagedRelationshipDirectionEnum = {
    Incoming: 'incoming',
    Outgoing: 'outgoing',
} as const;
export type ManagedRelationshipDirectionEnum = typeof ManagedRelationshipDirectionEnum[keyof typeof ManagedRelationshipDirectionEnum];

/**
 * @export
 */
export const ManagedRelationshipStateEnum = {
    HistoryPending: 'HISTORY_PENDING',
    Unresolved: 'UNRESOLVED',
} as const;
export type ManagedRelationshipStateEnum = typeof ManagedRelationshipStateEnum[keyof typeof ManagedRelationshipStateEnum];

/**
 * Exact source History Position required by the receiving obligation when established. Null means the required position is unresolved, not unnecessary.
 * @export
 * @interface ManagedRelationshipRequiredPositionRef
 */
export interface ManagedRelationshipRequiredPositionRef {
    /**
     *
     */
    blueId: string;
}
/**
 *
 * @export
 * @interface MandateSharingSettings
 */
export interface MandateSharingSettings {
    /**
     *
     */
    enabled: boolean;
    /**
     *
     */
    revision: number;
}
/**
 *
 * @export
 * @interface MatchedUser
 */
export interface MatchedUser {
    /**
     *
     */
    userId: string;
    /**
     *
     */
    email: string;
    /**
     *
     */
    firstName: string | null;
    /**
     *
     */
    lastName: string | null;
    /**
     * Versioned avatar of this User, shared across their accounts. Requires authenticated Core image access.
     */
    imageUrl: string | null;
}
/**
 *
 * @export
 * @interface MembershipReceipt
 */
export interface MembershipReceipt {
    /**
     *
     */
    accountId: string;
    /**
     *
     */
    userId: string;
    /**
     *
     */
    generation: number;
    /**
     *
     */
    revision: number;
    /**
     *
     */
    role: MembershipRole;
    /**
     *
     */
    status: MembershipReceiptStatusEnum;
    /**
     *
     */
    receiptId: string;
}


/**
 * @export
 */
export const MembershipReceiptStatusEnum = {
    Active: 'ACTIVE',
    Removed: 'REMOVED',
} as const;
export type MembershipReceiptStatusEnum = typeof MembershipReceiptStatusEnum[keyof typeof MembershipReceiptStatusEnum];


/**
 * Owner has Admin capabilities. An account, User and normalized email each have at most one ownership. Any Owner or Admin can change the current Owner to Admin before assigning another eligible Owner.
 * @export
 */
export const MembershipRole = {
    Owner: 'OWNER',
    Admin: 'ADMIN',
    Write: 'WRITE',
    ReadAll: 'READ_ALL',
    ReadSelected: 'READ_SELECTED',
} as const;
export type MembershipRole = typeof MembershipRole[keyof typeof MembershipRole];

/**
 *
 * @export
 * @interface MembershipSummary
 */
export interface MembershipSummary {
    /**
     *
     */
    generation: number;
    /**
     *
     */
    revision: number;
    /**
     *
     */
    role: MembershipRole;
    /**
     *
     */
    status: MembershipSummaryStatusEnum;
}


/**
 * @export
 */
export const MembershipSummaryStatusEnum = {
    Active: 'ACTIVE',
    Removed: 'REMOVED',
} as const;
export type MembershipSummaryStatusEnum = typeof MembershipSummaryStatusEnum[keyof typeof MembershipSummaryStatusEnum];

/**
 *
 * @export
 * @interface NamedResourceEventPayload
 */
export interface NamedResourceEventPayload {
    /**
     *
     */
    name: string | null;
}
/**
 * @type NativeExactContent
 *
 * @export
 */
export type NativeExactContent = CyclicEvidence | ExactText;
/**
 * The history transition as recorded when the event was published. Its fields differ from the HistoryPosition resource.
 * @export
 * @interface NativeHistoryEventPayload
 */
export interface NativeHistoryEventPayload {
    /**
     *
     */
    number: number;
    /**
     *
     */
    epoch: number;
    /**
     *
     */
    kind: NativeHistoryEventPayloadKindEnum;
    /**
     *
     */
    stable: boolean;
    /**
     *
     */
    document: string | null;
    /**
     *
     */
    position: string | null;
    /**
     *
     */
    receipt: string | null;
    /**
     *
     */
    effectiveOrder?: Array<any> | null;
    /**
     *
     */
    gas: number | null;
    /**
     *
     */
    timings?: object;
}


/**
 * @export
 */
export const NativeHistoryEventPayloadKindEnum = {
    Initialize: 'INITIALIZE',
    Process: 'PROCESS',
    Apply: 'APPLY',
    Complete: 'COMPLETE',
} as const;
export type NativeHistoryEventPayloadKindEnum = typeof NativeHistoryEventPayloadKindEnum[keyof typeof NativeHistoryEventPayloadKindEnum];

/**
 * Original Core input observation. Local assessment and requested-entry disposition remain separate, with retained native history rows. A missing disposition is unknown, not NO_MATCH or COMPLETE.
 * @export
 * @interface NativeInputObservation
 */
export interface NativeInputObservation {
    /**
     *
     */
    sessionId: string;
    /**
     *
     */
    entryBlueId: string;
    /**
     *
     */
    assessment: object | null;
    /**
     *
     */
    localHistory: Array<NativeHistoryEventPayload>;
    /**
     *
     */
    disposition: object | null;
    /**
     *
     */
    processing: NativeProcessing;
}
/**
 * Original Core processing observation, with the direct pause flag, native/effective state and current reason or issue. A missing blocker does not prove completion.
 * @export
 * @interface NativeProcessing
 */
export interface NativeProcessing {
    /**
     *
     */
    state: NativeProcessingStateEnum;
    /**
     *
     */
    nativeState: NativeProcessingNativeStateEnum;
    /**
     *
     */
    checkedBoundary: number;
    /**
     *
     */
    reason: NativeProcessingReason | null;
    /**
     *
     */
    issue: NativeProcessingReason | null;
    /**
     *
     */
    retryable: boolean;
    /**
     *
     */
    paused: boolean;
}


/**
 * @export
 */
export const NativeProcessingStateEnum = {
    Ready: 'READY',
    Processing: 'PROCESSING',
    Waiting: 'WAITING',
    Paused: 'PAUSED',
    Error: 'ERROR',
    HostIncomplete: 'HOST_INCOMPLETE',
} as const;
export type NativeProcessingStateEnum = typeof NativeProcessingStateEnum[keyof typeof NativeProcessingStateEnum];

/**
 * @export
 */
export const NativeProcessingNativeStateEnum = {
    Ready: 'READY',
    Processing: 'PROCESSING',
    Waiting: 'WAITING',
    Error: 'ERROR',
    HostIncomplete: 'HOST_INCOMPLETE',
} as const;
export type NativeProcessingNativeStateEnum = typeof NativeProcessingNativeStateEnum[keyof typeof NativeProcessingNativeStateEnum];

/**
 * Native observed reason. Other native diagnostic facts may be present.
 * @export
 * @interface NativeProcessingReason
 */
export interface NativeProcessingReason {
    /**
     *
     */
    code?: string;
    /**
     *
     */
    message?: string;
    /**
     *
     */
    documents?: Array<string>;
    /**
     * Unresolved Blue IDs blocking the latest attempt when code is NEEDS_RESOURCES. Includes failed dependencies only. Resolving them may reveal further requirements.
     */
    required?: Array<string>;
}
/**
 * OpenAPI 3.1 specification using JSON Schema 2020-12.
 * @export
 * @interface OpenApiDocument
 */
export interface OpenApiDocument {
    /**
     *
     */
    openapi: OpenApiDocumentOpenapiEnum;
    /**
     *
     */
    info: object;
    /**
     *
     */
    paths: object;
    /**
     *
     */
    components: object;
    /**
     *
     */
    tags?: Array<object>;
    /**
     *
     */
    security?: Array<object>;
}


/**
 * @export
 */
export const OpenApiDocumentOpenapiEnum = {
    _310: '3.1.0',
} as const;
export type OpenApiDocumentOpenapiEnum = typeof OpenApiDocumentOpenapiEnum[keyof typeof OpenApiDocumentOpenapiEnum];

/**
 *
 * @export
 * @interface Operation
 */
export interface Operation {
    /**
     *
     */
    name: string;
    /**
     *
     */
    channel?: string;
    /**
     * A native Blue display value; domain fields remain open. Arbitrary-size integers are preserved. Unsupported native decimal precision is rejected rather than silently rounded.
     */
    request?: any | null;
    /**
     *
     */
    routes: Array<OperationRoute>;
    /**
     *
     */
    description?: string | null;
}
/**
 *
 * @export
 * @interface OperationAdmission
 */
export interface OperationAdmission {
    /**
     *
     */
    admissionId: string;
    /**
     *
     */
    status: OperationAdmissionStatusEnum;
    /**
     *
     */
    problem?: AdmissionProblem;
    /**
     *
     */
    createdAt: string | null;
    /**
     *
     */
    completedAt: string | null;
    /**
     *
     */
    completionScope: OperationAdmissionCompletionScopeEnum;
    /**
     *
     */
    kind: OperationAdmissionKindEnum;
    /**
     *
     */
    details: EntryAdmissionDetails | null;
}


/**
 * @export
 */
export const OperationAdmissionStatusEnum = {
    Pending: 'PENDING',
    Succeeded: 'SUCCEEDED',
    Failed: 'FAILED',
} as const;
export type OperationAdmissionStatusEnum = typeof OperationAdmissionStatusEnum[keyof typeof OperationAdmissionStatusEnum];

/**
 * @export
 */
export const OperationAdmissionCompletionScopeEnum = {
    TimelineEntryStored: 'TIMELINE_ENTRY_STORED',
} as const;
export type OperationAdmissionCompletionScopeEnum = typeof OperationAdmissionCompletionScopeEnum[keyof typeof OperationAdmissionCompletionScopeEnum];

/**
 * @export
 */
export const OperationAdmissionKindEnum = {
    Operation: 'OPERATION',
} as const;
export type OperationAdmissionKindEnum = typeof OperationAdmissionKindEnum[keyof typeof OperationAdmissionKindEnum];

/**
 * Publication-bound operation context. Ordinary reads return valueJson; childrenOffset reads return children and nextChildrenOffset instead, without child values or dereferencing them.
 * @export
 * @interface OperationContext
 */
export interface OperationContext {
    /**
     *
     */
    accountId: string;
    /**
     *
     */
    sessionId: string;
    /**
     *
     */
    documentId: string | null;
    /**
     *
     */
    reviewedPublication: OperationReviewedPublication;
    /**
     *
     */
    path: string;
    /**
     * Exact native literal projection. Formal contracts are references; other state and existing references are preserved. Maximum 262144 UTF-8 bytes; larger values return 413 and can be explored with childrenOffset.
     */
    valueJson?: string;
    /**
     *
     */
    children?: Array<OperationContextChildrenInner>;
    /**
     *
     */
    nextChildrenOffset?: number | null;
}
/**
 *
 * @export
 * @interface OperationContextChildrenInner
 */
export interface OperationContextChildrenInner {
    /**
     *
     */
    path: string;
}
/**
 *
 * @export
 * @interface OperationDefinitionView
 */
export interface OperationDefinitionView {
    /**
     * Request-root JSON pointers to declared fields or positional items whose children are omitted from presentation. Empty pointer selects the root. Generic collection indices and dictionary keys not declared in the matcher are rejected. No wildcards. Does not change validation, wireFormat, request bytes or definition digest. Omission or an empty list expands normally.
     */
    doNotExpandPaths: Array<string>;
}
/**
 *
 * @export
 * @interface OperationInputDefinition
 */
export interface OperationInputDefinition {
    /**
     * Canonical request declaration state. Missing matcher is distinct from an explicit empty matcher. No metadata or inferred hidden requirements are read.
     */
    state: OperationInputDefinitionStateEnum;
    /**
     * Versioned canonical declaration and runtime fingerprint. Presentation selectors, pagination, descriptions of resolved fields and presentation failures do not alter it.
     */
    digest: string;
    /**
     * Exact canonical execution selection used to resolve and validate this definition.
     */
    runtime: { [key: string]: any; };
    /**
     *
     */
    descriptor?: OperationInputDescriptor;
    /**
     *
     */
    description?: string;
    /**
     * Non-authoritative presentation diagnostics; never changes canonical input validity.
     */
    presentationIssues?: Array<OperationPreparationIssue>;
}


/**
 * @export
 */
export const OperationInputDefinitionStateEnum = {
    NoMatcher: 'no_matcher',
    Matcher: 'matcher',
    Unresolved: 'unresolved',
} as const;
export type OperationInputDefinitionStateEnum = typeof OperationInputDefinitionStateEnum[keyof typeof OperationInputDefinitionStateEnum];

/**
 *
 * @export
 * @interface OperationInputDescriptor
 */
export interface OperationInputDescriptor {
    /**
     *
     */
    kind: OperationInputDescriptorKindEnum;
    /**
     * Canonical request representation. Typed scalar values preserve their type and value fields; typed lists preserve their type and items fields. Other native metadata remains part of the original value. Never infer a wrapper solely from kind or typeBlueId.
     */
    wireFormat: OperationInputDescriptorWireFormatEnum;
    /**
     * Explicit native typed-Integer value encoding. A value inside the safe integer range may be a JSON number; a larger integer is represented as its exact decimal string. Preserve the native type envelope. Do not silently change request bytes during validation.
     */
    valueEncoding?: OperationInputDescriptorValueEncodingEnum;
    /**
     *
     */
    primitive?: OperationInputDescriptorPrimitiveEnum;
    /**
     *
     */
    typeBlueId?: string;
    /**
     *
     */
    fields?: Array<OperationInputField>;
    /**
     *
     */
    item?: OperationInputDescriptor;
    /**
     * Declared positional list element definitions, when present.
     */
    items?: Array<OperationInputDescriptor>;
    /**
     *
     */
    key?: OperationInputDescriptor;
    /**
     *
     */
    value?: OperationInputDescriptor;
    /**
     * Exact native fixed value encoded as JSON text. Presence is significant, including null.
     */
    fixedJson?: string;
    /**
     * Exact native choices encoded as JSON text.
     */
    enumJson?: Array<string>;
    /**
     * Resolved native Blue constraints encoded as lossless JSON text. This is not JSON Schema.
     */
    constraintsJson: string;
    /**
     * Absolute request-root RFC 6901 pointer for this concrete declared node. Empty selects the request root. Absent for generic item/key/value templates and their descendants. Presentation only; not a descriptorPath selector.
     */
    requestPath?: string;
    /**
     *
     */
    definitionPath: string;
    /**
     *
     */
    definitionBlueId: string;
    /**
     *
     */
    description?: string;
    /**
     * Children are intentionally omitted by definitionView. Metadata and canonical validation remain unchanged.
     */
    collapsed?: boolean;
    /**
     * Presentation is incomplete; canonical validation is independent and complete. Expand this path or follow the returned collection offset.
     */
    partial?: boolean;
    /**
     *
     */
    fieldsOffset?: number;
    /**
     *
     */
    nextFieldsOffset?: number;
    /**
     *
     */
    itemsOffset?: number;
    /**
     *
     */
    nextItemsOffset?: number;
}


/**
 * @export
 */
export const OperationInputDescriptorKindEnum = {
    Scalar: 'scalar',
    Object: 'object',
    List: 'list',
    Dictionary: 'dictionary',
    Reference: 'reference',
    Unrestricted: 'unrestricted',
} as const;
export type OperationInputDescriptorKindEnum = typeof OperationInputDescriptorKindEnum[keyof typeof OperationInputDescriptorKindEnum];

/**
 * @export
 */
export const OperationInputDescriptorWireFormatEnum = {
    Scalar: 'scalar',
    TypedScalar: 'typed_scalar',
    Array: 'array',
    TypedList: 'typed_list',
    Object: 'object',
    TypedObject: 'typed_object',
    Reference: 'reference',
    Unrestricted: 'unrestricted',
} as const;
export type OperationInputDescriptorWireFormatEnum = typeof OperationInputDescriptorWireFormatEnum[keyof typeof OperationInputDescriptorWireFormatEnum];

/**
 * @export
 */
export const OperationInputDescriptorValueEncodingEnum = {
    SafeNumberOrDecimalString: 'safe_number_or_decimal_string',
} as const;
export type OperationInputDescriptorValueEncodingEnum = typeof OperationInputDescriptorValueEncodingEnum[keyof typeof OperationInputDescriptorValueEncodingEnum];

/**
 * @export
 */
export const OperationInputDescriptorPrimitiveEnum = {
    String: 'string',
    Integer: 'integer',
    Number: 'number',
    Boolean: 'boolean',
} as const;
export type OperationInputDescriptorPrimitiveEnum = typeof OperationInputDescriptorPrimitiveEnum[keyof typeof OperationInputDescriptorPrimitiveEnum];

/**
 *
 * @export
 * @interface OperationInputField
 */
export interface OperationInputField {
    /**
     *
     */
    name: string;
    /**
     *
     */
    required: boolean;
    /**
     *
     */
    definition: OperationInputDescriptor;
}
/**
 * Authorized operations and exact reviewed publication from one selected-account snapshot. Continuation binds this publication and caller authority; a changed or unstable document returns document_changed and requires fresh discovery.
 * @export
 * @interface OperationPage
 */
export interface OperationPage {
    /**
     * Document lineage from the same snapshot as operation routes.
     */
    documentId: string | null;
    /**
     *
     */
    reviewedPublication: OperationReviewedPublication;
    /**
     *
     */
    items: Array<Operation>;
    /**
     *
     */
    nextPageToken: string | null;
    /**
     *
     */
    paginationMode: OperationPagePaginationModeEnum;
    /**
     *
     */
    total?: number;
}


/**
 * @export
 */
export const OperationPagePaginationModeEnum = {
    Snapshot: 'SNAPSHOT',
} as const;
export type OperationPagePaginationModeEnum = typeof OperationPagePaginationModeEnum[keyof typeof OperationPagePaginationModeEnum];

/**
 *
 * @export
 * @interface OperationPreparationIssue
 */
export interface OperationPreparationIssue {
    /**
     *
     */
    path: string;
    /**
     *
     */
    code: string;
    /**
     *
     */
    message: string;
}
/**
 *
 * @export
 * @interface OperationPreparationPreconditions
 */
export interface OperationPreparationPreconditions {
    /**
     *
     */
    requireExactDocumentVersion: OperationPreparationPreconditionsRequireExactDocumentVersionEnum;
}


/**
 * @export
 */
export const OperationPreparationPreconditionsRequireExactDocumentVersionEnum = {
    True: true,
} as const;
export type OperationPreparationPreconditionsRequireExactDocumentVersionEnum = typeof OperationPreparationPreconditionsRequireExactDocumentVersionEnum[keyof typeof OperationPreparationPreconditionsRequireExactDocumentVersionEnum];

/**
 * One exact caller-authorized discovery route selected for review.
 * @export
 * @interface OperationPreparationRoute
 */
export interface OperationPreparationRoute {
    /**
     *
     */
    channel: string;
    /**
     *
     */
    timeline: string;
    /**
     *
     */
    actor: string;
}
/**
 *
 * @export
 * @interface OperationPreparationValidation
 */
export interface OperationPreparationValidation {
    /**
     *
     */
    status: OperationPreparationValidationStatusEnum;
    /**
     *
     */
    issues: Array<OperationPreparationIssue>;
}


/**
 * @export
 */
export const OperationPreparationValidationStatusEnum = {
    MissingInput: 'missing_input',
    Valid: 'valid',
    Invalid: 'invalid',
    Unresolved: 'unresolved',
} as const;
export type OperationPreparationValidationStatusEnum = typeof OperationPreparationValidationStatusEnum[keyof typeof OperationPreparationValidationStatusEnum];

/**
 *
 * @export
 * @interface OperationReviewedPublication
 */
export interface OperationReviewedPublication {
    /**
     *
     */
    documentRef: BlueReference;
    /**
     *
     */
    epoch: OperationReviewedPublicationEpoch;
}
/**
 * @type OperationReviewedPublicationEpoch
 * Exact retained publication epoch. Use decimal text beyond client numeric precision.
 * @export
 */
export type OperationReviewedPublicationEpoch = number | string;
/**
 *
 * @export
 * @interface OperationRoute
 */
export interface OperationRoute {
    /**
     *
     */
    channel: string;
    /**
     *
     */
    timeline: string;
    /**
     *
     */
    actor: string;
    /**
     *
     */
    timelineName?: string;
}
/**
 *
 * @export
 * @interface PackageAdmissionDetails
 */
export interface PackageAdmissionDetails {
    /**
     *
     */
    packageId: string;
    /**
     *
     */
    installed: boolean;
}
/**
 *
 * @export
 * @interface PackageCatalogue
 */
export interface PackageCatalogue {
    /**
     *
     */
    items: Array<PackageCatalogueItem>;
    /**
     *
     */
    available: Array<PackageCatalogueItem>;
    /**
     *
     */
    supportedPackageBlueIds: Array<string>;
}
/**
 * Managed runtime packages and verified catalogue editions share these required fields. Archive metadata, imports/exports, installation checks and edition details are present for catalogue editions; protocolBlueId belongs to native runtime groups and may be null.
 * @export
 * @interface PackageCatalogueItem
 */
export interface PackageCatalogueItem {
    /**
     *
     */
    id: string;
    /**
     *
     */
    name: string;
    /**
     *
     */
    description: string;
    /**
     *
     */
    version?: string;
    /**
     *
     */
    blueId?: string;
    /**
     *
     */
    edition?: string;
    /**
     *
     */
    archiveSha256?: string;
    /**
     *
     */
    sourceArchiveSha256?: string;
    /**
     *
     */
    upstreamPackageBlueId?: string;
    /**
     *
     */
    adaptation?: string;
    /**
     *
     */
    protocolBlueId?: string | null;
    /**
     *
     */
    types: Array<PackageType>;
    /**
     *
     */
    dependencies: Array<PackageDependency>;
    /**
     *
     */
    imports?: { [key: string]: string; };
    /**
     *
     */
    exports?: { [key: string]: string; };
    /**
     *
     */
    createdAt?: string;
    /**
     *
     */
    removalReason: string | null;
    /**
     *
     */
    missingDependencies?: Array<PackageCatalogueItemMissingDependenciesInner>;
    /**
     *
     */
    dependents?: Array<PackageDependent>;
    /**
     *
     */
    installed: boolean;
    /**
     *
     */
    managed: boolean;
    /**
     *
     */
    hasSpecification: boolean;
    /**
     *
     */
    requiresCustomLogic: boolean;
    /**
     *
     */
    canRemove: boolean;
    /**
     *
     */
    canInstall?: boolean;
    /**
     *
     */
    hasPackageSpecification?: boolean;
}
/**
 *
 * @export
 * @interface PackageCatalogueItemMissingDependenciesInner
 */
export interface PackageCatalogueItemMissingDependenciesInner {
    /**
     *
     */
    name: string;
    /**
     *
     */
    blueId: string;
}
/**
 * Managed runtime dependencies use id/name; verified catalogue dependencies use name/blueId.
 * @export
 * @interface PackageDependency
 */
export interface PackageDependency {
    /**
     *
     */
    id?: string;
    /**
     *
     */
    name: string;
    /**
     *
     */
    blueId?: string;
}
/**
 *
 * @export
 * @interface PackageDependent
 */
export interface PackageDependent {
    /**
     *
     */
    id: string;
    /**
     *
     */
    name: string;
}
/**
 *
 * @export
 * @interface PackageInstallAdmission
 */
export interface PackageInstallAdmission {
    /**
     *
     */
    admissionId: string;
    /**
     *
     */
    status: PackageInstallAdmissionStatusEnum;
    /**
     *
     */
    problem?: AdmissionProblem;
    /**
     *
     */
    createdAt: string | null;
    /**
     *
     */
    completedAt: string | null;
    /**
     *
     */
    completionScope: PackageInstallAdmissionCompletionScopeEnum;
    /**
     *
     */
    kind: PackageInstallAdmissionKindEnum;
    /**
     *
     */
    details: any | null;
}


/**
 * @export
 */
export const PackageInstallAdmissionStatusEnum = {
    Pending: 'PENDING',
    Succeeded: 'SUCCEEDED',
    Failed: 'FAILED',
} as const;
export type PackageInstallAdmissionStatusEnum = typeof PackageInstallAdmissionStatusEnum[keyof typeof PackageInstallAdmissionStatusEnum];

/**
 * @export
 */
export const PackageInstallAdmissionCompletionScopeEnum = {
    PackageActionApplied: 'PACKAGE_ACTION_APPLIED',
} as const;
export type PackageInstallAdmissionCompletionScopeEnum = typeof PackageInstallAdmissionCompletionScopeEnum[keyof typeof PackageInstallAdmissionCompletionScopeEnum];

/**
 * @export
 */
export const PackageInstallAdmissionKindEnum = {
    PackageInstall: 'PACKAGE_INSTALL',
} as const;
export type PackageInstallAdmissionKindEnum = typeof PackageInstallAdmissionKindEnum[keyof typeof PackageInstallAdmissionKindEnum];

/**
 *
 * @export
 * @interface PackageRemoveAdmission
 */
export interface PackageRemoveAdmission {
    /**
     *
     */
    admissionId: string;
    /**
     *
     */
    status: PackageRemoveAdmissionStatusEnum;
    /**
     *
     */
    problem?: AdmissionProblem;
    /**
     *
     */
    createdAt: string | null;
    /**
     *
     */
    completedAt: string | null;
    /**
     *
     */
    completionScope: PackageRemoveAdmissionCompletionScopeEnum;
    /**
     *
     */
    kind: PackageRemoveAdmissionKindEnum;
    /**
     *
     */
    details: any | null;
}


/**
 * @export
 */
export const PackageRemoveAdmissionStatusEnum = {
    Pending: 'PENDING',
    Succeeded: 'SUCCEEDED',
    Failed: 'FAILED',
} as const;
export type PackageRemoveAdmissionStatusEnum = typeof PackageRemoveAdmissionStatusEnum[keyof typeof PackageRemoveAdmissionStatusEnum];

/**
 * @export
 */
export const PackageRemoveAdmissionCompletionScopeEnum = {
    PackageActionApplied: 'PACKAGE_ACTION_APPLIED',
} as const;
export type PackageRemoveAdmissionCompletionScopeEnum = typeof PackageRemoveAdmissionCompletionScopeEnum[keyof typeof PackageRemoveAdmissionCompletionScopeEnum];

/**
 * @export
 */
export const PackageRemoveAdmissionKindEnum = {
    PackageRemove: 'PACKAGE_REMOVE',
} as const;
export type PackageRemoveAdmissionKindEnum = typeof PackageRemoveAdmissionKindEnum[keyof typeof PackageRemoveAdmissionKindEnum];

/**
 * packageId is present for installed catalogue exports; native runtime aliases do not include it.
 * @export
 * @interface PackageType
 */
export interface PackageType {
    /**
     *
     */
    name: string;
    /**
     *
     */
    alias: string;
    /**
     *
     */
    package: string;
    /**
     *
     */
    blueId: string;
    /**
     *
     */
    packageId?: string;
}
/**
 *
 * @export
 * @interface PartyAccessAutomationSettings
 */
export interface PartyAccessAutomationSettings {
    /**
     * Execute approved, bootstrap-scoped owner access updates automatically. Defaults to true.
     */
    automaticallyUpdatePartyAccess: boolean;
}
/**
 *
 * @export
 * @interface PartyAccessRequest
 */
export interface PartyAccessRequest {
    /**
     *
     */
    id: string;
    /**
     *
     */
    sessionId: string;
    /**
     *
     */
    party: string;
    /**
     *
     */
    admissionRef: string;
    /**
     *
     */
    status: string;
    /**
     *
     */
    request: { [key: string]: any; };
    /**
     *
     */
    problem?: { [key: string]: any; };
}
/**
 *
 * @export
 * @interface PartyAccessRequests
 */
export interface PartyAccessRequests {
    /**
     *
     */
    items: Array<PartyAccessRequest>;
}
/**
 *
 * @export
 * @interface PauseAdmission
 */
export interface PauseAdmission {
    /**
     *
     */
    admissionId: string;
    /**
     *
     */
    status: PauseAdmissionStatusEnum;
    /**
     *
     */
    problem?: AdmissionProblem;
    /**
     *
     */
    createdAt: string | null;
    /**
     *
     */
    completedAt: string | null;
    /**
     *
     */
    completionScope: PauseAdmissionCompletionScopeEnum;
    /**
     *
     */
    kind: PauseAdmissionKindEnum;
    /**
     *
     */
    details: ControlAdmissionDetails | null;
}


/**
 * @export
 */
export const PauseAdmissionStatusEnum = {
    Pending: 'PENDING',
    Succeeded: 'SUCCEEDED',
    Failed: 'FAILED',
} as const;
export type PauseAdmissionStatusEnum = typeof PauseAdmissionStatusEnum[keyof typeof PauseAdmissionStatusEnum];

/**
 * @export
 */
export const PauseAdmissionCompletionScopeEnum = {
    ControlApplied: 'CONTROL_APPLIED',
} as const;
export type PauseAdmissionCompletionScopeEnum = typeof PauseAdmissionCompletionScopeEnum[keyof typeof PauseAdmissionCompletionScopeEnum];

/**
 * @export
 */
export const PauseAdmissionKindEnum = {
    Pause: 'PAUSE',
} as const;
export type PauseAdmissionKindEnum = typeof PauseAdmissionKindEnum[keyof typeof PauseAdmissionKindEnum];

/**
 * Requested Unix microseconds, not measured execution time. Wall time alone does not establish source completeness.
 * @export
 * @interface PendingTimer
 */
export interface PendingTimer {
    /**
     *
     */
    id: string;
    /**
     *
     */
    channel: string;
    /**
     *
     */
    generation: number;
    /**
     *
     */
    key?: string | null;
    /**
     *
     */
    timestamp: number;
    /**
     *
     */
    source: string;
    /**
     *
     */
    entry: string;
    /**
     *
     */
    message: string;
    /**
     *
     */
    request?: string;
    /**
     *
     */
    status: PendingTimerStatusEnum;
}


/**
 * @export
 */
export const PendingTimerStatusEnum = {
    Pending: 'PENDING',
} as const;
export type PendingTimerStatusEnum = typeof PendingTimerStatusEnum[keyof typeof PendingTimerStatusEnum];

/**
 *
 * @export
 * @interface PhysicalContentCache
 */
export interface PhysicalContentCache {
    /**
     *
     */
    entries: number;
    /**
     *
     */
    estimatedBytes: number;
    /**
     *
     */
    budgetBytes: number;
    /**
     *
     */
    hits: number;
    /**
     *
     */
    misses: number;
}
/**
 *
 * @export
 * @interface PrepareDocumentOperation
 */
export interface PrepareDocumentOperation {
    /**
     * Exact discovery dispatch key, not a display name.
     */
    operationName: string;
    /**
     *
     */
    reviewedPublication: OperationReviewedPublication;
    /**
     *
     */
    route: OperationPreparationRoute;
    /**
     * User-supplied native request encoded as JSON text, including scalar, list, object or null. Omission requests the input description; it is distinct from explicit null or an empty object. Numeric values are preserved without conversion to client floating point.
     */
    requestJson?: string;
    /**
     *
     */
    definitionView?: OperationDefinitionView;
    /**
     * Canonical operation-definition selector from a returned descriptor. Empty selects the root; only fields, items, item, key and value slots are accepted. Does not change validation or digest.
     */
    descriptorPath?: string;
    /**
     * Continuation offset from nextFieldsOffset or nextItemsOffset for the selected definition.
     */
    descriptorOffset?: number;
}
/**
 *
 * @export
 * @interface PreparedDocumentOperation
 */
export interface PreparedDocumentOperation {
    /**
     *
     */
    accountId: string;
    /**
     *
     */
    sessionId: string;
    /**
     *
     */
    documentId: string | null;
    /**
     *
     */
    reviewedPublication: OperationReviewedPublication;
    /**
     *
     */
    operationName: string;
    /**
     *
     */
    route: OperationPreparationRoute;
    /**
     *
     */
    definition: OperationInputDefinition;
    /**
     *
     */
    validation: OperationPreparationValidation;
    /**
     * Original user-supplied native request JSON; absent when no input was supplied.
     */
    requestJson?: string;
    /**
     * Exact native operation message JSON object for a separate confirmed submission.
     */
    messageJson?: string;
    /**
     *
     */
    preconditions: OperationPreparationPreconditions;
}
/**
 * HTTP errors always use application/problem+json. HEAD errors have no body.
 * @export
 * @interface Problem
 */
export interface Problem {
    /**
     *
     */
    type: string;
    /**
     *
     */
    title: string;
    /**
     *
     */
    status: number;
    /**
     *
     */
    detail: string;
    /**
     *
     */
    code: string;
    /**
     *
     */
    instance: string;
    /**
     *
     */
    requestId: string;
}
/**
 *
 * @export
 * @interface Problem400
 */
export interface Problem400 {
    /**
     *
     */
    type: string;
    /**
     *
     */
    title: string;
    /**
     *
     */
    status: Problem400StatusEnum;
    /**
     *
     */
    detail: string;
    /**
     *
     */
    code: string;
    /**
     *
     */
    instance: string;
    /**
     *
     */
    requestId: string;
}


/**
 * @export
 */
export const Problem400StatusEnum = {
    NUMBER_400: 400,
} as const;
export type Problem400StatusEnum = typeof Problem400StatusEnum[keyof typeof Problem400StatusEnum];

/**
 *
 * @export
 * @interface Problem401
 */
export interface Problem401 {
    /**
     *
     */
    type: string;
    /**
     *
     */
    title: string;
    /**
     *
     */
    status: Problem401StatusEnum;
    /**
     *
     */
    detail: string;
    /**
     *
     */
    code: string;
    /**
     *
     */
    instance: string;
    /**
     *
     */
    requestId: string;
}


/**
 * @export
 */
export const Problem401StatusEnum = {
    NUMBER_401: 401,
} as const;
export type Problem401StatusEnum = typeof Problem401StatusEnum[keyof typeof Problem401StatusEnum];

/**
 *
 * @export
 * @interface Problem403
 */
export interface Problem403 {
    /**
     *
     */
    type: string;
    /**
     *
     */
    title: string;
    /**
     *
     */
    status: Problem403StatusEnum;
    /**
     *
     */
    detail: string;
    /**
     *
     */
    code: string;
    /**
     *
     */
    instance: string;
    /**
     *
     */
    requestId: string;
}


/**
 * @export
 */
export const Problem403StatusEnum = {
    NUMBER_403: 403,
} as const;
export type Problem403StatusEnum = typeof Problem403StatusEnum[keyof typeof Problem403StatusEnum];

/**
 *
 * @export
 * @interface Problem404
 */
export interface Problem404 {
    /**
     *
     */
    type: string;
    /**
     *
     */
    title: string;
    /**
     *
     */
    status: Problem404StatusEnum;
    /**
     *
     */
    detail: string;
    /**
     *
     */
    code: string;
    /**
     *
     */
    instance: string;
    /**
     *
     */
    requestId: string;
}


/**
 * @export
 */
export const Problem404StatusEnum = {
    NUMBER_404: 404,
} as const;
export type Problem404StatusEnum = typeof Problem404StatusEnum[keyof typeof Problem404StatusEnum];

/**
 *
 * @export
 * @interface Problem406
 */
export interface Problem406 {
    /**
     *
     */
    type: string;
    /**
     *
     */
    title: string;
    /**
     *
     */
    status: Problem406StatusEnum;
    /**
     *
     */
    detail: string;
    /**
     *
     */
    code: string;
    /**
     *
     */
    instance: string;
    /**
     *
     */
    requestId: string;
}


/**
 * @export
 */
export const Problem406StatusEnum = {
    NUMBER_406: 406,
} as const;
export type Problem406StatusEnum = typeof Problem406StatusEnum[keyof typeof Problem406StatusEnum];

/**
 *
 * @export
 * @interface Problem409
 */
export interface Problem409 {
    /**
     *
     */
    type: string;
    /**
     *
     */
    title: string;
    /**
     *
     */
    status: Problem409StatusEnum;
    /**
     *
     */
    detail: string;
    /**
     *
     */
    code: string;
    /**
     *
     */
    instance: string;
    /**
     *
     */
    requestId: string;
}


/**
 * @export
 */
export const Problem409StatusEnum = {
    NUMBER_409: 409,
} as const;
export type Problem409StatusEnum = typeof Problem409StatusEnum[keyof typeof Problem409StatusEnum];

/**
 *
 * @export
 * @interface Problem413
 */
export interface Problem413 {
    /**
     *
     */
    type: string;
    /**
     *
     */
    title: string;
    /**
     *
     */
    status: Problem413StatusEnum;
    /**
     *
     */
    detail: string;
    /**
     *
     */
    code: string;
    /**
     *
     */
    instance: string;
    /**
     *
     */
    requestId: string;
}


/**
 * @export
 */
export const Problem413StatusEnum = {
    NUMBER_413: 413,
} as const;
export type Problem413StatusEnum = typeof Problem413StatusEnum[keyof typeof Problem413StatusEnum];

/**
 *
 * @export
 * @interface Problem415
 */
export interface Problem415 {
    /**
     *
     */
    type: string;
    /**
     *
     */
    title: string;
    /**
     *
     */
    status: Problem415StatusEnum;
    /**
     *
     */
    detail: string;
    /**
     *
     */
    code: string;
    /**
     *
     */
    instance: string;
    /**
     *
     */
    requestId: string;
}


/**
 * @export
 */
export const Problem415StatusEnum = {
    NUMBER_415: 415,
} as const;
export type Problem415StatusEnum = typeof Problem415StatusEnum[keyof typeof Problem415StatusEnum];

/**
 *
 * @export
 * @interface Problem422
 */
export interface Problem422 {
    /**
     *
     */
    type: string;
    /**
     *
     */
    title: string;
    /**
     *
     */
    status: Problem422StatusEnum;
    /**
     *
     */
    detail: string;
    /**
     *
     */
    code: string;
    /**
     *
     */
    instance: string;
    /**
     *
     */
    requestId: string;
}


/**
 * @export
 */
export const Problem422StatusEnum = {
    NUMBER_422: 422,
} as const;
export type Problem422StatusEnum = typeof Problem422StatusEnum[keyof typeof Problem422StatusEnum];

/**
 *
 * @export
 * @interface Problem429
 */
export interface Problem429 {
    /**
     *
     */
    type: string;
    /**
     *
     */
    title: string;
    /**
     *
     */
    status: Problem429StatusEnum;
    /**
     *
     */
    detail: string;
    /**
     *
     */
    code: string;
    /**
     *
     */
    instance: string;
    /**
     *
     */
    requestId: string;
}


/**
 * @export
 */
export const Problem429StatusEnum = {
    NUMBER_429: 429,
} as const;
export type Problem429StatusEnum = typeof Problem429StatusEnum[keyof typeof Problem429StatusEnum];

/**
 *
 * @export
 * @interface Problem503
 */
export interface Problem503 {
    /**
     *
     */
    type: string;
    /**
     *
     */
    title: string;
    /**
     *
     */
    status: Problem503StatusEnum;
    /**
     *
     */
    detail: string;
    /**
     *
     */
    code: string;
    /**
     *
     */
    instance: string;
    /**
     *
     */
    requestId: string;
}


/**
 * @export
 */
export const Problem503StatusEnum = {
    NUMBER_503: 503,
} as const;
export type Problem503StatusEnum = typeof Problem503StatusEnum[keyof typeof Problem503StatusEnum];

/**
 * Current processing state. checkedBoundary is a timestamp boundary in microseconds; it does not independently prove completion of a business process.
 * @export
 * @interface Processing
 */
export interface Processing {
    /**
     *
     */
    state: ProcessingStateEnum;
    /**
     *
     */
    nativeState: ProcessingNativeStateEnum;
    /**
     *
     */
    checkedBoundary: number;
    /**
     *
     */
    reason: ProcessingReason | null;
    /**
     *
     */
    retryable: boolean;
}


/**
 * @export
 */
export const ProcessingStateEnum = {
    Ready: 'READY',
    Processing: 'PROCESSING',
    Waiting: 'WAITING',
    Paused: 'PAUSED',
    Error: 'ERROR',
    HostIncomplete: 'HOST_INCOMPLETE',
} as const;
export type ProcessingStateEnum = typeof ProcessingStateEnum[keyof typeof ProcessingStateEnum];

/**
 * @export
 */
export const ProcessingNativeStateEnum = {
    Ready: 'READY',
    Processing: 'PROCESSING',
    Waiting: 'WAITING',
    Error: 'ERROR',
    HostIncomplete: 'HOST_INCOMPLETE',
} as const;
export type ProcessingNativeStateEnum = typeof ProcessingNativeStateEnum[keyof typeof ProcessingNativeStateEnum];

/**
 *
 * @export
 * @interface ProcessingErrorEventPayload
 */
export interface ProcessingErrorEventPayload {
    /**
     *
     */
    status: ProcessingErrorEventPayloadStatusEnum;
    /**
     *
     */
    issue: object | null;
}


/**
 * @export
 */
export const ProcessingErrorEventPayloadStatusEnum = {
    Error: 'ERROR',
    HostIncomplete: 'HOST_INCOMPLETE',
} as const;
export type ProcessingErrorEventPayloadStatusEnum = typeof ProcessingErrorEventPayloadStatusEnum[keyof typeof ProcessingErrorEventPayloadStatusEnum];

/**
 * One explanation for waiting, paused or failed processing. Native diagnostic facts are preserved in details.
 * @export
 * @interface ProcessingReason
 */
export interface ProcessingReason {
    /**
     *
     */
    code: string;
    /**
     *
     */
    message: string;
    /**
     * Retained code-specific facts, including resource IDs, gas requirements and the gasEvaluation used for an explicit retry.
     */
    details?: { [key: string]: any; };
    /**
     *
     */
    phase?: string;
}
/**
 * Retained original entries in native order, pinned to the projection sequence selected on the first page. Later imports of older timestamps do not enter that pagination. beforeUs is an exclusive timestamp filter; afterEntryBlueId must be a retained entry of the selected timeline and range. Neither guaranteeRef nor attestationRef is generated; this page does not prove completeness.
 * @export
 * @interface ProjectedTimelineEntriesPage
 */
export interface ProjectedTimelineEntriesPage {
    /**
     *
     */
    timelineRef: BlueReference;
    /**
     *
     */
    items: Array<TimelineEntry>;
    /**
     *
     */
    nextPageToken: string | null;
    /**
     *
     */
    hasMore: boolean;
    /**
     *
     */
    paginationMode: ProjectedTimelineEntriesPagePaginationModeEnum;
}


/**
 * @export
 */
export const ProjectedTimelineEntriesPagePaginationModeEnum = {
    ProjectedRetainedEntries: 'PROJECTED_RETAINED_ENTRIES',
} as const;
export type ProjectedTimelineEntriesPagePaginationModeEnum = typeof ProjectedTimelineEntriesPagePaginationModeEnum[keyof typeof ProjectedTimelineEntriesPagePaginationModeEnum];

/**
 *
 * @export
 * @interface ProposeMandateRequest
 */
export interface ProposeMandateRequest {
    /**
     *
     */
    name: string;
    /**
     *
     */
    description?: string;
    /**
     * Native Operation Mandate scope entries proposed by the Agent for human review.
     */
    scope: { [key: string]: any; };
}
/**
 * Existing signed provider range; upper completeness boundary is pinned by continuation. Requests beyond the established provider frontier fail without advancing it.
 * @export
 * @interface ProviderRange
 */
export interface ProviderRange {
    /**
     *
     */
    timelineRef: BlueReference;
    /**
     *
     */
    beforeUs: number;
    /**
     *
     */
    afterEntryRef: BlueReference | null;
    /**
     *
     */
    items: Array<TimelineEntry>;
    /**
     *
     */
    guaranteeRef: BlueReference;
    /**
     *
     */
    attestationRef: BlueReference;
    /**
     *
     */
    nextPageToken: string | null;
    /**
     *
     */
    hasMore: boolean;
    /**
     *
     */
    paginationMode: ProviderRangePaginationModeEnum;
}


/**
 * @export
 */
export const ProviderRangePaginationModeEnum = {
    BoundedProviderRange: 'BOUNDED_PROVIDER_RANGE',
} as const;
export type ProviderRangePaginationModeEnum = typeof ProviderRangePaginationModeEnum[keyof typeof ProviderRangePaginationModeEnum];

/**
 * Existing signed provider range; upper completeness boundary is pinned by continuation. Requests beyond the established provider frontier fail without advancing it.
 * @export
 * @interface ProviderRangeProof
 */
export interface ProviderRangeProof {
    /**
     *
     */
    timelineRef: BlueReference;
    /**
     *
     */
    beforeUs: number;
    /**
     *
     */
    afterEntryRef: BlueReference | null;
    /**
     *
     */
    items: Array<BlueReference>;
    /**
     *
     */
    guaranteeRef: BlueReference;
    /**
     *
     */
    attestationRef: BlueReference;
    /**
     *
     */
    guarantee: NativeExactContent;
    /**
     *
     */
    attestation: NativeExactContent;
    /**
     *
     */
    nextPageToken: string | null;
    /**
     *
     */
    hasMore: boolean;
    /**
     *
     */
    paginationMode: ProviderRangeProofPaginationModeEnum;
}


/**
 * @export
 */
export const ProviderRangeProofPaginationModeEnum = {
    BoundedProviderRange: 'BOUNDED_PROVIDER_RANGE',
} as const;
export type ProviderRangeProofPaginationModeEnum = typeof ProviderRangeProofPaginationModeEnum[keyof typeof ProviderRangeProofPaginationModeEnum];

/**
 * POST body selectors only. An absent body or {} selects defaults. The durable receipt normalizes omitted limit to 50 and freezes the default frontier for same-key replay; it never advances completeness.
 * @export
 * @interface ProviderSelectors
 */
export interface ProviderSelectors {
    /**
     *
     */
    beforeUs?: number;
    /**
     *
     */
    afterEntryBlueId?: string;
    /**
     *
     */
    pageToken?: string;
    /**
     *
     */
    limit?: number;
}
/**
 * The latest observed stable epoch and its commit time in Unix milliseconds, or an empty object if none is available. Observations are cleared when the service restarts.
 * @export
 * @interface PublicationObservation
 */
export interface PublicationObservation {
    /**
     *
     */
    epoch?: number;
    /**
     *
     */
    committedAtEpochMs?: number;
}
/**
 * Relative navigation route. blueId is included for document matches only; a content route is a fallback and does not assert that content exists.
 * @export
 * @interface ReferenceNavigation
 */
export interface ReferenceNavigation {
    /**
     *
     */
    route: string;
    /**
     *
     */
    kind: ReferenceNavigationKindEnum;
    /**
     *
     */
    blueId?: string;
}


/**
 * @export
 */
export const ReferenceNavigationKindEnum = {
    Type: 'type',
    Timeline: 'timeline',
    Document: 'document',
    Content: 'content',
} as const;
export type ReferenceNavigationKindEnum = typeof ReferenceNavigationKindEnum[keyof typeof ReferenceNavigationKindEnum];

/**
 *
 * @export
 * @interface RelationshipEndpoint
 */
export interface RelationshipEndpoint {
    /**
     * Exact source/target document identity retained with this occurrence.
     */
    documentRef: BlueReference;
    /**
     * Accessible hosted session handle when known; null does not manufacture a registration for the referenced document.
     */
    sessionId: string | null;
}
/**
 * Current retained relationship view. Only outgoing traversal is pinned to its observed revision, and continuation fails if the graph changes. Incoming and both traverse LIVE pages with a revision per page. Historical positionBlueId selection is unsupported.
 * @export
 * @interface RelationshipPage
 */
export interface RelationshipPage {
    /**
     * Selected receiver History Position for outgoing traversals. Null for incoming or both, which do not have one receiver position, and also possible before an outgoing position has been established.
     */
    positionRef: BlueReference | null;
    /**
     * Opaque revision of the selected relationship view/traversal. For LIVE incoming or both reads, each page declares the revision of that page without promising one snapshot across pages.
     */
    observationVersion: string;
    /**
     * Retained managed occurrences in stable occurrence identity/generation order.
     */
    items: Array<ManagedRelationship>;
    /**
     * Opaque continuation over the same authorized relationship traversal; null ends that traversal.
     */
    nextPageToken: string | null;
}
/**
 *
 * @export
 * @interface RemoveAdmission
 */
export interface RemoveAdmission {
    /**
     *
     */
    admissionId: string;
    /**
     *
     */
    status: RemoveAdmissionStatusEnum;
    /**
     *
     */
    problem?: AdmissionProblem;
    /**
     *
     */
    createdAt: string | null;
    /**
     *
     */
    completedAt: string | null;
    /**
     * REGISTRATION_UNLINKED confirms removal of the registration. Earlier removal receipts retain CONTROL_APPLIED when they confirm a control change.
     */
    completionScope: RemoveAdmissionCompletionScopeEnum;
    /**
     *
     */
    kind: RemoveAdmissionKindEnum;
    /**
     *
     */
    details: RemoveAdmissionDetails | null;
}


/**
 * @export
 */
export const RemoveAdmissionStatusEnum = {
    Pending: 'PENDING',
    Succeeded: 'SUCCEEDED',
    Failed: 'FAILED',
} as const;
export type RemoveAdmissionStatusEnum = typeof RemoveAdmissionStatusEnum[keyof typeof RemoveAdmissionStatusEnum];

/**
 * @export
 */
export const RemoveAdmissionCompletionScopeEnum = {
    RegistrationUnlinked: 'REGISTRATION_UNLINKED',
    ControlApplied: 'CONTROL_APPLIED',
} as const;
export type RemoveAdmissionCompletionScopeEnum = typeof RemoveAdmissionCompletionScopeEnum[keyof typeof RemoveAdmissionCompletionScopeEnum];

/**
 * @export
 */
export const RemoveAdmissionKindEnum = {
    Remove: 'REMOVE',
} as const;
export type RemoveAdmissionKindEnum = typeof RemoveAdmissionKindEnum[keyof typeof RemoveAdmissionKindEnum];

/**
 * Unlinking returns sessionId and processingChanged:false. Earlier removal receipts may omit processingChanged and confirm CONTROL_APPLIED.
 * @export
 * @interface RemoveAdmissionDetails
 */
export interface RemoveAdmissionDetails {
    /**
     *
     */
    sessionId: string;
    /**
     *
     */
    processingChanged?: RemoveAdmissionDetailsProcessingChangedEnum;
}


/**
 * @export
 */
export const RemoveAdmissionDetailsProcessingChangedEnum = {
    False: false,
} as const;
export type RemoveAdmissionDetailsProcessingChangedEnum = typeof RemoveAdmissionDetailsProcessingChangedEnum[keyof typeof RemoveAdmissionDetailsProcessingChangedEnum];

/**
 *
 * @export
 * @interface ResumeAdmission
 */
export interface ResumeAdmission {
    /**
     *
     */
    admissionId: string;
    /**
     *
     */
    status: ResumeAdmissionStatusEnum;
    /**
     *
     */
    problem?: AdmissionProblem;
    /**
     *
     */
    createdAt: string | null;
    /**
     *
     */
    completedAt: string | null;
    /**
     *
     */
    completionScope: ResumeAdmissionCompletionScopeEnum;
    /**
     *
     */
    kind: ResumeAdmissionKindEnum;
    /**
     *
     */
    details: ControlAdmissionDetails | null;
}


/**
 * @export
 */
export const ResumeAdmissionStatusEnum = {
    Pending: 'PENDING',
    Succeeded: 'SUCCEEDED',
    Failed: 'FAILED',
} as const;
export type ResumeAdmissionStatusEnum = typeof ResumeAdmissionStatusEnum[keyof typeof ResumeAdmissionStatusEnum];

/**
 * @export
 */
export const ResumeAdmissionCompletionScopeEnum = {
    ControlApplied: 'CONTROL_APPLIED',
} as const;
export type ResumeAdmissionCompletionScopeEnum = typeof ResumeAdmissionCompletionScopeEnum[keyof typeof ResumeAdmissionCompletionScopeEnum];

/**
 * @export
 */
export const ResumeAdmissionKindEnum = {
    Resume: 'RESUME',
} as const;
export type ResumeAdmissionKindEnum = typeof ResumeAdmissionKindEnum[keyof typeof ResumeAdmissionKindEnum];

/**
 *
 * @export
 * @interface RetryAdmission
 */
export interface RetryAdmission {
    /**
     *
     */
    admissionId: string;
    /**
     *
     */
    status: RetryAdmissionStatusEnum;
    /**
     *
     */
    problem?: AdmissionProblem;
    /**
     *
     */
    createdAt: string | null;
    /**
     *
     */
    completedAt: string | null;
    /**
     *
     */
    completionScope: RetryAdmissionCompletionScopeEnum;
    /**
     *
     */
    kind: RetryAdmissionKindEnum;
    /**
     *
     */
    details: ControlAdmissionDetails | null;
}


/**
 * @export
 */
export const RetryAdmissionStatusEnum = {
    Pending: 'PENDING',
    Succeeded: 'SUCCEEDED',
    Failed: 'FAILED',
} as const;
export type RetryAdmissionStatusEnum = typeof RetryAdmissionStatusEnum[keyof typeof RetryAdmissionStatusEnum];

/**
 * @export
 */
export const RetryAdmissionCompletionScopeEnum = {
    ControlApplied: 'CONTROL_APPLIED',
} as const;
export type RetryAdmissionCompletionScopeEnum = typeof RetryAdmissionCompletionScopeEnum[keyof typeof RetryAdmissionCompletionScopeEnum];

/**
 * @export
 */
export const RetryAdmissionKindEnum = {
    Retry: 'RETRY',
} as const;
export type RetryAdmissionKindEnum = typeof RetryAdmissionKindEnum[keyof typeof RetryAdmissionKindEnum];

/**
 * Confirms that a delivery retry has been queued. It does not confirm processing by the recipient.
 * @export
 * @interface RetryReceipt
 */
export interface RetryReceipt {
    /**
     *
     */
    deliveryId: string;
    /**
     *
     */
    status: RetryReceiptStatusEnum;
}


/**
 * @export
 */
export const RetryReceiptStatusEnum = {
    Pending: 'PENDING',
} as const;
export type RetryReceiptStatusEnum = typeof RetryReceiptStatusEnum[keyof typeof RetryReceiptStatusEnum];

/**
 *
 * @export
 * @interface RevokeAgentConnection
 */
export interface RevokeAgentConnection {
    /**
     *
     */
    expectedRevision: number;
}
/**
 *
 * @export
 * @interface RevokeInvitation
 */
export interface RevokeInvitation {
    /**
     *
     */
    expectedRevision: number;
}
/**
 * Exact language, protocol and specification identities of the installed runtime.
 * @export
 * @interface RuntimeIdentities
 */
export interface RuntimeIdentities {
    /**
     *
     */
    language: string;
    /**
     *
     */
    bex: string;
    /**
     *
     */
    bexSpecification: string;
    /**
     *
     */
    contracts: string;
    /**
     *
     */
    contractsSpecification: string;
    /**
     *
     */
    coordination?: string;
    /**
     *
     */
    coordinationSpecification?: string;
    /**
     *
     */
    mandates?: string;
    /**
     *
     */
    time?: string;
    /**
     * Exact installed provider protocol identity.
     */
    provider?: string;
    /**
     * Exact current Time protocol identity when installed.
     */
    currentTime?: string;
    /**
     * Exact current Mandates protocol identity when installed.
     */
    currentMandates?: string;
    /**
     * Exact definition-preserving document read capability protocol identity when selected.
     */
    definitionReadV1?: string;
    /**
     * Exact non-additive canonical wire measurement capability identity when selected.
     */
    canonicalWireMeasurementV1?: string;
    /**
     * Exact identities of installed native packages.
     */
    supportedPackages: Array<string>;
}
/**
 *
 * @export
 * @interface RuntimeRecovery
 */
export interface RuntimeRecovery {
    /**
     *
     */
    status: RuntimeRecoveryStatusEnum;
}


/**
 * @export
 */
export const RuntimeRecoveryStatusEnum = {
    RecoveryRequested: 'recovery_requested',
} as const;
export type RuntimeRecoveryStatusEnum = typeof RuntimeRecoveryStatusEnum[keyof typeof RuntimeRecoveryStatusEnum];

/**
 * Selected stable publication with retained cause, measured gas and an exact evidence target. No previousEpochRef is invented: Core does not retain a separate epoch-content object. Native per-transition gas remains incremental; COMPLETE may reuse an earlier receipt with zero additional gas.
 * @export
 * @interface StableEpoch
 */
export interface StableEpoch {
    /**
     * Stable publication number within the document lineage; zero identifies the initial stable publication.
     */
    epoch: number;
    /**
     * Identity of the document lineage, shared by its versions; distinct from the registration handle and exact content BlueId.
     */
    documentId: string;
    /**
     * Exact Blue reference to the document value at the selected publication.
     */
    documentRef: BlueReference;
    /**
     * Exact History Position for this publication; distinct from the document value and epoch number.
     */
    positionRef: BlueReference;
    /**
     * Exact Processing Receipt for the completed computation represented by this publication.
     */
    receiptRef: BlueReference;
    /**
     * Exact references to values emitted by this publication; an empty list means it emitted none.
     */
    emitted: Array<BlueReference>;
    /**
     *
     */
    gas: StableEpochGas | null;
    /**
     * Exact document value pinned to documentRef, included on epoch detail. Cyclic values retain references without an owning-set proof wrapper.
     */
    document?: object;
    /**
     *
     */
    cause: EpochCause | null;
    /**
     * Sum of known receipt gas in this document history through the selected epoch, counting each receipt once, including non-stable transitions. Null if a required measurement is unavailable; not an account debit.
     */
    cumulativeGas: number | null;
    /**
     *
     */
    evidenceTarget: EvidenceTarget;
}
/**
 * Gas retained for the selected publication transition; COMPLETE may reuse an earlier receipt with zero incremental gas. Not cumulative billing.
 * @export
 * @interface StableEpochGas
 */
export interface StableEpochGas {
    /**
     *
     */
    used: number;
    /**
     * Original shared execution budget, when retained and correlated to this receipt. Never taken from current account configuration.
     */
    limit?: number;
}
/**
 * Bounded retained history pinned on first page.
 * @export
 * @interface StableEpochPage
 */
export interface StableEpochPage {
    /**
     *
     */
    items: Array<StableEpoch>;
    /**
     *
     */
    nextPageToken: string | null;
    /**
     *
     */
    paginationMode?: string;
    /**
     *
     */
    total?: number;
    /**
     *
     */
    hasMore?: boolean;
}
/**
 * Event-publication counters are always included. Other fields depend on the configured storage backend; SQLite reports physicalSchema/encodedMirrorEntries, while the publication journal reports replay, immutable-content and journal counters.
 * @export
 * @interface StorageStatistics
 */
export interface StorageStatistics {
    /**
     *
     */
    recordKeys?: number;
    /**
     *
     */
    liveRecords?: number;
    /**
     *
     */
    versions?: number;
    /**
     *
     */
    readers?: number;
    /**
     *
     */
    physicalSchema?: number;
    /**
     *
     */
    encodedMirrorEntries?: number;
    /**
     *
     */
    sequence?: number;
    /**
     *
     */
    knownPublications?: number;
    /**
     *
     */
    knownApplications?: number;
    /**
     *
     */
    journalReplayLoads?: number;
    /**
     *
     */
    journalReplayLoadNanos?: number;
    /**
     *
     */
    journalReplayApplyNanos?: number;
    /**
     *
     */
    journalReplayMaxPendingRows?: number;
    /**
     *
     */
    immutableContents?: number;
    /**
     *
     */
    immutableCharacters?: number;
    /**
     *
     */
    compactionKeysVisited?: number;
    /**
     *
     */
    contentNodesDecoded?: number;
    /**
     *
     */
    lockWaitNanos?: number;
    /**
     *
     */
    journalPrepareNanos?: number;
    /**
     *
     */
    journalPersistNanos?: number;
    /**
     *
     */
    journalApplyNanos?: number;
    /**
     *
     */
    eventPreparationNanos: number;
    /**
     *
     */
    commitNanos: number;
    /**
     *
     */
    commitAttempts: number;
    /**
     *
     */
    commitConflicts: number;
    /**
     *
     */
    decodedCache?: DecodedCacheStatistics;
    /**
     *
     */
    encodingCache?: EncodingCacheStatistics;
    /**
     * Named cumulative counters; keys are created when the corresponding activity occurs.
     */
    journal?: { [key: string]: number; };
}
/**
 *
 * @export
 * @interface SubscribeAgentEvents
 */
export interface SubscribeAgentEvents {
    /**
     *
     */
    name: SubscribeAgentEventsNameEnum;
    /**
     *
     */
    arguments: AgentEventArguments;
    /**
     *
     */
    delivery: AgentEventSubscribeDelivery;
    /**
     *
     */
    cursor?: string | null;
    /**
     * Requested lifetime in milliseconds; the server grants at most 24 hours.
     */
    ttlMs?: number | null;
}


/**
 * @export
 */
export const SubscribeAgentEventsNameEnum = {
    DocumentUpdated: 'document.updated',
} as const;
export type SubscribeAgentEventsNameEnum = typeof SubscribeAgentEventsNameEnum[keyof typeof SubscribeAgentEventsNameEnum];

/**
 * Host-managed owned timeline metadata. timelineId is the host management identity; blueId is the distinct native timeline identity and matches operations[].routes[].timeline. Provider-backed owned timelines use ImportedTimeline instead. completeBefore is the established native frontier.
 * @export
 * @interface Timeline
 */
export interface Timeline {
    /**
     *
     */
    timelineId: string;
    /**
     *
     */
    blueId: string;
    /**
     *
     */
    name: string;
    /**
     *
     */
    actors: Array<string>;
    /**
     *
     */
    createdAt: string | null;
    /**
     *
     */
    lastChangedAt: string | null;
    /**
     *
     */
    completeBefore: number;
}
/**
 *
 * @export
 * @interface TimelineAccess
 */
export interface TimelineAccess {
    /**
     *
     */
    id: string;
    /**
     *
     */
    allowedKeys: Array<string>;
    /**
     *
     */
    allowedTimelines: Array<string>;
    /**
     * Native Blue document or signed provider evidence; its domain fields are preserved.
     */
    document: object;
    /**
     * Native Blue document or signed provider evidence; its domain fields are preserved.
     */
    current: object;
}
/**
 *
 * @export
 * @interface TimelineAccessList
 */
export interface TimelineAccessList {
    /**
     *
     */
    items: Array<TimelineAccess>;
}
/**
 *
 * @export
 * @interface TimelineAdmission
 */
export interface TimelineAdmission {
    /**
     *
     */
    admissionId: string;
    /**
     *
     */
    status: TimelineAdmissionStatusEnum;
    /**
     *
     */
    problem?: AdmissionProblem;
    /**
     *
     */
    createdAt: string | null;
    /**
     *
     */
    completedAt: string | null;
    /**
     *
     */
    completionScope: TimelineAdmissionCompletionScopeEnum;
    /**
     *
     */
    kind: TimelineAdmissionKindEnum;
    /**
     *
     */
    details: TimelineAdmissionDetails | null;
}


/**
 * @export
 */
export const TimelineAdmissionStatusEnum = {
    Pending: 'PENDING',
    Succeeded: 'SUCCEEDED',
    Failed: 'FAILED',
} as const;
export type TimelineAdmissionStatusEnum = typeof TimelineAdmissionStatusEnum[keyof typeof TimelineAdmissionStatusEnum];

/**
 * @export
 */
export const TimelineAdmissionCompletionScopeEnum = {
    TimelineStored: 'TIMELINE_STORED',
} as const;
export type TimelineAdmissionCompletionScopeEnum = typeof TimelineAdmissionCompletionScopeEnum[keyof typeof TimelineAdmissionCompletionScopeEnum];

/**
 * @export
 */
export const TimelineAdmissionKindEnum = {
    Timeline: 'TIMELINE',
} as const;
export type TimelineAdmissionKindEnum = typeof TimelineAdmissionKindEnum[keyof typeof TimelineAdmissionKindEnum];

/**
 *
 * @export
 * @interface TimelineAdmissionDetails
 */
export interface TimelineAdmissionDetails {
    /**
     *
     */
    timelineId: string;
    /**
     *
     */
    timelineRef: BlueReference;
    /**
     *
     */
    name: string;
    /**
     *
     */
    actors: Array<string>;
    /**
     *
     */
    createdAt: string;
    /**
     *
     */
    completeBefore: number;
}
/**
 * Owned host timeline metadata created. ref.timelineId is the host timeline identity.
 * @export
 * @interface TimelineCreatedEvent
 */
export interface TimelineCreatedEvent {
    /**
     *
     */
    id: string;
    /**
     *
     */
    sequence: number;
    /**
     *
     */
    created: string;
    /**
     *
     */
    accountId: string;
    /**
     *
     */
    type: TimelineCreatedEventTypeEnum;
    /**
     *
     */
    ref: TimelineCreatedEventAllOfRef;
    /**
     *
     */
    object: NamedResourceEventPayload;
}


/**
 * @export
 */
export const TimelineCreatedEventTypeEnum = {
    TimelineCreated: 'TIMELINE_CREATED',
} as const;
export type TimelineCreatedEventTypeEnum = typeof TimelineCreatedEventTypeEnum[keyof typeof TimelineCreatedEventTypeEnum];

/**
 *
 * @export
 * @interface TimelineCreatedEventAllOfRef
 */
export interface TimelineCreatedEventAllOfRef {
    /**
     *
     */
    timelineId: string;
}
/**
 * @type TimelineEntriesPage
 *
 * @export
 */
export type TimelineEntriesPage = ImportedTimelineEntriesPage | ProjectedTimelineEntriesPage | ProviderRange;
/**
 * Entry identity and original retained entry. The entry is not reconstructed from index metadata or renamed reference fields.
 * @export
 * @interface TimelineEntry
 */
export interface TimelineEntry {
    /**
     *
     */
    blueId: string;
    /**
     * Original retained entry value decoded from its exact transport. Native signed fields are preserved without adding initialDocument; cyclic entries retain the original owning-set proof/body envelope.
     */
    entry: object;
}
/**
 *
 * @export
 * @interface TimelineEntryAdmission
 */
export interface TimelineEntryAdmission {
    /**
     *
     */
    admissionId: string;
    /**
     *
     */
    status: TimelineEntryAdmissionStatusEnum;
    /**
     *
     */
    problem?: AdmissionProblem;
    /**
     *
     */
    createdAt: string | null;
    /**
     *
     */
    completedAt: string | null;
    /**
     *
     */
    completionScope: TimelineEntryAdmissionCompletionScopeEnum;
    /**
     *
     */
    kind: TimelineEntryAdmissionKindEnum;
    /**
     *
     */
    details: EntryAdmissionDetails | null;
}


/**
 * @export
 */
export const TimelineEntryAdmissionStatusEnum = {
    Pending: 'PENDING',
    Succeeded: 'SUCCEEDED',
    Failed: 'FAILED',
} as const;
export type TimelineEntryAdmissionStatusEnum = typeof TimelineEntryAdmissionStatusEnum[keyof typeof TimelineEntryAdmissionStatusEnum];

/**
 * @export
 */
export const TimelineEntryAdmissionCompletionScopeEnum = {
    TimelineEntryStored: 'TIMELINE_ENTRY_STORED',
} as const;
export type TimelineEntryAdmissionCompletionScopeEnum = typeof TimelineEntryAdmissionCompletionScopeEnum[keyof typeof TimelineEntryAdmissionCompletionScopeEnum];

/**
 * @export
 */
export const TimelineEntryAdmissionKindEnum = {
    TimelineEntry: 'TIMELINE_ENTRY',
} as const;
export type TimelineEntryAdmissionKindEnum = typeof TimelineEntryAdmissionKindEnum[keyof typeof TimelineEntryAdmissionKindEnum];

/**
 * New provider entry. ref.timelineId is the native timeline BlueId, unlike TIMELINE_CREATED.
 * @export
 * @interface TimelineEntryCreatedEvent
 */
export interface TimelineEntryCreatedEvent {
    /**
     *
     */
    id: string;
    /**
     *
     */
    sequence: number;
    /**
     *
     */
    created: string;
    /**
     *
     */
    accountId: string;
    /**
     *
     */
    type: TimelineEntryCreatedEventTypeEnum;
    /**
     *
     */
    ref: TimelineCreatedEventAllOfRef;
    /**
     *
     */
    object: BlueIdentityEventPayload;
}


/**
 * @export
 */
export const TimelineEntryCreatedEventTypeEnum = {
    TimelineEntryCreated: 'TIMELINE_ENTRY_CREATED',
} as const;
export type TimelineEntryCreatedEventTypeEnum = typeof TimelineEntryCreatedEventTypeEnum[keyof typeof TimelineEntryCreatedEventTypeEnum];

/**
 * Live listing; inserts or updates between pages may affect membership. Tokens are bound to account/resource/filters/limit.
 * @export
 * @interface TimelinePage
 */
export interface TimelinePage {
    /**
     *
     */
    items: Array<TimelineResource>;
    /**
     *
     */
    nextPageToken: string | null;
    /**
     *
     */
    paginationMode?: string;
    /**
     *
     */
    total?: number;
    /**
     *
     */
    hasMore?: boolean;
}
/**
 * Host-managed owned timeline (Timeline) or provider-backed timeline (ImportedTimeline). Provider-backed timelines include both owned=true and read-only owned=false rows; determine ownership from owned, not from the presence of endpoint or absence of blueId.
 * @export
 * @interface TimelineResource
 */
export interface TimelineResource {
    /**
     *
     */
    timelineId: string;
    /**
     *
     */
    blueId: string;
    /**
     *
     */
    name: string;
    /**
     *
     */
    actors: Array<string>;
    /**
     *
     */
    createdAt: string;
    /**
     *
     */
    lastChangedAt: string;
    /**
     *
     */
    completeBefore: number;
    /**
     *
     */
    endpoint: string;
    /**
     *
     */
    readerKeyId?: string;
    /**
     *
     */
    owned: boolean;
    /**
     *
     */
    status: TimelineResourceStatusEnum;
    /**
     *
     */
    lastFetchedAt?: string;
    /**
     *
     */
    head?: string | null;
    /**
     *
     */
    issue?: ImportedTimelineIssue;
}


/**
 * @export
 */
export const TimelineResourceStatusEnum = {
    Pending: 'PENDING',
    Available: 'AVAILABLE',
    Unavailable: 'UNAVAILABLE',
    RetryRequired: 'RETRY_REQUIRED',
} as const;
export type TimelineResourceStatusEnum = typeof TimelineResourceStatusEnum[keyof typeof TimelineResourceStatusEnum];

/**
 *
 * @export
 * @interface TimerPage
 */
export interface TimerPage {
    /**
     *
     */
    items: Array<PendingTimer>;
    /**
     *
     */
    nextPageToken: string | null;
    /**
     *
     */
    paginationMode: TimerPagePaginationModeEnum;
    /**
     *
     */
    total?: number;
}


/**
 * @export
 */
export const TimerPagePaginationModeEnum = {
    Live: 'LIVE',
} as const;
export type TimerPagePaginationModeEnum = typeof TimerPagePaginationModeEnum[keyof typeof TimerPagePaginationModeEnum];

/**
 * Attached type metadata with native exact content when available, or a note for a primitive whose content is unavailable.
 * @export
 * @interface TypeInspection
 */
export interface TypeInspection {
    /**
     *
     */
    name: string;
    /**
     *
     */
    alias: string;
    /**
     *
     */
    package: string;
    /**
     *
     */
    blueId: string;
    /**
     *
     */
    packageId?: string;
    /**
     *
     */
    content?: NativeExactContent;
    /**
     *
     */
    note?: string;
}
/**
 *
 * @export
 * @interface UnsubscribeAgentEvents
 */
export interface UnsubscribeAgentEvents {
    /**
     *
     */
    name: UnsubscribeAgentEventsNameEnum;
    /**
     *
     */
    arguments: AgentEventArguments;
    /**
     *
     */
    delivery: AgentEventDelivery;
}


/**
 * @export
 */
export const UnsubscribeAgentEventsNameEnum = {
    DocumentUpdated: 'document.updated',
} as const;
export type UnsubscribeAgentEventsNameEnum = typeof UnsubscribeAgentEventsNameEnum[keyof typeof UnsubscribeAgentEventsNameEnum];

/**
 *
 * @export
 * @interface UpdateAccountAgent
 */
export interface UpdateAccountAgent {
    /**
     *
     */
    inclusion?: boolean;
    /**
     *
     */
    expectedRevision: number;
    /**
     *
     */
    name?: string;
    /**
     *
     */
    role?: UpdateAccountAgentRoleEnum;
    /**
     *
     */
    status?: UpdateAccountAgentStatusEnum;
}


/**
 * @export
 */
export const UpdateAccountAgentRoleEnum = {
    ReadSelected: 'READ_SELECTED',
    ReadAll: 'READ_ALL',
} as const;
export type UpdateAccountAgentRoleEnum = typeof UpdateAccountAgentRoleEnum[keyof typeof UpdateAccountAgentRoleEnum];

/**
 * @export
 */
export const UpdateAccountAgentStatusEnum = {
    Active: 'ACTIVE',
    Paused: 'PAUSED',
} as const;
export type UpdateAccountAgentStatusEnum = typeof UpdateAccountAgentStatusEnum[keyof typeof UpdateAccountAgentStatusEnum];

/**
 *
 * @export
 * @interface UpdateAccountProfile
 */
export interface UpdateAccountProfile {
    /**
     * Explicitly convert a Personal account to Business with the supplied business name and optional address. Business cannot convert back to Personal. Omission preserves kind.
     */
    kind?: UpdateAccountProfileKindEnum;
    /**
     * Optional business address. Omit on update to preserve it; an empty string clears it. Unavailable for personal accounts.
     */
    address?: string;
    /**
     *
     */
    displayName: string;
    /**
     *
     */
    expectedRevision: number;
    /**
     * Account website. HTTP(S) only; never fetched by this API. Null clears it on update; omission preserves it.
     */
    website?: string | null;
}


/**
 * @export
 */
export const UpdateAccountProfileKindEnum = {
    Business: 'BUSINESS',
} as const;
export type UpdateAccountProfileKindEnum = typeof UpdateAccountProfileKindEnum[keyof typeof UpdateAccountProfileKindEnum];

/**
 *
 * @export
 * @interface UpdateAdminNotifications
 */
export interface UpdateAdminNotifications {
    /**
     *
     */
    recipients: Array<string>;
    /**
     *
     */
    expectedRevision: number;
}
/**
 *
 * @export
 * @interface UpdateMandateSharingRequest
 */
export interface UpdateMandateSharingRequest {
    /**
     *
     */
    enabled: boolean;
}
/**
 *
 * @export
 * @interface UpdateMembership
 */
export interface UpdateMembership {
    /**
     *
     */
    expectedGeneration: number;
    /**
     *
     */
    expectedRevision: number;
    /**
     *
     */
    role: MembershipRole;
    /**
     *
     */
    status: UpdateMembershipStatusEnum;
}


/**
 * @export
 */
export const UpdateMembershipStatusEnum = {
    Active: 'ACTIVE',
    Removed: 'REMOVED',
} as const;
export type UpdateMembershipStatusEnum = typeof UpdateMembershipStatusEnum[keyof typeof UpdateMembershipStatusEnum];

/**
 * @type UpdateMyProfile
 *
 * @export
 */
export type UpdateMyProfile = UpdateMyProfileOneOf | UpdateMyProfileOneOf1;
/**
 *
 * @export
 * @interface UpdateMyProfileOneOf
 */
export interface UpdateMyProfileOneOf {
    /**
     *
     */
    expectedRevision: number;
    /**
     *
     */
    firstName: string | null;
    /**
     *
     */
    lastName?: string | null;
}
/**
 *
 * @export
 * @interface UpdateMyProfileOneOf1
 */
export interface UpdateMyProfileOneOf1 {
    /**
     *
     */
    expectedRevision: number;
    /**
     *
     */
    firstName?: string | null;
    /**
     *
     */
    lastName: string | null;
}
/**
 *
 * @export
 * @interface UpdateTimelineAccess
 */
export interface UpdateTimelineAccess {
    /**
     * Optional complete advertised execution selection for the owner's access follower. Omission preserves the account default. Existing followers cannot change selection. The provider grant commits before a new follower is imported; a later import failure does not roll back the grant. Retry the identical request and key to recover that grant without a duplicate update. A conflicting already-bound follower is not replaced by retrying.
     */
    executionSelection?: RuntimeIdentities;
    /**
     *
     */
    accessId?: string;
    /**
     *
     */
    allowedKeys: Array<string>;
}
/**
 *
 * @export
 * @interface UpdateUserPreferences
 */
export interface UpdateUserPreferences {
    /**
     *
     */
    defaultAccountId: string;
    /**
     *
     */
    expectedMembershipGeneration: number;
    /**
     *
     */
    expectedRevision: number;
}
/**
 *
 * @export
 * @interface UpdatedAccountProfile
 */
export interface UpdatedAccountProfile {
    /**
     *
     */
    kind: AccountKind;
    /**
     *
     */
    accountId: string;
    /**
     *
     */
    profile: AccountProfile;
    /**
     *
     */
    revision: number;
}


/**
 *
 * @export
 * @interface UserPreferences
 */
export interface UserPreferences {
    /**
     * Current eligible default, or null when none is available in this discovery snapshot.
     */
    defaultAccountId: string | null;
    /**
     *
     */
    revision: number;
}
/**
 *
 * @export
 * @interface UserProfile
 */
export interface UserProfile {
    /**
     *
     */
    userId: string;
    /**
     *
     */
    email: string;
    /**
     *
     */
    firstName: string | null;
    /**
     *
     */
    lastName: string | null;
    /**
     * Versioned avatar of this User, shared across their accounts. Requires authenticated Core image access.
     */
    imageUrl: string | null;
    /**
     *
     */
    profileRevision: number;
}
/**
 *
 * @export
 * @interface UserSession
 */
export interface UserSession {
    /**
     *
     */
    impersonation?: ImpersonationMetadata;
    /**
     *
     */
    userId: string;
    /**
     *
     */
    email: string;
    /**
     *
     */
    sessionContext: string;
    /**
     *
     */
    sessionExpiresAt: number;
    /**
     *
     */
    accounts: Array<AccountSummary>;
    /**
     *
     */
    preferences: UserPreferences;
    /**
     *
     */
    accountModel: UserSessionAccountModelEnum;
    /**
     *
     */
    firstName: string | null;
    /**
     *
     */
    lastName: string | null;
    /**
     * Versioned avatar of this User, shared across their accounts. Requires authenticated Core image access.
     */
    imageUrl: string | null;
    /**
     *
     */
    profileRevision: number;
}


/**
 * @export
 */
export const UserSessionAccountModelEnum = {
    MembershipsV1: 'MEMBERSHIPS_V1',
} as const;
export type UserSessionAccountModelEnum = typeof UserSessionAccountModelEnum[keyof typeof UserSessionAccountModelEnum];

/**
 * Public subscription fields. New hooks start at the current event sequence with no backfill. Deleted configurations return 404; retained delivery reads remain available.
 * @export
 * @interface Webhook
 */
export interface Webhook {
    /**
     *
     */
    id: string;
    /**
     *
     */
    name?: string;
    /**
     *
     */
    url: string;
    /**
     *
     */
    status: WebhookStatusEnum;
    /**
     *
     */
    events: Array<WebhookSelector>;
    /**
     *
     */
    filters: WebhookFilters;
    /**
     *
     */
    callbackProtocol: WebhookCallbackProtocolEnum;
    /**
     *
     */
    createdAt?: string | null;
    /**
     *
     */
    updatedAt?: string | null;
}


/**
 * @export
 */
export const WebhookStatusEnum = {
    Active: 'ACTIVE',
    Inactive: 'INACTIVE',
} as const;
export type WebhookStatusEnum = typeof WebhookStatusEnum[keyof typeof WebhookStatusEnum];

/**
 * @export
 */
export const WebhookCallbackProtocolEnum = {
    NativeV1: 'native-v1',
} as const;
export type WebhookCallbackProtocolEnum = typeof WebhookCallbackProtocolEnum[keyof typeof WebhookCallbackProtocolEnum];

/**
 *
 * @export
 * @interface WebhookCreate
 */
export interface WebhookCreate {
    /**
     *
     */
    name?: string;
    /**
     * Public HTTPS on port 443; redirects and private destinations are rejected.
     */
    url?: string;
    /**
     *
     */
    status?: WebhookCreateStatusEnum;
    /**
     *
     */
    events?: Array<WebhookSelector>;
    /**
     *
     */
    filters?: WebhookFilters;
}


/**
 * @export
 */
export const WebhookCreateStatusEnum = {
    Active: 'ACTIVE',
    Inactive: 'INACTIVE',
} as const;
export type WebhookCreateStatusEnum = typeof WebhookCreateStatusEnum[keyof typeof WebhookCreateStatusEnum];

/**
 *
 * @export
 * @interface WebhookFilters
 */
export interface WebhookFilters {
    /**
     *
     */
    sessionIds?: Array<string>;
}
/**
 *
 * @export
 * @interface WebhookHealth
 */
export interface WebhookHealth {
    /**
     *
     */
    webhookId: string;
    /**
     *
     */
    deliveriesByStatus: { [key: string]: number; };
    /**
     *
     */
    queued: number;
    /**
     *
     */
    attempts: number;
    /**
     *
     */
    measurementScope: WebhookHealthMeasurementScopeEnum;
}


/**
 * @export
 */
export const WebhookHealthMeasurementScopeEnum = {
    RetainedDeliveries: 'RETAINED_DELIVERIES',
} as const;
export type WebhookHealthMeasurementScopeEnum = typeof WebhookHealthMeasurementScopeEnum[keyof typeof WebhookHealthMeasurementScopeEnum];

/**
 * Live listing; inserts or updates between pages may affect membership. Tokens are bound to account/resource/filters/limit.
 * @export
 * @interface WebhookPage
 */
export interface WebhookPage {
    /**
     *
     */
    items: Array<Webhook>;
    /**
     *
     */
    nextPageToken: string | null;
    /**
     *
     */
    paginationMode?: string;
    /**
     *
     */
    total?: number;
    /**
     *
     */
    hasMore?: boolean;
}
/**
 *
 * @export
 * @interface WebhookSelector
 */
export interface WebhookSelector {
    /**
     *
     */
    type: EventCategory;
}


/**
 * POST partial update: omitted fields preserve state; filters:{} or filters:{sessionIds:[]} reset session filtering; filters:[] is invalid. Empty events selects all categories. Session filters compare literal event.ref.sessionId, without causal graph expansion.
 * @export
 * @interface WebhookWrite
 */
export interface WebhookWrite {
    /**
     *
     */
    name?: string;
    /**
     * Public HTTPS on port 443; redirects and private destinations are rejected.
     */
    url?: string;
    /**
     *
     */
    status?: WebhookWriteStatusEnum;
    /**
     *
     */
    events?: Array<WebhookSelector>;
    /**
     *
     */
    filters?: WebhookFilters;
}


/**
 * @export
 */
export const WebhookWriteStatusEnum = {
    Active: 'ACTIVE',
    Inactive: 'INACTIVE',
} as const;
export type WebhookWriteStatusEnum = typeof WebhookWriteStatusEnum[keyof typeof WebhookWriteStatusEnum];
