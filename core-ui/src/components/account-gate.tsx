import { impersonation } from "../impersonation-state";
import type { ReactNode } from "react";
import { DevLayout } from "./dev-layout";
import { Dialog } from "./dialog";

/** Only application chrome is mounted until the User has selected an account. */
export function AccountGate({
  title,
  children,
  headerActions,
}: {
  title: string;
  children: ReactNode;
  headerActions?: ReactNode;
}) {
  // Workspace onboarding must not make the impersonation controls inert.
  if (impersonation())
    return (
      <main className="admin-page account-management">
        <h1>{title}</h1>
        {children}
      </main>
    );
  return (
    <>
      <div inert aria-hidden="true">
        <DevLayout page="documents" navigate={() => {}}>
          <div className="page-heading">
            <h1 className="page-title">Documents</h1>
          </div>
          <div className="panel account-gate-placeholder" />
        </DevLayout>
      </div>
      <Dialog
        title={title}
        headerActions={headerActions}
        className="account-management account-onboarding"
      >
        {children}
      </Dialog>
    </>
  );
}
