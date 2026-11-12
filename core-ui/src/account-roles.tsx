import type { MembershipRole } from "@blue-labs/myos-sdk";
export const roleLabels: Record<MembershipRole, string> = {
  OWNER: "Owner",
  ADMIN: "Admin",
  WRITE: "Write",
  READ_ALL: "Read all",
  READ_SELECTED: "Read selected",
};
export function RoleOptions({ owner = false, ownerUnavailable, administratorsOnly = false }: { owner?: boolean; ownerUnavailable?: string; administratorsOnly?: boolean }) {
  return (
    <>
      {Object.entries(roleLabels).filter(([value]) => value !== "OWNER" || owner).map(([value, label]) => (
        <option key={value} value={value} disabled={(value === "OWNER" && ownerUnavailable !== undefined) || (administratorsOnly && !["OWNER", "ADMIN"].includes(value))}>
          {value === "OWNER" && ownerUnavailable ? `${label} (${ownerUnavailable})` : label}
        </option>
      ))}
    </>
  );
}
