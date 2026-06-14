"use client";

import type { DriftDiff } from "@/lib/mock-tool-detail";
import { useT } from "@/lib/i18n";

/**
 * Current↔pinned unified diff for a DRIFTED asset.
 * Faithful to design-system/components/diff-view.html.
 */
const LINE: Record<DriftDiff["lines"][number]["kind"], string> = {
  ctx: "text-muted",
  add: "bg-approved/[0.10] [&>.t]:text-[#9BF0AD]",
  del: "bg-blocked/[0.10] [&>.t]:text-[#FFB4AF]",
};

export function DiffView({
  name,
  diff,
}: {
  name: string;
  diff: DriftDiff;
}) {
  const { t } = useT();
  return (
    <div className="overflow-hidden rounded-[11px] border border-border">
      <div className="flex items-center gap-3 border-b border-border bg-drift/[0.08] px-4 py-[13px]">
        <span className="inline-flex items-center gap-[7px] text-[12px] font-bold tracking-[0.04em] text-drift">
          <span className="h-2 w-2 rounded-full bg-drift drift-pulse" />
          {t("drift.detected")} · {name}
        </span>
        <span className="ml-auto text-right font-mono text-[11px] leading-[1.5] text-dim">
          {t("drift.pinned")} <b className="text-text">{diff.pinnedSha}</b> → {t("drift.observed")}{" "}
          <b className="text-text">{diff.observedSha}</b>
          <br />
          {diff.summary}
        </span>
      </div>
      <div className="bg-surface py-1.5 font-mono text-[12.5px] leading-[1.7]">
        {diff.lines.map((ln, i) => (
          <div key={i} className={`flex px-3.5 ${LINE[ln.kind]}`}>
            <span className="w-[22px] flex-none select-none text-center text-dim">
              {ln.gutter}
            </span>
            <span className="t whitespace-pre-wrap">{ln.text}</span>
          </div>
        ))}
      </div>
    </div>
  );
}
