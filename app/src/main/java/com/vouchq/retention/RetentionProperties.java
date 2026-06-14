package com.vouchq.retention;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Data-retention / pruning policy (MA3-97, 기획서 §6 lifecycle).
 *
 * <p>The registry accumulates a {@code tool_version} row on every observed
 * definition change and a {@code scan_result} per scan; with scheduled re-scans
 * (MA3-85) running indefinitely, that history grows without bound and slows the
 * inventory/audit queries (cf. MA3-118). This policy trims <em>superseded</em>
 * versions and their scans while always preserving what the product needs.
 *
 * <h2>Always protected (never pruned)</h2>
 * <ul>
 *   <li>each tool's <b>current</b> version ({@code tool.current_version_id});</li>
 *   <li>any version that is or was <b>approved/pinned</b> (referenced by an
 *       {@code approved_version} row) — needed for install/박제 export and as the
 *       drift baseline;</li>
 *   <li>the most recent {@link #toolVersionMinPerTool} versions of each tool,
 *       regardless of age (recent history for drift context);</li>
 *   <li>the <b>audit log</b> — WORM/append-only, out of scope entirely.</li>
 * </ul>
 *
 * <h2>Config shape</h2>
 * <pre>
 * vouchq:
 *   retention:
 *     enabled: false              # opt-in (like rescan) — off = no background deletes
 *     tool-version-keep-days: 90  # prune superseded versions older than this
 *     tool-version-min-per-tool: 20  # but always keep at least this many newest per tool
 *     interval-ms: 86400000       # run cadence (default daily)
 *     initial-delay-ms: 300000    # first run delay after boot
 * </pre>
 *
 * <p>Disabled by default so a quiet self-hosted instance does no background work
 * and never deletes data unless the operator opts in (기획서 §7).
 */
@ConfigurationProperties(prefix = "vouchq.retention")
public class RetentionProperties {

    private boolean enabled = false;
    private int toolVersionKeepDays = 90;
    private int toolVersionMinPerTool = 20;
    private long intervalMs = 86_400_000L;     // 24h
    private long initialDelayMs = 300_000L;    // 5m

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getToolVersionKeepDays() {
        return toolVersionKeepDays;
    }

    public void setToolVersionKeepDays(int toolVersionKeepDays) {
        this.toolVersionKeepDays = toolVersionKeepDays;
    }

    public int getToolVersionMinPerTool() {
        return toolVersionMinPerTool;
    }

    public void setToolVersionMinPerTool(int toolVersionMinPerTool) {
        this.toolVersionMinPerTool = toolVersionMinPerTool;
    }

    public long getIntervalMs() {
        return intervalMs;
    }

    public void setIntervalMs(long intervalMs) {
        this.intervalMs = intervalMs;
    }

    public long getInitialDelayMs() {
        return initialDelayMs;
    }

    public void setInitialDelayMs(long initialDelayMs) {
        this.initialDelayMs = initialDelayMs;
    }
}
