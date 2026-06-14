package com.vouchq.retention;

import com.vouchq.audit.AuditLogService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Prunes superseded {@code tool_version} / {@code scan_result} rows per the
 * retention policy (MA3-97 — see {@link RetentionProperties} for what is always
 * protected). Pure set-based native SQL (no IDs round-tripped into memory), in
 * FK-safe order, org-scoped, and audited. The audit log itself is never touched.
 *
 * <p>A {@code tool_version} is <b>prunable</b> for an org when it is:
 * older than the keep-days cutoff, AND not a tool's {@code current_version}, AND
 * not referenced by any {@code approved_version}, AND not among the N most-recent
 * versions of its tool. Its {@code scan_result}s are deleted first (the only FK
 * into {@code tool_version}); {@code drift_event} stores hashes + a self-contained
 * diff, so it has no FK to versions and is left intact.
 */
@Service
public class RetentionService {

    @PersistenceContext
    private EntityManager em;

    private final AuditLogService audit;

    public RetentionService(AuditLogService audit) {
        this.audit = audit;
    }

    /** Result of one prune pass for an org. */
    public record PruneResult(int versionsPruned, int scansPruned) {
        public int total() {
            return versionsPruned + scansPruned;
        }
    }

    // Selects the prunable tool_version ids for :o given :cutoff and :minPerTool.
    // Reused by the scan-delete and version-delete steps so they stay in sync.
    private static final String PRUNABLE_VERSIONS =
            "SELECT tv.id FROM tool_version tv "
            + "WHERE tv.org_id = :o "
            + "  AND tv.observed_at < :cutoff "
            + "  AND tv.id NOT IN (SELECT current_version_id FROM tool "
            + "                    WHERE org_id = :o AND current_version_id IS NOT NULL) "
            + "  AND tv.id NOT IN (SELECT tool_version_id FROM approved_version WHERE org_id = :o) "
            + "  AND tv.id NOT IN (SELECT id FROM ("
            + "        SELECT id, row_number() OVER (PARTITION BY tool_id ORDER BY observed_at DESC) rn "
            + "        FROM tool_version WHERE org_id = :o) r WHERE r.rn <= :minPerTool)";

    /**
     * Prune one org's superseded versions/scans.
     *
     * @param keepDays   prune versions older than now − keepDays
     * @param minPerTool always keep at least this many newest versions per tool
     */
    @Transactional
    public PruneResult pruneOrg(UUID orgId, int keepDays, int minPerTool, String actor) {
        OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC).minusDays(keepDays);

        // 1) scans of the versions we're about to delete (the FK into tool_version).
        int scansOfPruned = em.createNativeQuery(
                        "DELETE FROM scan_result WHERE org_id = :o "
                        + "AND tool_version_id IN (" + PRUNABLE_VERSIONS + ")")
                .setParameter("o", orgId)
                .setParameter("cutoff", cutoff)
                .setParameter("minPerTool", minPerTool)
                .executeUpdate();

        // 2) the prunable versions themselves.
        int versionsPruned = em.createNativeQuery(
                        "DELETE FROM tool_version WHERE id IN (" + PRUNABLE_VERSIONS + ")")
                .setParameter("o", orgId)
                .setParameter("cutoff", cutoff)
                .setParameter("minPerTool", minPerTool)
                .executeUpdate();

        // 3) dedupe scan history of RETAINED versions: keep the latest scan per
        //    version, drop older ones beyond the cutoff. (scan_result has no inbound
        //    FK, so this is always safe.)
        int scansDeduped = em.createNativeQuery(
                        "DELETE FROM scan_result WHERE org_id = :o AND scanned_at < :cutoff "
                        + "AND id NOT IN (SELECT id FROM ("
                        + "    SELECT id, row_number() OVER (PARTITION BY tool_version_id "
                        + "        ORDER BY scanned_at DESC) rn FROM scan_result WHERE org_id = :o"
                        + ") s WHERE s.rn = 1)")
                .setParameter("o", orgId)
                .setParameter("cutoff", cutoff)
                .executeUpdate();

        PruneResult result = new PruneResult(versionsPruned, scansOfPruned + scansDeduped);

        // Record the prune in the WORM log so retention is itself auditable.
        if (result.total() > 0) {
            audit.append(orgId, actor == null ? "system" : actor, "RETENTION_PRUNED", null,
                    "{\"versionsPruned\":" + result.versionsPruned()
                    + ",\"scansPruned\":" + result.scansPruned()
                    + ",\"keepDays\":" + keepDays
                    + ",\"minPerTool\":" + minPerTool + "}");
        }
        return result;
    }
}
