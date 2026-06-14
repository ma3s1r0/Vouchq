package com.vouchq.retention;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the retention policy (MA3-97): old, unprotected versions (+ their
 * scans) are pruned, while the current version, every approved version, and the
 * N newest per tool are preserved — and the prune is recorded in the audit log.
 */
@SpringBootTest
@Testcontainers
@Transactional
class RetentionServiceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @PersistenceContext
    EntityManager em;

    @Autowired
    RetentionService retention;

    private static final String HASH = "0".repeat(64);

    @Test
    void prunesOldUnprotectedVersionsButKeepsCurrentApprovedAndRecent() {
        UUID org = uuid();
        exec("INSERT INTO organization (id, name, slug) VALUES (:id, 'Acme', :slug)",
                "id", org, "slug", "acme-" + org);
        UUID source = uuid();
        exec("INSERT INTO source (id, org_id, type, uri) VALUES (:id, :o, 'GIT_REPOSITORY', 'git://x')",
                "id", source, "o", org);
        UUID server = uuid();
        exec("INSERT INTO registered_server (id, org_id, source_id, kind, name) "
                        + "VALUES (:id, :o, :s, 'SKILL_BUNDLE', 'bundle')",
                "id", server, "o", org, "s", source);
        UUID tool = uuid();
        exec("INSERT INTO tool (id, org_id, server_id, kind, name, status) "
                        + "VALUES (:id, :o, :s, 'SKILL', 'planner', 'APPROVED')",
                "id", tool, "o", org, "s", server);

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID vOldA = version(org, tool, now.minusDays(200));   // old, unprotected → prune
        UUID vOldB = version(org, tool, now.minusDays(180));   // old, unprotected → prune
        UUID vApproved = version(org, tool, now.minusDays(190)); // old but approved → keep
        UUID vRecent2 = version(org, tool, now.minusDays(2));  // recent (top-2) → keep
        UUID vRecent1 = version(org, tool, now.minusDays(1));  // recent + current → keep

        // current version → protected
        exec("UPDATE tool SET current_version_id = :v WHERE id = :t", "v", vRecent1, "t", tool);
        // approved version → protected (references vApproved)
        exec("INSERT INTO approved_version (id, org_id, tool_id, tool_version_id, hash, approved_by) "
                        + "VALUES (:id, :o, :t, :v, :h, 'admin')",
                "id", uuid(), "o", org, "t", tool, "v", vApproved, "h", HASH);

        // a scan for each version
        for (UUID v : new UUID[]{vOldA, vOldB, vApproved, vRecent2, vRecent1}) {
            scan(org, v, now.minusDays(1));
        }

        // keepDays=30, minPerTool=2 → vRecent1/vRecent2 protected by recency,
        // vApproved by approval; vOldA/vOldB are >30d and unprotected.
        RetentionService.PruneResult r = retention.pruneOrg(org, 30, 2, "test");
        em.flush(); // ensure the audited entity is visible to the native count below

        assertThat(r.versionsPruned()).isEqualTo(2);
        assertThat(remainingVersionIds(org)).containsExactlyInAnyOrder(vApproved, vRecent2, vRecent1);
        assertThat(scanCountFor(vOldA)).isZero();
        assertThat(scanCountFor(vOldB)).isZero();
        assertThat(scanCountFor(vRecent1)).isEqualTo(1);
        // the prune is itself audited
        assertThat(count("SELECT count(*) FROM audit_log WHERE org_id = :o AND action = 'RETENTION_PRUNED'",
                "o", org)).isEqualTo(1L);

        // idempotent: a second pass with nothing old left prunes nothing.
        assertThat(retention.pruneOrg(org, 30, 2, "test").total()).isZero();
    }

    // ── helpers ──────────────────────────────────────────────────────────
    private UUID version(UUID org, UUID tool, OffsetDateTime observedAt) {
        UUID id = uuid();
        exec("INSERT INTO tool_version (id, org_id, tool_id, definition, hash, observed_at) "
                        + "VALUES (:id, :o, :t, '{}'::jsonb, :h, :ts)",
                "id", id, "o", org, "t", tool, "h", HASH, "ts", observedAt);
        return id;
    }

    private void scan(UUID org, UUID versionId, OffsetDateTime at) {
        exec("INSERT INTO scan_result (id, org_id, tool_version_id, risk_score, findings, scanned_at) "
                        + "VALUES (:id, :o, :v, 0, '[]'::jsonb, :ts)",
                "id", uuid(), "o", org, "v", versionId, "ts", at);
    }

    @SuppressWarnings("unchecked")
    private java.util.List<UUID> remainingVersionIds(UUID org) {
        return em.createNativeQuery("SELECT id FROM tool_version WHERE org_id = :o")
                .setParameter("o", org).getResultList();
    }

    private long scanCountFor(UUID versionId) {
        return count("SELECT count(*) FROM scan_result WHERE tool_version_id = :v", "v", versionId);
    }

    private long count(String sql, Object... kv) {
        var q = em.createNativeQuery(sql);
        for (int i = 0; i < kv.length; i += 2) q.setParameter((String) kv[i], kv[i + 1]);
        return ((Number) q.getSingleResult()).longValue();
    }

    private void exec(String sql, Object... kv) {
        var q = em.createNativeQuery(sql);
        for (int i = 0; i < kv.length; i += 2) q.setParameter((String) kv[i], kv[i + 1]);
        q.executeUpdate();
    }

    private static UUID uuid() {
        return UUID.randomUUID();
    }
}
