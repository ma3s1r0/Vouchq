"use client";

import Link from "next/link";
import { useT } from "@/lib/i18n";

/**
 * Dashboard stat card. Faithful to design-system/components/stat-card.html:
 * 3px left accent rail, uppercase letter-spaced label, large mono value,
 * and a small delta/detail line that can carry its own accent.
 */
export type StatAccent = "primary" | "warn" | "drift" | "blocked" | "approved";

const ACCENT_VAR: Record<StatAccent, string> = {
  primary: "var(--color-primary)",
  warn: "var(--color-warn)",
  drift: "var(--color-drift)",
  blocked: "var(--color-blocked)",
  approved: "var(--color-approved)",
};

const DELTA_CLASS: Record<StatAccent | "dim", string> = {
  dim: "text-dim",
  primary: "text-primary",
  warn: "text-warn",
  drift: "text-drift",
  blocked: "text-blocked",
  approved: "text-approved",
};

export function StatCard({
  label,
  labelKey,
  value,
  delta,
  accent = "primary",
  deltaAccent = "dim",
  href,
}: {
  label?: string;
  labelKey?: string;
  value: string | number;
  delta?: React.ReactNode;
  accent?: StatAccent;
  deltaAccent?: StatAccent | "dim";
  /** When set, the card is a link to a drill-down view. */
  href?: string;
}) {
  const { t } = useT();
  const inner = (
    <>
      <span
        className="absolute inset-y-0 left-0 w-[3px]"
        style={{ background: ACCENT_VAR[accent] }}
        aria-hidden
      />
      <div className="text-[11px] font-semibold uppercase tracking-[0.12em] text-muted">
        {labelKey ? t(labelKey) : label}
      </div>
      <div className="my-2 font-mono text-[34px] font-semibold tracking-[-0.02em]">
        {value}
      </div>
      {delta != null && (
        <div className={`text-[12px] ${DELTA_CLASS[deltaAccent]}`}>{delta}</div>
      )}
    </>
  );
  const cls =
    "relative block overflow-hidden rounded-xl border border-border bg-surface px-[18px] py-4";
  return href ? (
    <Link href={href} className={`${cls} transition-colors hover:border-border-strong`}>
      {inner}
    </Link>
  ) : (
    <div className={cls}>{inner}</div>
  );
}
