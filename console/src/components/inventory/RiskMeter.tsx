import { riskColorVar, riskFillVar } from "@/lib/risk";

/**
 * Inline risk meter (score + bar). Color shifts with the score via the shared
 * risk palette (MA3-106) so clean/low scores read calm and only high scores alarm.
 */
export function RiskMeter({ risk }: { risk: number }) {
  return (
    <div
      className="flex items-center gap-2 font-mono text-[12px]"
      role="img"
      aria-label={`Risk score ${risk} of 100`}
    >
      <span style={{ color: riskColorVar(risk) }}>{risk}</span>
      <span
        aria-hidden
        className="h-[5px] w-[46px] overflow-hidden rounded-full border border-border bg-bg"
      >
        <span
          className="block h-full"
          style={{ width: `${risk}%`, background: riskFillVar(risk) }}
        />
      </span>
    </div>
  );
}
