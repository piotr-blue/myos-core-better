import type { ReadState } from "./transport";

export function AccountPreparationNotice({ state }: { state?: ReadState }) {
  return state === "initial" ? <p role="status" className="account-preparation-notice">Trwa przygotowanie widoku konta</p> : null;
}
