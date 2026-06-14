package com.vouchq.retention;

import com.vouchq.registry.Organization;
import com.vouchq.registry.OrganizationRepository;
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
 * Scheduled retention/pruning job (MA3-97). Periodically, for every org, trims
 * superseded {@code tool_version}/{@code scan_result} rows via {@link
 * RetentionService} so the registry doesn't grow without bound.
 *
 * <p>Mirrors {@code RescanScheduler}: the bean exists only when {@code
 * vouchq.retention.enabled=true} (default off → no background deletes), uses
 * {@code fixedDelay} so runs never overlap, binds each org via
 * {@link CurrentOrgContext#runAs} (no HTTP request → tenant filter off by
 * default), and an {@link AtomicBoolean} guards against re-entrancy.
 */
@Component
@ConditionalOnProperty(prefix = "vouchq.retention", name = "enabled", havingValue = "true")
public class RetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(RetentionScheduler.class);

    private final OrganizationRepository organizations;
    private final RetentionService retention;
    private final RetentionProperties props;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public RetentionScheduler(OrganizationRepository organizations,
                              RetentionService retention,
                              RetentionProperties props) {
        this.organizations = organizations;
        this.retention = retention;
        this.props = props;
    }

    @Scheduled(
            fixedDelayString = "${vouchq.retention.interval-ms:86400000}",
            initialDelayString = "${vouchq.retention.initial-delay-ms:300000}")
    public void runScheduledPrune() {
        if (!running.compareAndSet(false, true)) {
            log.info("Scheduled retention skipped: a previous run is still in flight.");
            return;
        }
        try {
            pruneAllOrgs();
        } finally {
            running.set(false);
        }
    }

    /** Visible for testing: the actual work, independent of the overlap latch. */
    void pruneAllOrgs() {
        int keepDays = props.getToolVersionKeepDays();
        int minPerTool = props.getToolVersionMinPerTool();
        List<Organization> orgs = organizations.findAll();
        log.info("Scheduled retention starting for {} org(s) (keepDays={}, minPerTool={}).",
                orgs.size(), keepDays, minPerTool);
        for (Organization org : orgs) {
            UUID orgId = org.getId();
            try {
                CurrentOrgContext.runAs(orgId, () -> {
                    RetentionService.PruneResult r =
                            retention.pruneOrg(orgId, keepDays, minPerTool, "system");
                    if (r.total() > 0) {
                        log.info("Retention org={}: pruned {} version(s), {} scan(s).",
                                orgId, r.versionsPruned(), r.scansPruned());
                    }
                });
            } catch (RuntimeException e) {
                // One org's failure must not abort the rest.
                log.warn("Scheduled retention failed for org={}: {}", orgId, e.toString());
            }
        }
    }
}
