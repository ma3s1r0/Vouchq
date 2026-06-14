"use client";

import type { RiskLevel } from "@/lib/mock-dashboard";
import { useT } from "@/lib/i18n";

/**
 * 위험 등급 분포 — stacked proportion bar + legend.
 * Colors follow the severity-tag / color tokens: crit, warn, info(primary), clean(approved).
 */
const ORDER: { key: RiskLevel; label: string; varName: string }[] = [
  { key: "CRITICAL", label: "Critical", varName: "var(--color-blocked)" },
  { key: "WARN", label: "Warn", varName: "var(--color-warn)" },
  { key: "INFO", label: "Info", varName: "var(--color-primary)" },
  { key: "CLEAN", label: "Clean", varName: "var(--color-approved)" },
];

export function RiskDistribution({
  distribution,
}: {
  distribution: Record<RiskLevel, number>;
}) {
  const { t } = useT();
  const total =
    Object.values(distribution).reduce((a, b) => a + b, 0) || 1;

  return (
    <div className="relative overflow-hidden rounded-xl border border-border bg-surface px-[18px] py-4">
      <div className="text-[11px] font-semibold uppercase tracking-[0.12em] text-muted">
        {t("dash.riskDistribution")}
      </div>

      <div className="mt-4 flex h-2.5 w-full overflow-hidden rounded-full border border-border bg-bg">
        {ORDER.map(({ key, varName }) => {
          const pct = (distribution[key] / total) * 100;
          if (pct === 0) return null;
          return (
            <div
              key={key}
              style={{ width: `${pct}%`, background: varName }}
              title={`${key} ${distribution[key]}`}
            />
          );
        })}
      </div>

      <ul className="mt-4 grid grid-cols-2 gap-x-6 gap-y-2.5">
        {ORDER.map(({ key, label, varName }) => (
          <li
            key={key}
            className="flex items-center gap-2 text-[12px] text-muted"
          >
            <span
              className="h-2 w-2 flex-none rounded-full"
              style={{ background: varName }}
            />
            {label}
            <span className="ml-auto font-mono text-text">
              {distribution[key]}
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}
