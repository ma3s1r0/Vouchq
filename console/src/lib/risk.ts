/**
 * Single source of truth for how a 0–100 risk score maps to a color (MA3-106).
 * Used by the inventory meter, findings panel, and approval cards so the risk
 * color always means the same thing. Bands mirror `riskBand()` in mock-inventory:
 *   clean 0–14 · info 15–39 · warn 40–69 · critical 70+
 * Crucially, a clean/low score is NOT red — the color must stay a trustworthy signal.
 */
export function riskColorVar(score: number): string {
  if (score >= 70) return "var(--color-crit)";
  if (score >= 40) return "var(--color-warn)";
  if (score >= 15) return "var(--color-muted)";
  return "var(--color-dim)";
}

/** Bar fill: solid band color, with a warn→crit gradient reserved for critical. */
export function riskFillVar(score: number): string {
  if (score >= 70) {
    return "linear-gradient(90deg, var(--color-warn), var(--color-crit))";
  }
  return riskColorVar(score);
}
