"use client";

import type { ReactNode } from "react";
import { useT } from "@/lib/i18n";

/**
 * Translated page heading (MA3-107). Pages are server components, so this small
 * client component drives the <h1> (and optional subtitle) through the i18n
 * dictionary. Pass a static `subtitleKey` or a dynamic `subtitle` node.
 */
export function PageHeading({
  titleKey,
  subtitleKey,
  subtitle,
}: {
  titleKey: string;
  subtitleKey?: string;
  subtitle?: ReactNode;
}) {
  const { t } = useT();
  const sub = subtitle ?? (subtitleKey ? t(subtitleKey) : null);
  return (
    <>
      <h1 className="text-[20px] font-semibold">{t(titleKey)}</h1>
      {sub != null && (
        <p className="mb-[18px] mt-1 text-[13px] text-muted">{sub}</p>
      )}
    </>
  );
}

/**
 * Translated subtitle for pages that embed a dynamic count.
 * Use as the `subtitle` prop of PageHeading from server components.
 * e.g. <PageHeading subtitle={<ApprovalsSubtitle count={n} />} />
 */
export function ApprovalsSubtitle({ count }: { count: number }) {
  const { t } = useT();
  return (
    <>
      <b className="text-text">{count}</b> {t("page.approvals.subtitle")}
    </>
  );
}

export function DriftSubtitle({ count }: { count: number }) {
  const { t } = useT();
  return (
    <>
      <b className="text-text">{count}</b> {t("page.drift.subtitle")}
    </>
  );
}

export function AuditSubtitle() {
  const { t } = useT();
  return <>{t("page.audit.subtitle")}</>;
}
