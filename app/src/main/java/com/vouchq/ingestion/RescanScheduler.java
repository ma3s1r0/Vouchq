package com.vouchq.ingestion;

import com.vouchq.registry.Organization;
import com.vouchq.registry.OrganizationRepository;
import com.vouchq.registry.Source;
import com.vouchq.registry.SourceRepository;
import com.vouchq.tenancy.CurrentOrgContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scheduled re-scan job (MA3-85, 기획서 §5.2 "스케줄 재스캔"). Periodically, for every
 * organization, re-ingests each re-fetchable source — {@code GIT_REPOSITORY}
 * (re-clone) and {@code MCP_SERVER} (re-call {@code tools/list}, MA3-90) — and
 * drift-scans its pinned tools, delegating entirely to {@link RescanService} (no
 * duplicated logic). MCP coverage is what gives rug-pull detection (기획서 §2.2) its
 * teeth: an MCP server can mutate a tool definition after approval, and only a
 * scheduled re-fetch catches it.
 *
 * <h2>Interval</h2>
 * Driven by {@code @Scheduled(fixedDelayString = "${vouchq.rescan.interval-ms}")}
 * with a sane default of 1 hour. {@code fixedDelay} (not {@code fixedRate}) means
 * the next run is scheduled only after the previous one finishes, so a long scan
 * can never stack up on itself.
 *
 * <h2>Enablement (기획서 §7)</h2>
 * The bean exists only when {@code vouchq.rescan.enabled=true}. The default
 * ({@code false}) keeps a quiet self-hosted instance from doing background work —
 * and, combined with the default-off notify channels, guarantees zero outbound
 * traffic out of the box.
 *
 * <h2>Org binding</h2>
 * A scheduled run executes outside any HTTP request, so the tenant filter is not
 * active by default. Each org's work is wrapped in
 * {@link CurrentOrgContext#runAs(UUID, Runnable)} so Hibernate's {@code orgFilter}
 * is enabled for that org's queries.
 *
 * <h2>Overlap guard</h2>
 * Single-threaded scheduling already serializes runs; an {@link AtomicBoolean}
 * adds belt-and-suspenders protection (and would matter if the pool were ever
 * widened), skipping a tick if a previous run is somehow still in flight.
 */
@Component
@ConditionalOnProperty(prefix = "vouchq.rescan", name = "enabled", havingValue = "true")
public class RescanScheduler {

    private static final Logger log = LoggerFactory.getLogger(RescanScheduler.class);

    private final OrganizationRepository organizations;
    private final SourceRepository sources;
    private final RescanService rescan;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public RescanScheduler(OrganizationRepository organizations,
                           SourceRepository sources,
                           RescanService rescan) {
        this.organizations = organizations;
        this.sources = sources;
        this.rescan = rescan;
    }

    @Scheduled(
            fixedDelayString = "${vouchq.rescan.interval-ms:3600000}",
            initialDelayString = "${vouchq.rescan.initial-delay-ms:60000}")
    public void runScheduledRescan() {
        if (!running.compareAndSet(false, true)) {
            log.info("Scheduled re-scan skipped: a previous run is still in flight.");
            return;
        }
        try {
            rescanAllOrgs();
        } finally {
            running.set(false);
        }
    }

    /** Visible for testing: the actual work, independent of the overlap latch. */
    void rescanAllOrgs() {
        List<Organization> orgs = organizations.findAll();
        log.info("Scheduled re-scan starting for {} organization(s).", orgs.size());
        for (Organization org : orgs) {
            UUID orgId = org.getId();
            // Bind the tenant so orgFilter is active for this org's queries.
            CurrentOrgContext.runAs(orgId, () -> rescanOrg(orgId));
        }
    }

    private void rescanOrg(UUID orgId) {
        // Re-fetchable sources only: Git repos (re-clone) and MCP servers
        // (re-call tools/list). FILE_UPLOAD has no live origin to re-fetch.
        List<Source> rescannable = sources.findByOrgIdOrderByCreatedAtDesc(orgId).stream()
                .filter(s -> s.getType() == Source.Type.GIT_REPOSITORY
                        || s.getType() == Source.Type.MCP_SERVER)
                .toList();
        for (Source source : rescannable) {
            try {
                RescanService.RescanResult result = rescan.rescanSource(orgId, source);
                log.info("Scheduled re-scan org={} source={}: scanned={} drifted={}",
                        orgId, source.getId(), result.scanned(), result.detected());
            } catch (RuntimeException e) {
                // One bad source (e.g. an unreachable repo) must not abort the rest.
                log.warn("Scheduled re-scan failed for org={} source={}: {}",
                        orgId, source.getId(), e.toString());
            }
        }
    }
}
