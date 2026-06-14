"use client";

import Link from "next/link";
import { useT } from "@/lib/i18n";

/**
 * Localized breadcrumb for a detail page: "<Inventory> / <leaf>" with a proper
 * <nav aria-label> landmark (MA3-129).
 */
export function DetailBreadcrumb({ leaf }: { leaf: string }) {
  const { t } = useT();
  return (
    <nav aria-label="Breadcrumb" className="mb-1 text-[12px] text-dim">
      <Link href="/inventory" className="hover:text-text hover:underline">
        {t("nav.inventory")}
      </Link>{" "}
      / <span className="font-mono text-muted">{leaf}</span>
    </nav>
  );
}
