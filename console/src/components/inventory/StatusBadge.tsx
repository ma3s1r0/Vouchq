"use client";

import type { AssetStatus } from "@/lib/mock-inventory";
import { useT } from "@/lib/i18n";

/**
 * Lifecycle status badge. Faithful to design-system/components/status-badge.html.
 * DRIFTED pulses (drift-pulse keyframe in globals.css) to draw attention.
 */
const STYLE: Record<
  AssetStatus,
  { wrap: string; dot: string; pulse?: boolean }
> = {
  APPROVED: {
    wrap: "bg-approved/[0.13] text-approved border-approved/30",
    dot: "bg-approved",
  },
  PENDING: {
    wrap: "bg-muted/[0.12] text-muted border-border",
    dot: "bg-dim",
  },
  DRIFTED: {
    wrap: "bg-drift/[0.14] text-drift border-drift/[0.32]",
    dot: "bg-drift",
    pulse: true,
  },
  BLOCKED: {
    wrap: "bg-blocked/[0.13] text-blocked border-blocked/[0.32]",
    dot: "bg-blocked",
  },
};

export function StatusBadge({ status }: { status: AssetStatus }) {
  const { t } = useT();
  const s = STYLE[status];
  return (
    <span
      className={`inline-flex items-center gap-[7px] rounded-full border px-[9px] py-[3px] text-[11px] font-semibold ${s.wrap}`}
    >
      <span
        className={`h-1.5 w-1.5 rounded-full ${s.dot} ${
          s.pulse ? "drift-pulse" : ""
        }`}
      />
      {t(`status.${status}`)}
    </span>
  );
}
