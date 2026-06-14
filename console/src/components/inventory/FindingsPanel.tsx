"use client";

import { useState } from "react";
import type { Finding } from "@/lib/mock-tool-detail";
import { api } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useToast } from "@/lib/feedback";
import { riskColorVar, riskFillVar } from "@/lib/risk";
import { SeverityTag } from "./SeverityTag";
import { useT } from "@/lib/i18n";

/**
 * Scan findings panel — MA3-94 update:
 * - Headline shows effectiveRisk; when rawRisk differs, the raw is shown struck-through.
 * - Suppressed findings are visually de-emphasized with a "Suppressed" chip.
 * - Admins get a per-finding "Suppress" button (fingerprint-scoped) and a
 *   "Suppress rule" button (rule+tool-scoped, whole-rule for this tool).
 */

function summarize(findings: Finding[], t: (k: string) => string): string {
  const active = findings.filter((f) => !f.suppressed);
  const suppressed = findings.filter((f) => f.suppressed);
  const c = active.filter((f) => f.severity === "CRITICAL").length;
  const w = active.filter((f) => f.severity === "WARN").length;
  const i = active.filter((f) => f.severity === "INFO").length;
  const parts: string[] = [];
  if (c) parts.push(`${c} ${t("findings.critical")}`);
  if (w) parts.push(`${w} ${t("findings.warn")}`);
  if (i) parts.push(`${i} ${t("findings.info")}`);
  if (suppressed.length) parts.push(`${suppressed.length} ${t("findings.suppressed")}`);
  return parts.join(" · ") || t("findings.none.summary");
}

export function FindingsPanel({
  effectiveRisk,
  rawRisk,
  findings: initialFindings,
  toolId,
  scanTarget,
  scannedSha,
  scannedAt,
}: {
  effectiveRisk: number;
  rawRisk: number;
  findings: Finding[];
  toolId: string;
  scanTarget: string;
  scannedSha: string;
  scannedAt: string;
}) {
  const { me } = useAuth();
  const { t } = useT();
  const toast = useToast();
  const isAdmin = me?.role === "ADMIN";

  // Local copy so we can optimistically mark a finding as suppressed after
  // a successful POST without needing a full page reload.
  const [findings, setFindings] = useState<Finding[]>(initialFindings);
  const [suppressing, setSuppressing] = useState<string | null>(null);

  const suppressedCount = findings.filter((f) => f.suppressed).length;
  const risksDiffer = rawRisk !== effectiveRisk;

  const handleSuppress = async (
    f: Finding,
    scope: "fingerprint" | "rule",
  ) => {
    const key = `${f.fingerprint}:${scope}`;
    setSuppressing(key);
    try {
      await api.createSuppression({
        ruleId: f.ruleId,
        toolId,
        fingerprint: scope === "fingerprint" ? f.fingerprint : null,
      });
      // Optimistically mark affected findings as suppressed in local state.
      setFindings((prev) =>
        prev.map((ff) => {
          if (scope === "fingerprint" && ff.fingerprint === f.fingerprint)
            return { ...ff, suppressed: true };
          if (scope === "rule" && ff.ruleId === f.ruleId)
            return { ...ff, suppressed: true };
          return ff;
        }),
      );
    } catch (err) {
      // Suppression did NOT persist — say so, so an admin never believes a
      // CRITICAL finding is suppressed when it isn't (MA3-126).
      const msg = err instanceof Error && err.message ? err.message : t("findings.suppress.error");
      toast("error", msg);
    } finally {
      setSuppressing(null);
    }
  };

  return (
    <div className="overflow-hidden rounded-[11px] border border-border bg-surface">
      <div className="flex items-center gap-3.5 border-b border-border bg-sidebar px-4 py-3.5">
        {/* Effective risk headline */}
        <div className="flex min-w-[52px] flex-col items-start leading-none">
          <div
            className="font-mono text-[26px] font-semibold"
            style={{ color: riskColorVar(effectiveRisk) }}
          >
            {effectiveRisk}
          </div>
          {risksDiffer && (
            <div className="font-mono text-[11px] text-dim line-through">
              {t("findings.raw")} {rawRisk}
            </div>
          )}
        </div>

        <div className="flex-1">
          <div className="mb-[7px] text-[11px] tracking-[0.04em] text-muted">
            {risksDiffer ? t("findings.riskScoreEffective") : t("findings.riskScore")} ·{" "}
            {findings.length} {t("findings.findings")} ({summarize(findings, t)})
            {suppressedCount > 0 && (
              <span className="ml-2 rounded-full bg-muted/[0.12] px-2 py-0.5 text-[10px] font-semibold text-dim">
                {suppressedCount} {t("findings.suppressed")}
              </span>
            )}
          </div>
          <div className="h-1.5 overflow-hidden rounded-full border border-border bg-bg">
            <div
              className="h-full"
              style={{
                width: `${effectiveRisk}%`,
                background: riskFillVar(effectiveRisk),
              }}
            />
          </div>
        </div>

        <div className="ml-auto text-right font-mono text-[11px] leading-[1.5] text-dim">
          {t("findings.scan")} <b className="text-text">{scanTarget}</b>
          <br />
          {scannedSha} · {scannedAt}
        </div>
      </div>

      {findings.length === 0 ? (
        <div className="px-4 py-8 text-center text-[13px] text-dim">
          {t("findings.none")}
        </div>
      ) : (
        findings.map((f) => {
          const isSuppressed = f.suppressed;
          const fpKey = `${f.fingerprint}:fingerprint`;
          const ruleKey = `${f.fingerprint}:rule`;
          return (
            <div
              key={f.id}
              className={[
                "grid grid-cols-[96px_1fr] gap-3 border-b border-border px-4 py-3 last:border-b-0",
                isSuppressed
                  ? "bg-bg/40 opacity-60 hover:opacity-80"
                  : "hover:bg-surface-2",
              ].join(" ")}
            >
              <div className="flex flex-col gap-1.5">
                <SeverityTag severity={f.severity} />
                {isSuppressed && (
                  <span className="inline-flex items-center rounded-full bg-muted/[0.12] px-2 py-0.5 text-[10px] font-semibold tracking-wide text-dim">
                    {t("findings.suppressedChip")}
                  </span>
                )}
              </div>
              <div>
                <div className="flex items-center gap-2">
                  <span className="font-mono text-[12.5px] font-medium text-text">
                    {f.ruleId}
                  </span>
                  <span className="font-mono text-[11.5px] font-normal text-dim">
                    {f.category}
                  </span>
                  {/* Admin suppress actions — only shown for unsuppressed findings */}
                  {isAdmin && !isSuppressed && (
                    <div className="ml-auto flex items-center gap-1.5">
                      <button
                        type="button"
                        disabled={
                          suppressing === fpKey || suppressing === ruleKey
                        }
                        onClick={() => void handleSuppress(f, "fingerprint")}
                        title="Suppress this specific finding (fingerprint-scoped)"
                        className="rounded-[6px] border border-border px-2 py-0.5 font-mono text-[10px] font-semibold text-muted hover:border-warn/60 hover:text-warn disabled:cursor-not-allowed disabled:opacity-40"
                      >
                        {suppressing === fpKey ? "…" : t("findings.suppress")}
                      </button>
                      <button
                        type="button"
                        disabled={
                          suppressing === fpKey || suppressing === ruleKey
                        }
                        onClick={() => void handleSuppress(f, "rule")}
                        title="Suppress this rule for this tool (rule-scoped)"
                        className="rounded-[6px] border border-border px-2 py-0.5 font-mono text-[10px] font-semibold text-muted hover:border-warn/60 hover:text-warn disabled:cursor-not-allowed disabled:opacity-40"
                      >
                        {suppressing === ruleKey ? "…" : t("findings.suppressRule")}
                      </button>
                    </div>
                  )}
                </div>
                <div className="mt-[3px] font-mono text-[11px] text-dim">
                  {f.location}
                </div>
                <div className="mt-[7px] overflow-hidden whitespace-pre-wrap break-words rounded-md border border-border bg-bg px-[9px] py-1.5 font-mono text-[11.5px] text-muted">
                  {f.evidence}
                </div>
              </div>
            </div>
          );
        })
      )}
    </div>
  );
}
