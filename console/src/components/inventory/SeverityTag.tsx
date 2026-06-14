"use client";

import type { Severity } from "@/lib/mock-tool-detail";
import { useT } from "@/lib/i18n";

/**
 * Severity tag. Faithful to design-system/components/severity-tag.html.
 * `short` renders the compact CRIT token (kept as a technical token, untranslated);
 * the long form is localized (MA3-128).
 */
const STYLE: Record<Severity, string> = {
  CRITICAL: "bg-crit/[0.14] text-crit",
  WARN: "bg-warn/[0.14] text-warn",
  INFO: "bg-primary/[0.13] text-primary",
};

const SHORT: Record<Severity, string> = {
  CRITICAL: "CRIT",
  WARN: "WARN",
  INFO: "INFO",
};

const LONG_KEY: Record<Severity, string> = {
  CRITICAL: "findings.critical",
  WARN: "findings.warn",
  INFO: "findings.info",
};

export function SeverityTag({
  severity,
  short = false,
}: {
  severity: Severity;
  short?: boolean;
}) {
  const { t } = useT();
  return (
    <span
      className={`inline-flex items-center rounded-[5px] px-[7px] py-[2px] font-mono text-[10px] font-bold tracking-[0.06em] ${STYLE[severity]}`}
    >
      {short ? SHORT[severity] : t(LONG_KEY[severity])}
    </span>
  );
}
