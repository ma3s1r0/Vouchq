"use client";

import { useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { api } from "@/lib/api";
import {
  type AssetKind,
  type AssetStatus,
  type InventoryItem,
  riskBand,
  sourceCount,
} from "@/lib/mock-inventory";
import { StatusBadge } from "./StatusBadge";
import { RiskMeter } from "./RiskMeter";
import { Onboarding } from "@/components/shell/Onboarding";
import { Pager } from "@/components/shell/Pager";
import { useToast } from "@/lib/feedback";
import { useT } from "@/lib/i18n";

/** Rows per page — the rich filters (risk/source) stay client-side, so the
 * filtered list is paginated client-side rather than rendered all at once. */
const PAGE_SIZE = 25;

type KindFilter = "ALL" | AssetKind;
type StatusFilter = "ALL" | AssetStatus;
/** Risk floor: ALL, or "≥ INFO/WARN/CRITICAL". */
type RiskFilter = "ALL" | "INFO" | "WARN" | "CRITICAL";

const KIND_OPTIONS: KindFilter[] = [
  "ALL",
  "SKILL",
  "MCP_TOOL",
  "TOOL",
  "MCP_SERVER",
];
const STATUS_OPTIONS: StatusFilter[] = [
  "ALL",
  "APPROVED",
  "PENDING",
  "DRIFTED",
  "BLOCKED",
];
const RISK_OPTIONS: RiskFilter[] = ["ALL", "INFO", "WARN", "CRITICAL"];
const RISK_FLOOR: Record<Exclude<RiskFilter, "ALL">, number> = {
  INFO: 1,
  WARN: 2,
  CRITICAL: 3,
};
const BAND_RANK: Record<ReturnType<typeof riskBand>, number> = {
  CLEAN: 0,
  INFO: 1,
  WARN: 2,
  CRITICAL: 3,
};

/** A select styled like the design-system `.sel` chip. */
function FilterSelect<T extends string>({
  label,
  value,
  options,
  onChange,
  format,
}: {
  label: string;
  value: T;
  options: T[];
  onChange: (v: T) => void;
  format?: (v: T) => string;
}) {
  return (
    <label className="inline-flex cursor-pointer items-center gap-1.5 rounded-lg border border-border bg-surface-2 px-2.5 py-[7px] text-[12px] text-muted">
      {label}
      <select
        value={value}
        onChange={(e) => onChange(e.target.value as T)}
        className="cursor-pointer appearance-none bg-transparent font-semibold text-text outline-none"
      >
        {options.map((o) => (
          <option key={o} value={o} className="bg-surface text-text">
            {format ? format(o) : o}
          </option>
        ))}
      </select>
    </label>
  );
}

export function InventoryView({ items }: { items: InventoryItem[] }) {
  const router = useRouter();
  const { t } = useT();
  const toast = useToast();
  const searchParams = useSearchParams();
  const [query, setQuery] = useState(searchParams.get("q") ?? "");
  // Keep the box in sync when arriving via the topbar global search (?q=…).
  useEffect(() => {
    setQuery(searchParams.get("q") ?? "");
  }, [searchParams]);
  const [kind, setKind] = useState<KindFilter>("ALL");
  const [status, setStatus] = useState<StatusFilter>("ALL");
  const [risk, setRisk] = useState<RiskFilter>("ALL");
  const [rescanning, setRescanning] = useState(false);
  const [page, setPage] = useState(0);
  // A repo registers many skills, so group the inventory by source by default
  // (MA3-135); a toggle drops back to the flat, paginated list.
  const [grouped, setGrouped] = useState(true);
  // Any filter/search change returns to the first page.
  useEffect(() => {
    setPage(0);
  }, [query, kind, status, risk]);

  // Re-scan every connected source (re-fetch → re-scan → drift check), then
  // refresh the server-rendered inventory so new versions/risk/drift show up.
  const handleRescanAll = async () => {
    setRescanning(true);
    try {
      const sources = await api.getSources();
      // Best-effort: one unreachable source must not abort the whole rescan.
      const results = await Promise.allSettled(sources.map((s) => api.scanSource(s.id)));
      const failed = results.filter((r) => r.status === "rejected").length;
      router.refresh();
      if (failed > 0) {
        toast("error", `${failed}/${results.length} ${t("inventory.rescan.partial")}`);
      } else if (results.length > 0) {
        toast("success", t("inventory.rescan.done"));
      }
    } catch {
      // The source list itself couldn't be fetched — tell the user.
      toast("error", t("inventory.rescan.failed"));
    } finally {
      setRescanning(false);
    }
  };

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    return items.filter((it) => {
      if (kind !== "ALL" && it.kind !== kind) return false;
      if (status !== "ALL" && it.status !== status) return false;
      if (
        risk !== "ALL" &&
        BAND_RANK[riskBand(it.risk)] < RISK_FLOOR[risk]
      )
        return false;
      if (q) {
        const hay = `${it.name} ${it.versionRef} ${it.source}`.toLowerCase();
        if (!hay.includes(q)) return false;
      }
      return true;
    });
  }, [items, query, kind, status, risk]);

  const paged = useMemo(
    () => filtered.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE),
    [filtered, page],
  );

  // Group the filtered items by their source, newest-ish first by label.
  const groups = useMemo(() => {
    const map = new Map<string, InventoryItem[]>();
    for (const it of filtered) {
      const key = it.sourceId ?? it.source ?? "—";
      const bucket = map.get(key);
      if (bucket) bucket.push(it);
      else map.set(key, [it]);
    }
    return [...map.entries()]
      .map(([key, list]) => ({ key, label: list[0].source, items: list }))
      .sort((a, b) => a.label.localeCompare(b.label));
  }, [filtered]);

  const tableHead = (showSource: boolean) => (
    <thead>
      <tr>
        {[
          t("inventory.col.name"),
          t("inventory.col.kind"),
          ...(showSource ? [t("inventory.col.source")] : []),
          t("inventory.col.status"),
          t("inventory.col.risk"),
          t("inventory.col.lastVerified"),
        ].map((h) => (
          <th
            key={h}
            scope="col"
            className="border-b border-border bg-sidebar px-4 py-2.5 text-left text-[11px] font-semibold uppercase tracking-[0.08em] text-dim"
          >
            {h}
          </th>
        ))}
      </tr>
    </thead>
  );

  const renderRow = (it: InventoryItem, showSource: boolean) => (
    <tr key={it.id} className="border-b border-border last:border-b-0 hover:bg-surface-2">
      <td className="max-w-[280px] px-4 py-[11px]">
        <Link
          href={`/inventory/${encodeURIComponent(it.id)}`}
          className="block font-semibold text-text hover:underline"
        >
          <span className="block truncate" title={it.name}>{it.name}</span>
          <span className="block font-mono text-[11px] font-normal text-dim">
            {t("inventory.versionRef")} {it.versionRef}
          </span>
        </Link>
      </td>
      <td className="px-4 py-[11px]">
        <span className="rounded border border-border px-1.5 py-0.5 font-mono text-[11px] text-muted">
          {it.kind}
        </span>
      </td>
      {showSource && (
        <td className="px-4 py-[11px] font-mono text-[12px] text-muted">{it.source}</td>
      )}
      <td className="px-4 py-[11px]"><StatusBadge status={it.status} /></td>
      <td className="px-4 py-[11px]"><RiskMeter risk={it.risk} /></td>
      <td className="px-4 py-[11px] text-[12px] text-dim">{it.lastVerified}</td>
    </tr>
  );

  // Concerning-status chips for a group header (approved is the calm default).
  const groupChips = (list: InventoryItem[]) => {
    const c: Record<string, number> = {};
    for (const it of list) c[it.status] = (c[it.status] ?? 0) + 1;
    const tone: Record<string, string> = {
      DRIFTED: "text-drift",
      BLOCKED: "text-crit",
      PENDING: "text-warn",
    };
    return (["DRIFTED", "BLOCKED", "PENDING"] as const)
      .filter((s) => c[s])
      .map((s) => (
        <span key={s} className={`font-mono text-[11px] ${tone[s]}`}>
          {c[s]} {s.toLowerCase()}
        </span>
      ));
  };

  // First run (no assets at all) → onboarding, distinct from a no-match filter.
  if (items.length === 0) {
    return <Onboarding />;
  }

  return (
    <div className="flex flex-col gap-3">
      <div className="flex max-w-full flex-wrap items-center gap-2.5 rounded-[11px] border border-border bg-surface px-3.5 py-3">
        <div className="relative min-w-[220px] flex-1">
          <span
            aria-hidden
            className="pointer-events-none absolute left-2.5 top-1/2 -translate-y-1/2 text-[12px] text-dim"
          >
            ⌕
          </span>
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder={t("inventory.search")}
            aria-label={t("inventory.search")}
            className="w-full rounded-lg border border-border bg-bg py-2 pl-[30px] pr-2.5 font-mono text-[12.5px] text-text outline-none placeholder:text-dim focus:border-border-strong"
          />
        </div>

        <FilterSelect
          label={t("inventory.filter.kind")}
          value={kind}
          options={KIND_OPTIONS}
          onChange={setKind}
          format={(v) => (v === "ALL" ? t("filter.all") : v)}
        />
        <FilterSelect
          label={t("inventory.filter.status")}
          value={status}
          options={STATUS_OPTIONS}
          onChange={setStatus}
          format={(v) => (v === "ALL" ? t("filter.all") : v)}
        />
        <FilterSelect
          label={t("inventory.filter.risk")}
          value={risk}
          options={RISK_OPTIONS}
          onChange={setRisk}
          format={(v) => (v === "ALL" ? t("filter.all") : `≥ ${v}`)}
        />

        <div
          className="ml-auto inline-flex rounded-lg border border-border bg-surface-2 p-0.5 text-[12px] font-semibold"
          role="group"
          aria-label={t("inventory.viewMode")}
        >
          <button
            type="button"
            onClick={() => setGrouped(true)}
            aria-pressed={grouped}
            className={`rounded-md px-2.5 py-1 transition-colors ${grouped ? "bg-primary/[0.14] text-[#9DC3FF]" : "text-muted hover:text-text"}`}
          >
            {t("inventory.grouped")}
          </button>
          <button
            type="button"
            onClick={() => setGrouped(false)}
            aria-pressed={!grouped}
            className={`rounded-md px-2.5 py-1 transition-colors ${!grouped ? "bg-primary/[0.14] text-[#9DC3FF]" : "text-muted hover:text-text"}`}
          >
            {t("inventory.flat")}
          </button>
        </div>
        <span className="whitespace-nowrap font-mono text-[12px] text-dim">
          <b className="text-text">{filtered.length}</b> {t("inventory.assets")} ·{" "}
          <b className="text-text">{sourceCount(items)}</b> {t("inventory.sources")}
        </span>
        <button
          type="button"
          onClick={handleRescanAll}
          disabled={rescanning}
          className="inline-flex items-center gap-[7px] rounded-lg bg-primary px-3.5 py-2 text-[13px] font-semibold text-[#04101F] transition-opacity hover:opacity-90 disabled:opacity-60"
        >
          <span aria-hidden className="h-[7px] w-[7px] rounded-full bg-[#04101F]" />
          {rescanning ? t("action.rescanning") : t("action.rescanAll")}
        </button>
      </div>

      {filtered.length === 0 ? (
        <div className="rounded-[11px] border border-border bg-surface px-4 py-10 text-center text-[13px] text-dim">
          {t("inventory.empty")}
        </div>
      ) : grouped ? (
        // One collapsible card per source — a repo's many skills are grouped (MA3-135).
        <div className="flex flex-col gap-3">
          {groups.map((g) => (
            <details
              key={g.key}
              open
              className="overflow-hidden rounded-[11px] border border-border bg-surface [&[open]>summary_.chev]:rotate-90"
            >
              <summary className="flex cursor-pointer list-none items-center gap-3 border-b border-border bg-sidebar px-4 py-3 hover:bg-surface-2">
                <span aria-hidden className="chev font-mono text-[11px] text-dim transition-transform">▸</span>
                <span className="truncate font-semibold text-text" title={g.label}>{g.label}</span>
                <span className="font-mono text-[11px] text-dim">
                  {g.items.length} {t("inventory.assets")}
                </span>
                <span className="ml-auto flex items-center gap-2.5">{groupChips(g.items)}</span>
              </summary>
              <table className="w-full border-collapse text-[13px]">
                {tableHead(false)}
                <tbody>{g.items.map((it) => renderRow(it, false))}</tbody>
              </table>
            </details>
          ))}
        </div>
      ) : (
        <>
          <div className="overflow-hidden rounded-[11px] border border-border bg-surface">
            <table className="w-full border-collapse text-[13px]">
              {tableHead(true)}
              <tbody>{paged.map((it) => renderRow(it, true))}</tbody>
            </table>
          </div>
          <Pager page={page} pageSize={PAGE_SIZE} total={filtered.length} onPage={setPage} />
        </>
      )}
    </div>
  );
}
