package com.vouchq.registry;

import com.vouchq.audit.AuditLogService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Deletes a source and everything ingested from it — its registered servers,
 * tools, versions, scans, approvals, drift events, and suppressions — in
 * FK-safe order (the {@code tool ↔ tool_version} references are a cycle, so the
 * tool's {@code current/approved_version_id} are nulled first). Org-scoped and
 * audited. There is no DB cascade by design, so the order here is the contract.
 */
@Service
public class SourceDeletionService {

    @PersistenceContext
    private EntityManager em;

    private final SourceRepository sources;
    private final AuditLogService audit;

    public SourceDeletionService(SourceRepository sources, AuditLogService audit) {
        this.sources = sources;
        this.audit = audit;
    }

    @Transactional
    public void delete(UUID orgId, UUID sourceId, String actor) {
        Source source = sources.findByIdAndOrgId(sourceId, orgId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown source: " + sourceId));

        List<UUID> serverIds = ids(
                "SELECT id FROM registered_server WHERE source_id = :s AND org_id = :o",
                "s", sourceId, "o", orgId);
        List<UUID> toolIds = serverIds.isEmpty() ? List.of() : idsIn(
                "SELECT id FROM tool WHERE server_id IN (:ids) AND org_id = :o", serverIds, orgId);
        List<UUID> tvIds = toolIds.isEmpty() ? List.of() : idsIn(
                "SELECT id FROM tool_version WHERE tool_id IN (:ids) AND org_id = :o", toolIds, orgId);

        if (!toolIds.isEmpty()) {
            // Break the tool → tool_version / approved_version references first.
            execIn("UPDATE tool SET current_version_id = NULL, approved_version_id = NULL "
                    + "WHERE id IN (:ids)", toolIds);
        }
        if (!tvIds.isEmpty()) {
            execIn("DELETE FROM scan_result WHERE tool_version_id IN (:ids)", tvIds);
            execIn("DELETE FROM approved_version WHERE tool_version_id IN (:ids)", tvIds);
        }
        if (!toolIds.isEmpty()) {
            execIn("DELETE FROM drift_event WHERE tool_id IN (:ids)", toolIds);
            execIn("DELETE FROM finding_suppression WHERE tool_id IN (:ids)", toolIds);
            execIn("DELETE FROM tool_version WHERE tool_id IN (:ids)", toolIds);
            execIn("DELETE FROM tool WHERE id IN (:ids)", toolIds);
        }
        if (!serverIds.isEmpty()) {
            execIn("DELETE FROM registered_server WHERE id IN (:ids)", serverIds);
        }
        em.createNativeQuery("DELETE FROM source WHERE id = :s AND org_id = :o")
                .setParameter("s", sourceId).setParameter("o", orgId).executeUpdate();

        String uri = source.getUri() == null ? "" : source.getUri().replace("\\", "\\\\").replace("\"", "\\\"");
        audit.append(orgId, actor == null ? "system" : actor, "SOURCE_DELETED", sourceId,
                "{\"uri\":\"" + uri + "\",\"toolsRemoved\":" + toolIds.size() + "}");
    }

    @SuppressWarnings("unchecked")
    private List<UUID> ids(String sql, String k1, Object v1, String k2, Object v2) {
        return em.createNativeQuery(sql).setParameter(k1, v1).setParameter(k2, v2).getResultList();
    }

    @SuppressWarnings("unchecked")
    private List<UUID> idsIn(String sql, List<UUID> ids, UUID orgId) {
        return em.createNativeQuery(sql).setParameter("ids", ids).setParameter("o", orgId).getResultList();
    }

    private void execIn(String sql, List<UUID> ids) {
        em.createNativeQuery(sql).setParameter("ids", ids).executeUpdate();
    }
}
