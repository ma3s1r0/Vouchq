"use client";

import { useT } from "@/lib/i18n";

/**
 * Prev / Next pager over a 0-based page index (MA3-118). Works for both
 * server-paginated lists (audit log) and client-paginated lists (inventory):
 * the caller owns `page`/`total`, this only renders the range + controls.
 * Renders nothing when everything fits on one page.
 */
export function Pager({
  page,
  pageSize,
  total,
  onPage,
}: {
  page: number;
  pageSize: number;
  total: number;
  onPage: (p: number) => void;
}) {
  const { t } = useT();
  if (total <= pageSize) return null;

  const pages = Math.max(1, Math.ceil(total / pageSize));
  const from = total === 0 ? 0 : page * pageSize + 1;
  const to = Math.min(total, (page + 1) * pageSize);
  const atStart = page <= 0;
  const atEnd = page >= pages - 1;

  const btn =
    "rounded-lg border border-border-strong bg-transparent px-3 py-[7px] text-[12px] font-semibold text-text transition-opacity disabled:opacity-40 disabled:cursor-not-allowed hover:enabled:border-primary";

  return (
    <nav
      aria-label="Pagination"
      className="flex items-center justify-between gap-3 rounded-[11px] border border-border bg-surface px-3.5 py-2.5"
    >
      <span className="font-mono text-[12px] text-dim">
        <b className="text-text">{from}</b>–<b className="text-text">{to}</b> {t("pager.of")}{" "}
        <b className="text-text">{total}</b>
      </span>
      <div className="flex gap-2">
        <button type="button" className={btn} disabled={atStart} onClick={() => onPage(page - 1)}>
          ← {t("pager.prev")}
        </button>
        <button type="button" className={btn} disabled={atEnd} onClick={() => onPage(page + 1)}>
          {t("pager.next")} →
        </button>
      </div>
    </nav>
  );
}
