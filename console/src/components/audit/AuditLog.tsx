"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { api, type ApiAuditLogView, type ApiAuditVerify } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { Pager } from "@/components/shell/Pager";
import { Select } from "@/components/shell/Select";

/** Chip color per action kind; unknown actions fall back to a neutral chip. */
const CHIP: Record<string, string> = {
  TOOL_APPROVED: "bg-approved/[0.14] text-approved",
  TOOL_BLOCKED: "bg-crit/[0.14] text-crit",
  DRIFT_DETECTED: "bg-drift/[0.14] text-drift",
  SCAN_RUN: "bg-primary/[0.14] text-primary",
  SCAN_COMPLETED: "bg-primary/[0.14] text-primary",
  SOURCE_CONNECTED: "bg-primary/[0.14] text-primary",
  SOURCE_DELETED: "bg-crit/[0.14] text-crit",
  POLICY_APPLIED: "bg-drift/[0.14] text-drift",
  SUPPRESSION_CREATED: "bg-muted/[0.14] text-muted",
  SUPPRESSION_DELETED: "bg-muted/[0.14] text-muted",
  MEMBER_INVITED: "bg-muted/[0.14] text-muted",
};
const CHIP_DEFAULT = "bg-muted/[0.14] text-muted";

/** Audit actions the backend emits — seeds the filter dropdown (MA3-118). */
const KNOWN_ACTIONS = [
  "TOOL_APPROVED",
  "TOOL_BLOCKED",
  "DRIFT_DETECTED",
  "SCAN_RUN",
  "SOURCE_CONNECTED",
  "SOURCE_DELETED",
  "POLICY_APPLIED",
  "SUPPRESSION_CREATED",
  "SUPPRESSION_DELETED",
];

const PAGE_SIZE = 50;

/** Split an ISO timestamp into date + HH:MM:SSZ for the mono time column. */
function splitTime(iso: string): [string, string] {
  const [date, rest] = iso.split("T");
  return [date, (rest ?? "").replace(/\.\d+/, "")];
}

/** UUID → short 8-char identifier for the target column. */
function shortTarget(id: string | null): string {
  return id ? id.replace(/-/g, "").slice(0, 8) : "—";
}

function triggerDownload(filename: string, mime: string, content: string) {
  const blob = new Blob([content], { type: mime });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

/** CSV of the current page (clearly scoped — not the full chain). */
function toCsv(rows: ApiAuditLogView[]): string {
  const cols = ["id", "createdAt", "action", "targetId", "actor", "payload", "entryHash"] as const;
  const esc = (v: string) => `"${v.replace(/"/g, '""')}"`;
  const head = cols.join(",");
  const body = rows.map((r) => cols.map((c) => esc(String(r[c] ?? ""))).join(",")).join("\n");
  return `${head}\n${body}\n`;
}

/**
 * Audit log (MA3-118): server-side paginated + filtered (action / actor / since
 * drive the backend query, not a client slice) so the unbounded WORM log is
 * never fully rendered. Client export covers the current page; "Download full
 * log" streams the entire hash-chain from the backend. Hash-chain verify badge
 * reflects the real /verify result (MA3-105).
 */
export function AuditLog() {
  const { t } = useT();
  const [action, setAction] = useState<string>("ALL");
  const [actor, setActor] = useState<string>("ALL");
  const [since, setSince] = useState("");
  const [page, setPage] = useState(0);

  const [items, setItems] = useState<ApiAuditLogView[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);

  // Filter dropdowns: seed actions with the known set, accumulate actors as
  // pages arrive (there is no distinct-values endpoint).
  const [seenActors, setSeenActors] = useState<string[]>([]);

  // A filter change resets to the first page.
  const onFilter = useCallback(<T,>(set: (v: T) => void) => (v: T) => {
    set(v);
    setPage(0);
  }, []);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(false);
    api
      .getAuditLogsPage({
        action: action === "ALL" ? undefined : action,
        actor: actor === "ALL" ? undefined : actor,
        since: since || undefined,
        page,
        limit: PAGE_SIZE,
      })
      .then(({ items, total }) => {
        if (cancelled) return;
        setItems(items);
        setTotal(total);
        setSeenActors((prev) => [...new Set([...prev, ...items.map((i) => i.actor)])].sort());
      })
      .catch(() => !cancelled && setError(true))
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, [action, actor, since, page]);

  // Real hash-chain verification (MA3-105) — the badge reflects /verify.
  const [verify, setVerify] = useState<ApiAuditVerify | null>(null);
  const [verifying, setVerifying] = useState(true);
  useEffect(() => {
    let cancelled = false;
    api
      .verifyAuditChain()
      .then((v) => !cancelled && setVerify(v))
      .catch(() => !cancelled && setVerify(null))
      .finally(() => !cancelled && setVerifying(false));
    return () => {
      cancelled = true;
    };
  }, []);

  const actorOptions = useMemo(
    () => [...new Set([...seenActors, ...(actor !== "ALL" ? [actor] : [])])].sort(),
    [seenActors, actor],
  );

  // "ALL" + known/seen values, feeding the custom dark dropdowns (MA3-136).
  const actionChoices = useMemo(() => ["ALL", ...KNOWN_ACTIONS], []);
  const actorChoices = useMemo(() => ["ALL", ...actorOptions], [actorOptions]);

  const exportBtn =
    "rounded-lg border border-border-strong bg-transparent px-3 py-[7px] text-[12px] font-semibold text-text hover:border-primary";

  return (
    <div className="flex max-w-[760px] flex-col gap-3">
      {/* filter bar */}
      <div className="flex flex-wrap items-center gap-2.5 rounded-[11px] border border-border bg-surface px-3.5 py-3">
        <Select
          label={t("audit.filter.action")}
          value={action}
          options={actionChoices}
          onChange={(v) => onFilter(setAction)(v)}
          format={(v) => (v === "ALL" ? t("audit.allActions") : v)}
        />

        <Select
          label={t("audit.filter.actor")}
          value={actor}
          options={actorChoices}
          onChange={(v) => onFilter(setActor)(v)}
          format={(v) => (v === "ALL" ? t("audit.allActors") : v)}
        />

        <label className="inline-flex cursor-pointer items-center gap-1.5 rounded-lg border border-border bg-surface-2 px-2.5 py-[7px] text-[12px] text-muted">
          {t("audit.filter.since")}
          <input
            type="date"
            value={since}
            onChange={(e) => onFilter(setSince)(e.target.value)}
            className="cursor-pointer bg-transparent font-mono text-[12px] font-semibold text-text outline-none [color-scheme:dark]"
          />
        </label>

        <div className="ml-auto flex items-center gap-2">
          <button
            type="button"
            title={t("audit.export.viewHint")}
            onClick={() => triggerDownload("audit-view.csv", "text/csv", toCsv(items))}
            className={exportBtn}
          >
            {t("audit.export.csv")}
          </button>
          <button
            type="button"
            title={t("audit.export.viewHint")}
            onClick={() =>
              triggerDownload("audit-view.json", "application/json", JSON.stringify(items, null, 2))
            }
            className={exportBtn}
          >
            {t("audit.export.json")}
          </button>
        </div>
      </div>

      {/* full-log download — the complete WORM chain, not the current view */}
      <div className="flex flex-wrap items-center gap-2 rounded-[11px] border border-dashed border-border bg-surface px-3.5 py-2.5 text-[12px] text-dim">
        <span>{t("audit.export.full")}:</span>
        <a
          href={api.auditExportUrl("json")}
          className="rounded border border-border-strong px-2 py-1 font-semibold text-text hover:border-primary"
        >
          {t("audit.export.fullJson")}
        </a>
        <a
          href={api.auditExportUrl("csv")}
          className="rounded border border-border-strong px-2 py-1 font-semibold text-text hover:border-primary"
        >
          {t("audit.export.fullCsv")}
        </a>
      </div>

      {/* chained log */}
      <div className="overflow-hidden rounded-[11px] border border-border bg-surface">
        <div className="flex items-center gap-2 border-b border-border bg-sidebar px-4 py-[11px] text-[11px] font-semibold uppercase tracking-[0.1em] text-muted">
          {t("audit.header")}
          {verifying ? (
            <span className="ml-auto inline-flex items-center gap-1.5 normal-case tracking-normal text-muted">
              <span className="h-1.5 w-1.5 rounded-full bg-muted" />
              {t("audit.chain.verifying")}
            </span>
          ) : verify?.valid ? (
            <span className="ml-auto inline-flex items-center gap-1.5 normal-case tracking-normal text-approved">
              <span className="h-1.5 w-1.5 rounded-full bg-approved" />
              {t("audit.chain.verified")} · {total} {t("audit.chain.entries")}
            </span>
          ) : verify && !verify.valid ? (
            <span
              className="ml-auto inline-flex items-center gap-1.5 normal-case tracking-normal text-crit"
              title={verify.reason ?? undefined}
            >
              <span className="h-1.5 w-1.5 rounded-full bg-crit" />
              {t("audit.chain.broken")}
              {verify.brokenEntryId != null ? ` ${t("audit.chain.at")} #${verify.brokenEntryId}` : ""}
            </span>
          ) : (
            <span className="ml-auto inline-flex items-center gap-1.5 normal-case tracking-normal text-dim">
              <span className="h-1.5 w-1.5 rounded-full bg-dim" />
              {t("audit.chain.hashed")} · {total} {t("audit.chain.entries")}
            </span>
          )}
        </div>

        {items.map((e) => {
          const [date, time] = splitTime(e.createdAt);
          return (
            <div
              key={e.id}
              className="grid grid-cols-[128px_18px_1fr] gap-2.5 border-b border-border px-4 py-[11px] last:border-b-0"
            >
              <div className="font-mono text-[11px] leading-[1.5] text-dim">
                {date}
                <br />
                {time}
              </div>
              <div className="relative flex justify-center">
                <span
                  className="absolute -top-[11px] bottom-[-11px] w-px bg-border"
                  aria-hidden
                />
                <span className="z-[1] mt-[3px] h-[9px] w-[9px] rounded-full border-[1.5px] border-primary bg-surface" />
              </div>
              <div>
                <div className="text-[13px] font-semibold">
                  <span
                    className={`mr-[7px] rounded px-1.5 py-px align-[1px] font-mono text-[10px] font-semibold ${CHIP[e.action] ?? CHIP_DEFAULT}`}
                  >
                    {e.action}
                  </span>
                  <span className="font-mono">{shortTarget(e.targetId)}</span>
                </div>
                <div className="mt-0.5 text-[12px] text-muted">
                  {t("audit.by")} {e.actor}
                  {e.payload ? ` · ${e.payload}` : ""}
                </div>
                <div className="mt-[5px] font-mono text-[10.5px] text-dim">
                  <b className="text-muted">{t("audit.entry")}</b> {e.entryHash?.slice(0, 16) ?? "—"}…
                </div>
              </div>
            </div>
          );
        })}

        {loading && (
          <div className="px-4 py-10 text-center text-[13px] text-dim">{t("common.loading")}</div>
        )}
        {!loading && error && (
          <div className="px-4 py-10 text-center text-[13px] text-crit">{t("audit.loadError")}</div>
        )}
        {!loading && !error && items.length === 0 && (
          <div className="px-4 py-10 text-center text-[13px] text-dim">{t("audit.empty")}</div>
        )}
      </div>

      <Pager page={page} pageSize={PAGE_SIZE} total={total} onPage={setPage} />
    </div>
  );
}
