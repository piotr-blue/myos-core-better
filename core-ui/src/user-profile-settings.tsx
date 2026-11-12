import { useEffect, useRef, useState } from "react";
import type { UserProfile, UserSession } from "@blue-labs/myos-sdk";
import { ImagePicker } from "@blue-labs/account-ui";
import { avatarFileError, reloadUserProfile, uploadUserAvatar, userProfileCommand } from "./user-profile";

export function UserProfileSettings({ user, reload }: { user: UserSession; reload: () => Promise<void> }) {
  const [profile, setProfile] = useState<UserProfile>(user);
  const [firstName, setFirstName] = useState(user.firstName ?? "");
  const [lastName, setLastName] = useState(user.lastName ?? "");
  const [file, setFile] = useState<File>();
  const [busy, setBusy] = useState("");
  const [error, setError] = useState("");
  const [conflict, setConflict] = useState(false);
  const [saved, setSaved] = useState("");
  const alive = useRef(false);
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  useEffect(() => {
    if (user.profileRevision <= profile.profileRevision) return;
    // Background refresh may update another tab's changes. Keep a local draft
    // until the User explicitly reloads; never submit it with a newer revision.
    setConflict(true);
    setError("Your profile changed elsewhere. Reload the latest profile before saving.");
  }, [user.profileRevision, profile.profileRevision]);

  const perform = async (action: string, request: () => Promise<UserProfile>) => {
    if (busy) return;
    setBusy(action); setError(""); setSaved("");
    try {
      const next = await request();
      if (!alive.current) return;
      setProfile(next); setConflict(false);
      if (action === "profile" || action === "reload") {
        setFirstName(next.firstName ?? ""); setLastName(next.lastName ?? "");
      }
      if (action === "upload" || action === "remove" || action === "reload") {
        setFile(undefined);
      }
      setSaved(action === "reload" ? "Latest profile loaded." : "Changes saved.");
      await reload();
    } catch (failure: any) {
      if (!alive.current || failure.name === "AbortError") return;
      setError(failure.message || "Your profile could not be saved. Try again.");
      if (failure.status === 409) setConflict(true);
    } finally { if (alive.current) setBusy(""); }
  };
  const disabled = !!busy || conflict;
  return <><section className="panel panel-pad account-management user-profile-settings">
    <h2>My profile</h2>
    <p>Your name and avatar are shared across all your accounts.</p>
    <form onSubmit={event => {
      event.preventDefault();
      if (disabled) return;
      const input = { firstName: firstName.trim() || null, lastName: lastName.trim() || null, expectedRevision: profile.profileRevision };
      void perform("profile", () => userProfileCommand(user, "user-profile", input,
        (api, key) => api.updateMyProfile({ updateMyProfile: input, idempotencyKey: key })));
    }}>
      <label className="field">Email
        {/* Verified email refreshes with the User session, independently of profileRevision. */}
        <input name="email" type="email" value={user.email} readOnly autoComplete="email" />
      </label>
      <label className="field">First name
        <input name="firstName" maxLength={160} autoComplete="given-name" value={firstName} disabled={!!busy} onChange={event => setFirstName(event.target.value)} />
      </label>
      <label className="field">Last name
        <input name="lastName" maxLength={160} autoComplete="family-name" value={lastName} disabled={!!busy} onChange={event => setLastName(event.target.value)} />
      </label>
      <button className="primary" disabled={disabled || (firstName.trim() === (profile.firstName ?? "") && lastName.trim() === (profile.lastName ?? ""))}>
        {busy === "profile" ? "Saving…" : "Save my profile"}
      </button>
    </form>
    </section>
    <section className="panel panel-pad account-management user-profile-settings image-settings-card" aria-label="Profile photo">
      <h2>Profile photo</h2>
      <p className="image-settings-description">Your photo is shared across all your accounts.</p>
      <ImagePicker file={file} imageUrl={profile.imageUrl} avatar alt="Your avatar" label={profile.imageUrl ? "Replace avatar" : "Add an avatar"} disabled={disabled} choose={selected => {
        const problem = selected ? avatarFileError(selected) : "";
        setError(problem); setSaved(""); setFile(problem ? undefined : selected ?? undefined);
      }}>
        <button type="button" className="primary" disabled={disabled || !file} onClick={() => file && void perform("upload", () => uploadUserAvatar(user, file, profile.profileRevision))}>
          {busy === "upload" ? "Uploading…" : "Save avatar"}
        </button>
        {profile.imageUrl && <button type="button" className="secondary" disabled={disabled} onClick={() => {
          const input = { expectedRevision: profile.profileRevision };
          void perform("remove", () => userProfileCommand(user, "user-avatar-remove", input,
            (api, key) => api.deleteMyAvatar({ deleteMyAvatar: input, idempotencyKey: key })));
        }}>{busy === "remove" ? "Removing…" : "Remove avatar"}</button>}
      </ImagePicker>
    </section>
    {error && <p className="error" role="alert">{error}</p>}
    {conflict && <>
      <p>Reloading replaces the unsaved changes in this form.</p>
      <button type="button" className="secondary" disabled={!!busy} onClick={() => void perform("reload", () => reloadUserProfile(user))}>Reload latest profile</button>
    </>}
    {saved && <p role="status">{saved}</p>}
  </>;
}
