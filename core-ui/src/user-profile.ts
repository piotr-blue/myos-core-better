import type { AccountApi, UserProfile, UserSession } from "@blue-labs/myos-sdk";
import { accountApi, accountCommand, accountResult } from "./account-api";
import { captureContext, contextIdentity, userContext } from "./account-context";

function profileSessionGuard(user: UserSession) {
  const original = captureContext();
  const assertCurrent = () => {
    if (!original || original.userId !== user.userId ||
        contextIdentity(original) !== contextIdentity(captureContext()))
      throw new DOMException("Your session changed. Reopen your profile.", "AbortError");
  };
  assertCurrent();
  return assertCurrent;
}

/** Self-profile requests use the User guard, even while an Account is selected. */
export async function userProfileCommand(
  user: UserSession,
  action: string,
  input: unknown,
  invoke: (api: AccountApi, key: string) => Promise<UserProfile>,
) {
  const assertCurrent = profileSessionGuard(user);
  const result = await accountCommand(userContext(user), action, input, (api, key) => {
    assertCurrent();
    return invoke(api, key);
  });
  assertCurrent();
  if (result.userId !== user.userId) throw new Error("Core returned a different User profile.");
  return result;
}

export async function reloadUserProfile(user: UserSession) {
  const assertCurrent = profileSessionGuard(user);
  const result = await accountResult(accountApi(userContext(user)).getMe());
  assertCurrent();
  if (result.userId !== user.userId || result.sessionContext !== user.sessionContext)
    throw new DOMException("Your session changed. Reopen your profile.", "AbortError");
  return result;
}

export function avatarFileError(file: File) {
  if (file.size === 0 || file.size > 5 * 1024 * 1024) return "Choose a nonempty image no larger than 5 MiB.";
  // Browser MIME hints can be absent or generic; Core validates the actual bytes.
  if (!["", "application/octet-stream", "image/jpeg", "image/png"].includes(file.type))
    return "Choose a JPEG or PNG image.";
  return "";
}

export async function uploadUserAvatar(user: UserSession, file: File, expectedRevision: number) {
  const error = avatarFileError(file);
  if (error) throw new Error(error);
  const assertCurrent = profileSessionGuard(user);
  // File contents are part of the intent: two images with identical names and
  // lengths must never reuse an idempotency key after an uncertain response.
  const bytes = await file.arrayBuffer();
  const sha256 = Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256", bytes)))
    .map(value => value.toString(16).padStart(2, "0")).join("");
  assertCurrent();
  return userProfileCommand(user, "user-avatar-upload", { expectedRevision, sha256, contentType: file.type },
    (api, key) => api.putMyAvatar({ file, expectedRevision, idempotencyKey: key }));
}
