"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import type { DriftAlert } from "@/lib/mock-drift";
import { DiffView } from "@/components/inventory/DiffView";
import { SeverityTag } from "@/components/inventory/SeverityTag";
import { api } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { useT } from "@/lib/i18n";
import { useToast, useConfirm } from "@/lib/feedback";

type Resolution = "reapproved" | "blocked";

/**
 * Drift alerts: each DRIFTED asset as a card showing what changed (severity +
 * one-line summary). Clicking a card expands the current↔pinned diff (reusing
 * `<DiffView>`); re-approve pins the observed sha, block revokes it.
 * Faithful to design-system/components/diff-view.html.
 */
export function DriftList({ alerts }: { alerts: DriftAlert[] }) {
  const router = useRouter();
  const { me } = useAuth();
  const { t } = useT();
  const toast = useToast();
  const confirm = useConfirm();
  const canDecide = me?.role === "ADMIN" || me?.role === "MEMBER";
  const [open, setOpen] = useState<Set<string>>(new Set());
  const [resolved, setResolved] = useState<Record<string, Resolution>>({});
  const [errored, setErrored] = useState<Set<string>>(new Set());
  const [busy, setBusy] = useState<string | null>(null);

  const toggle = (id: string) =>
    setOpen((prev) => {
      const next = new Set(prev);
      next.has(id) ? next.delete(id) : next.add(id);
      return next;
    });

  // Re-approve must actually RE-PIN the observed version (approveTool pins the
  // tool's current version) and then resolve the drift event; block revokes the
  // tool and resolves the event. Awaited — only confirms on success.
  const resolve = async (a: DriftAlert, r: Resolution) => {
    if (
      r === "blocked" &&
      !(await confirm(`${t("action.block")} ${a.name}${t("drift.confirm.block")}`))
    ) {
      return;
    }
    setBusy(a.id);
    setErrored((prev) => {
      const next = new Set(prev);
      next.delete(a.id);
      return next;
    });
    try {
      if (r === "reapproved") await api.approveTool(a.toolId);
      else await api.blockTool(a.toolId);
      await api.resolveDriftEvent(a.id);
      setResolved((prev) => ({ ...prev, [a.id]: r }));
      toast(
        "success",
        r === "reapproved" ? t("drift.toast.reapproved") : t("drift.toast.blocked"),
      );
      router.refresh();
    } catch {
      setErrored((prev) => new Set(prev).add(a.id));
      toast("error", t("drift.error"));
    } finally {
      setBusy(null);
    }
  };

  const pending = alerts.filter((a) => !resolved[a.id]);

  if (pending.length === 0) {
    return (
      <div className="rounded-[11px] border border-border bg-surface px-4 py-12 text-center text-[13px] text-dim">
        {t("drift.empty")}
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-3">
      {alerts.map((a) => {
        const r = resolved[a.id];
        if (r) {
          return (
            <div
              key={a.id}
              className={`flex items-center gap-3 rounded-[11px] border px-[18px] py-3.5 text-[13px] ${
                r === "reapproved"
                  ? "border-approved/40 bg-approved/[0.08] text-approved"
                  : "border-blocked/40 bg-blocked/[0.08] text-blocked"
              }`}
            >
              <span className="font-mono font-semibold">{a.name}</span>
              <span className="font-mono text-[12px] opacity-80">
                {r === "reapproved"
                  ? `${t("drift.result.reapproved")} ${a.diff.observedSha}`
                  : t("drift.result.blocked")}
              </span>
            </div>
          );
        }

        const isOpen = open.has(a.id);
        // Blast radius: how much changed, shown before expanding the diff.
        const added = a.diff.lines.filter((l) => l.kind === "add").length;
        const removed = a.diff.lines.filter((l) => l.kind === "del").length;
        return (
          <div
            key={a.id}
            className="overflow-hidden rounded-[11px] border border-border bg-surface"
          >
            <button
              type="button"
              onClick={() => toggle(a.id)}
              aria-expanded={isOpen}
              className="flex w-full items-start gap-3 px-[18px] py-4 text-left"
            >
              <span className="mt-1 h-2 w-2 flex-none rounded-full bg-drift drift-pulse" />
              <div className="min-w-0">
                <span
                  className="inline-block max-w-[320px] truncate align-bottom font-mono text-[15px] font-semibold"
                  title={a.name}
                >
                  {a.name}
                </span>
                <span className="ml-2 rounded border border-border px-1.5 py-0.5 align-middle font-mono text-[10px] text-muted">
                  {a.kind}
                </span>
                <div className="mt-1 flex flex-wrap items-center gap-2 text-[12px] text-muted">
                  <SeverityTag severity={a.severity} short />
                  {(added > 0 || removed > 0) && (
                    <span className="font-mono text-[11px]">
                      <span className="text-approved">+{added}</span>{" "}
                      <span className="text-crit">−{removed}</span>
                    </span>
                  )}
                  {a.summary}
                </div>
                <div className="mt-1 font-mono text-[11px] text-dim">
                  {a.source} · {t("drift.pinned")} {a.diff.pinnedSha} → {t("drift.observed")}{" "}
                  {a.diff.observedSha}
                </div>
              </div>
              <div className="ml-auto flex items-center gap-3 pl-3">
                <span className="font-mono text-[12px] text-dim">
                  {a.detected}
                </span>
                <span
                  className={`text-dim transition-transform ${
                    isOpen ? "rotate-90" : ""
                  }`}
                  aria-hidden
                >
                  ▸
                </span>
              </div>
            </button>

            {isOpen && (
              <div className="border-t border-border px-[18px] py-4">
                <DiffView name={a.name} diff={a.diff} />
                <div className="mt-3.5 flex flex-wrap items-center gap-2.5">
                  {canDecide && (
                    <>
                      <button
                        type="button"
                        disabled={busy === a.id}
                        onClick={() => resolve(a, "reapproved")}
                        className="rounded-lg border border-approved bg-approved px-[15px] py-2 text-[13px] font-semibold text-[#04130A] disabled:opacity-60"
                      >
                        {busy === a.id ? "…" : t("drift.reapprove")}
                      </button>
                      <button
                        type="button"
                        disabled={busy === a.id}
                        onClick={() => resolve(a, "blocked")}
                        className="rounded-lg border border-blocked/[0.45] px-[15px] py-2 text-[13px] font-semibold text-blocked disabled:opacity-60"
                      >
                        {t("action.block")}
                      </button>
                    </>
                  )}
                  <Link
                    href={`/inventory/${encodeURIComponent(a.toolId)}`}
                    className="ml-auto font-mono text-[12px] text-primary hover:underline"
                  >
                    {t("drift.viewManifest")}
                  </Link>
                </div>
                {errored.has(a.id) && (
                  <p className="mt-2 text-[12px] text-crit">
                    {t("drift.error")}
                  </p>
                )}
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}
