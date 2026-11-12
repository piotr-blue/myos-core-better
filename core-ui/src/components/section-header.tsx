import { cn } from "../utils";
import type { ReactNode } from "react";

type SectionHeaderProps = {
  title: ReactNode;
  subtitle?: string;
  actions?: React.ReactNode;
  className?: string;
};

export function SectionHeader({
  title,
  subtitle,
  actions,
  className,
}: SectionHeaderProps) {
  return (
    <div
      className={cn(
        "flex flex-wrap items-center justify-between gap-3",
        className,
      )}
    >
      <div className="flex min-w-0 flex-col gap-1">
        <h2 className="text-lg leading-6 font-semibold">{title}</h2>
        {subtitle ? (
          <p className="text-greys-secondary text-sm">{subtitle}</p>
        ) : null}
      </div>
      {actions ? (
        <div className="flex flex-wrap items-center gap-2">{actions}</div>
      ) : null}
    </div>
  );
}
