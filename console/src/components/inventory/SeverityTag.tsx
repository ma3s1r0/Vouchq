import type { Severity } from "@/lib/mock-tool-detail";

/**
 * Severity tag. Faithful to design-system/components/severity-tag.html.
 * `short` renders the compact CRIT label used in approval-queue findings.
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

export function SeverityTag({
  severity,
  short = false,
}: {
  severity: Severity;
  short?: boolean;
}) {
  return (
    <span
      className={`inline-flex items-center rounded-[5px] px-[7px] py-[2px] font-mono text-[10px] font-bold tracking-[0.06em] ${STYLE[severity]}`}
    >
      {short ? SHORT[severity] : severity}
    </span>
  );
}
