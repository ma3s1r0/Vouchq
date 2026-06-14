"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import type { ApprovalItem } from "@/lib/mock-approvals";
import { SeverityTag } from "@/components/inventory/SeverityTag";
import { api } from "@/lib/api";
import { useAuth } from "@/lib/auth-context";
import { riskColorVar, riskFillVar } from "@/lib/risk";
import { useT } from "@/lib/i18n";
import { useToast, useConfirm } from "@/lib/feedback";

type Decision = "approved" | "blocked" | "deferred";

/**
 * Approval queue: PENDING items as cards (risk score + top findings +
 * Approve&pin / Block / Defer), plus a simple batch-review affordance
 * (select rows, then approve/block the selection at once).
 * Faithful to design-system/components/approval-queue-item.html.
 */
export function ApprovalQueue({ items }: { items: ApprovalItem[] }) {
  const router = useRouter();
  const { me } = useAuth();
  const { t } = useT();
  const toast = useToast();
  const confirm = useConfirm();
  const canDecide = me?.role === "ADMIN" || me?.role === "MEMBER";
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [decisions, setDecisions] = useState<Record<string, Decision>>({});
  const [errored, setErrored] = useState<Set<string>>(new Set());
  const [busy, setBusy] = useState(false);

  const pending = items.filter((it) => !decisions[it.id]);

  const toggle = (id: string) =>
    setSelected((prev) => {
      const next = new Set(prev);
      next.has(id) ? next.delete(id) : next.add(id);
      return next;
    });

  // Await the API and only confirm the ids that actually succeeded; failures are
  // surfaced (never shown as "approved"). "deferred" is a local triage state.
  const decide = async (ids: string[], d: Decision) => {
    if (
      d === "blocked" &&
      !(await confirm(`${t("action.block")} ${ids.length} ${t("approvals.confirm.block")}`))
    ) {
      return;
    }
    setBusy(true);
    const results = await Promise.allSettled(
      ids.map((id) => {
        if (d === "approved") return api.approveTool(id);
        if (d === "blocked") return api.blockTool(id);
        return Promise.resolve(); // deferred — local only
      }),
    );
    const ok: string[] = [];
    const failed: string[] = [];
    ids.forEach((id, i) =>
      results[i].status === "fulfilled" ? ok.push(id) : failed.push(id),
    );
    setDecisions((prev) => {
      const next = { ...prev };
      for (const id of ok) next[id] = d;
      return next;
    });
    setErrored((prev) => {
      const next = new Set(prev);
      for (const id of failed) next.add(id);
      for (const id of ok) next.delete(id);
      return next;
    });
    setSelected((prev) => {
      const next = new Set(prev);
      for (const id of ids) next.delete(id);
      return next;
    });
    setBusy(false);
    if (ok.length > 0) {
      if (d !== "deferred") {
        toast("success", `${ok.length} · ${t(`approvals.result.${d}`)}`);
        router.refresh();
      }
    }
    if (failed.length > 0) toast("error", t("approvals.error"));
  };

  return (
    <div className="flex flex-col gap-3">
      {/* batch-review bar */}
      <div className="flex flex-wrap items-center gap-2.5 rounded-[11px] border border-border bg-surface px-3.5 py-2.5 text-[12px]">
        <label className="inline-flex cursor-pointer items-center gap-2 text-muted">
          <input
            type="checkbox"
            checked={pending.length > 0 && selected.size === pending.length}
            ref={(el) => {
              if (el)
                el.indeterminate =
                  selected.size > 0 && selected.size < pending.length;
            }}
            onChange={(e) =>
              setSelected(
                e.target.checked ? new Set(pending.map((i) => i.id)) : new Set(),
              )
            }
            className="h-3.5 w-3.5 accent-[#388BFD]"
          />
          {t("approvals.selectAll")}
        </label>
        <span className="font-mono text-dim">
          <b className="text-text">{selected.size}</b> {t("approvals.selected")} ·{" "}
          <b className="text-text">{pending.length}</b> {t("approvals.pending")}
        </span>
        {canDecide && (
          <div className="ml-auto flex gap-2">
            <button
              type="button"
              disabled={selected.size === 0 || busy}
              onClick={() => decide([...selected], "approved")}
              className="rounded-lg bg-approved px-3 py-1.5 text-[12px] font-semibold text-[#04130A] disabled:cursor-not-allowed disabled:opacity-40"
            >
              {t("approvals.approveSelected")}
            </button>
            <button
              type="button"
              disabled={selected.size === 0 || busy}
              onClick={() => decide([...selected], "blocked")}
              className="rounded-lg border border-blocked/[0.45] px-3 py-1.5 text-[12px] font-semibold text-blocked disabled:cursor-not-allowed disabled:opacity-40"
            >
              {t("approvals.blockSelected")}
            </button>
          </div>
        )}
      </div>

      {items.map((it) => {
        const d = decisions[it.id];
        if (d) {
          return (
            <div
              key={it.id}
              className={`flex items-center gap-3 rounded-[11px] border px-[18px] py-3.5 text-[13px] ${
                d === "approved"
                  ? "border-approved/40 bg-approved/[0.08] text-approved"
                  : d === "blocked"
                    ? "border-blocked/40 bg-blocked/[0.08] text-blocked"
                    : "border-border bg-surface text-muted"
              }`}
            >
              <span className="font-mono font-semibold">{it.name}</span>
              <span className="font-mono text-[12px] opacity-80">
                {d === "approved"
                  ? t("approvals.result.approved")
                  : d === "blocked"
                    ? t("approvals.result.blocked")
                    : t("approvals.result.deferred")}
              </span>
              {d === "deferred" && (
                <button
                  type="button"
                  onClick={() =>
                    setDecisions((prev) => {
                      const next = { ...prev };
                      delete next[it.id];
                      return next;
                    })
                  }
                  className="ml-auto font-mono text-[12px] underline opacity-80"
                >
                  {t("approvals.undo")}
                </button>
              )}
            </div>
          );
        }

        const checked = selected.has(it.id);
        return (
          <div
            key={it.id}
            className="relative overflow-hidden rounded-[11px] border border-border bg-surface px-[18px] py-4"
          >
            <span className="absolute inset-y-0 left-0 w-[3px] bg-dim" aria-hidden />
            <div className="flex items-start gap-3">
              <input
                type="checkbox"
                checked={checked}
                onChange={() => toggle(it.id)}
                className="mt-1 h-3.5 w-3.5 accent-[#388BFD]"
                aria-label={`select ${it.name}`}
              />
              <div className="min-w-0">
                <span
                  className="inline-block max-w-[320px] truncate align-bottom font-mono text-[15px] font-semibold"
                  title={it.name}
                >
                  {it.name}
                </span>
                <span className="ml-2 rounded bg-primary/[0.13] px-1.5 py-0.5 align-middle font-mono text-[10px] font-bold tracking-[0.06em] text-primary">
                  {it.kind}
                </span>
                <div className="mt-1 flex items-center gap-2 font-mono text-[12px] text-muted">
                  <span className="text-text">{it.source}</span>
                  <span className="text-dim">·</span>
                  {it.ref}
                  <span className="text-dim">·</span>
                  sha {it.shaShort}
                </div>
              </div>
              <span className="ml-auto inline-flex items-center gap-1.5 rounded-full border border-border bg-muted/[0.12] py-1 pl-2 pr-2.5 text-[11px] font-semibold text-muted">
                <span className="h-1.5 w-1.5 rounded-full bg-dim" />
                {t("status.PENDING")}
              </span>
            </div>

            <div className="my-3.5 flex flex-col gap-4 border-y border-border py-3 md:flex-row md:items-center md:gap-4">
              <div className="flex min-w-[170px] items-center gap-3">
                <div
                  className="min-w-[40px] font-mono text-[26px] font-semibold leading-none"
                  style={{ color: riskColorVar(it.risk) }}
                >
                  {it.risk}
                </div>
                <div className="flex-1">
                  <div className="mb-1.5 text-[10px] uppercase tracking-[0.06em] text-muted">
                    {t("approvals.riskScore")} · {it.findingCount} {t("approvals.findings")}
                  </div>
                  <div className="h-1.5 overflow-hidden rounded-full border border-border bg-bg">
                    <div
                      className="h-full"
                      style={{
                        width: `${it.risk}%`,
                        background: riskFillVar(it.risk),
                      }}
                    />
                  </div>
                </div>
              </div>
              <div className="flex flex-1 flex-col gap-1.5">
                {it.topFindings.map((f, i) => (
                  <div
                    key={i}
                    className="flex items-center gap-2 text-[12px] text-muted"
                  >
                    <SeverityTag severity={f.severity} short />
                    {f.text}
                  </div>
                ))}
              </div>
            </div>

            <div className="flex flex-wrap items-center gap-2.5">
              {canDecide && (
                <>
                  <button
                    type="button"
                    disabled={busy}
                    onClick={() => decide([it.id], "approved")}
                    className="rounded-lg border border-approved bg-approved px-[15px] py-2 text-[13px] font-semibold text-[#04130A] disabled:opacity-60"
                  >
                    {t("approvals.approvePinBtn")}
                  </button>
                  <button
                    type="button"
                    disabled={busy}
                    onClick={() => decide([it.id], "blocked")}
                    className="rounded-lg border border-blocked/[0.45] px-[15px] py-2 text-[13px] font-semibold text-blocked disabled:opacity-60"
                  >
                    {t("action.block")}
                  </button>
                  <button
                    type="button"
                    disabled={busy}
                    onClick={() => decide([it.id], "deferred")}
                    className="rounded-lg border border-border bg-surface-2 px-[15px] py-2 text-[13px] font-semibold text-muted disabled:opacity-60"
                  >
                    {t("approvals.defer")}
                  </button>
                </>
              )}
              <Link
                href={`/inventory/${encodeURIComponent(it.id)}`}
                className="ml-auto font-mono text-[12px] text-primary hover:underline"
              >
                {t("approvals.viewDiff")}
              </Link>
            </div>
            {errored.has(it.id) && (
              <p className="mt-2 text-[12px] text-crit">
                {t("approvals.error")}
              </p>
            )}
          </div>
        );
      })}

      {pending.length === 0 && (
        <div className="rounded-[11px] border border-border bg-surface px-4 py-12 text-center text-[13px] text-dim">
          {t("approvals.empty")}
        </div>
      )}
    </div>
  );
}
