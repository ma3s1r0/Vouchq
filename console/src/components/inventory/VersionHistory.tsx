"use client";

import type { VersionEntry } from "@/lib/mock-tool-detail";
import { useT } from "@/lib/i18n";

/**
 * tool_version timeline with PINNED / DRIFTED markers.
 * Faithful to design-system/components/version-history.html.
 */
const NODE: Record<NonNullable<VersionEntry["marker"]>, string> = {
  PINNED: "border-approved shadow-[0_0_0_3px_rgba(63,185,80,0.16)]",
  DRIFTED: "border-drift shadow-[0_0_0_3px_rgba(219,109,40,0.18)]",
};
const CHIP: Record<NonNullable<VersionEntry["marker"]>, string> = {
  PINNED: "bg-approved/[0.14] text-approved",
  DRIFTED: "bg-drift/[0.14] text-drift",
};

function fmt(at: string): { date: string; time: string } {
  // Display the ISO timestamp split into date / time (Z-suffixed) lines.
  const [date, rest = ""] = at.split("T");
  return { date, time: rest.replace(/\.\d+/, "") || "—" };
}

export function VersionHistory({
  name,
  history,
}: {
  name: string;
  history: VersionEntry[];
}) {
  const { t } = useT();
  return (
    <div className="overflow-hidden rounded-[11px] border border-border bg-surface">
      <div className="flex items-center gap-2 border-b border-border bg-sidebar px-4 py-[11px] text-[11px] font-semibold uppercase tracking-[0.1em] text-muted">
        {t("versionHistory.title")}
        <span className="ml-auto font-mono normal-case tracking-normal text-text">
          {name}
        </span>
      </div>
      {history.map((v) => {
        const tv = fmt(v.at);
        return (
          <div
            key={v.id}
            className="grid grid-cols-[118px_18px_1fr] gap-3 border-b border-border px-4 py-[13px] last:border-b-0"
          >
            <div className="font-mono text-[11px] leading-[1.5] text-dim">
              {tv.date}
              <br />
              {tv.time}
            </div>
            <div className="relative flex justify-center">
              <span className="absolute -bottom-[13px] -top-[13px] w-px bg-border" />
              <span
                className={`z-[1] mt-0.5 h-[11px] w-[11px] rounded-full border-[1.5px] bg-surface ${
                  v.marker ? NODE[v.marker] : "border-dim"
                }`}
              />
            </div>
            <div>
              <div className="font-mono text-[13px] font-medium text-text">
                sha256 {v.shaShort}
                {v.marker && (
                  <span
                    className={`ml-2 rounded font-mono text-[10px] font-bold tracking-[0.05em] px-[7px] py-px align-[1px] ${CHIP[v.marker]}`}
                  >
                    {t(v.marker === "PINNED" ? "version.pinned" : "version.drifted")}
                  </span>
                )}
              </div>
              <div className="mt-1 text-[12px] text-muted">{v.note}</div>
            </div>
          </div>
        );
      })}
    </div>
  );
}
