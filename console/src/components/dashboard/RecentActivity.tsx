"use client";

import type { ActivityKind, RecentActivity } from "@/lib/mock-dashboard";
import { useT } from "@/lib/i18n";

/**
 * 최근 활동 — recent audit/activity feed.
 * Each event gets a mono chip colored by kind, following audit-log-row.html.
 */
const KIND: Record<
  ActivityKind,
  { label: string; chip: string }
> = {
  APPROVE: { label: "APPROVED", chip: "bg-approved/[0.14] text-approved" },
  BLOCK: { label: "BLOCKED", chip: "bg-blocked/[0.14] text-blocked" },
  DRIFT: { label: "DRIFT", chip: "bg-drift/[0.14] text-drift" },
  SCAN: { label: "SCAN", chip: "bg-primary/[0.14] text-primary" },
  REGISTER: { label: "REGISTER", chip: "bg-surface-2 text-muted" },
};

function formatTime(iso: string): string {
  // Stable, locale-independent formatting (avoids SSR/CSR hydration drift).
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(
    d.getUTCDate(),
  )} ${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}Z`;
}

export function RecentActivityList({
  activity,
}: {
  activity: RecentActivity[];
}) {
  const { t } = useT();
  return (
    <div className="overflow-hidden rounded-xl border border-border bg-surface">
      <div className="border-b border-border bg-sidebar px-4 py-[11px] text-[11px] font-semibold uppercase tracking-[0.1em] text-muted">
        {t("dash.recentActivity")}
      </div>
      <ul>
        {activity.map((a) => {
          const k = KIND[a.kind];
          return (
            <li
              key={a.id}
              className="grid grid-cols-[1fr_auto] items-start gap-3 border-b border-border px-4 py-[11px] last:border-b-0"
            >
              <div className="min-w-0">
                <div className="flex items-center gap-2 text-[13px] font-semibold">
                  <span
                    className={`rounded font-mono text-[10px] font-semibold leading-none ${k.chip} px-1.5 py-1`}
                  >
                    {k.label}
                  </span>
                  <span className="truncate">{a.asset}</span>
                </div>
                <div className="mt-1 text-[12px] text-muted">{t("dash.by")} {a.actor}</div>
              </div>
              <time
                className="whitespace-nowrap font-mono text-[11px] text-dim"
                dateTime={a.at}
              >
                {formatTime(a.at)}
              </time>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
