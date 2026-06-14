package com.vouchq.audit;

import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /** Audit log for an org, most recent first. */
    List<AuditLog> findByOrgIdOrderByIdDesc(UUID orgId, Limit limit);

    /** One page of the org's log (most recent first), with optional filters (MA3-118). */
    // Filters use COALESCE against the (NOT NULL) column rather than the
    // `(:p IS NULL OR ...)` idiom: a bare untyped `:p IS NULL` makes Postgres
    // fail with "could not determine data type of parameter" for null String/
    // timestamp binds. COALESCE(:p, col) gives the bind its type from the column,
    // and when :p is null reduces to `col = col` / `col >= col` (always true).
    @Query("SELECT a FROM AuditLog a WHERE a.orgId = :orgId "
            + "AND a.action = COALESCE(:action, a.action) "
            + "AND a.actor = COALESCE(:actor, a.actor) "
            + "AND a.createdAt >= COALESCE(:since, a.createdAt) "
            + "ORDER BY a.id DESC")
    List<AuditLog> findPage(@Param("orgId") UUID orgId, @Param("action") String action,
            @Param("actor") String actor, @Param("since") OffsetDateTime since, Pageable pageable);

    /** Total matching the same filters, for the X-Total-Count header. */
    @Query("SELECT COUNT(a) FROM AuditLog a WHERE a.orgId = :orgId "
            + "AND a.action = COALESCE(:action, a.action) "
            + "AND a.actor = COALESCE(:actor, a.actor) "
            + "AND a.createdAt >= COALESCE(:since, a.createdAt)")
    long countPage(@Param("orgId") UUID orgId, @Param("action") String action,
            @Param("actor") String actor, @Param("since") OffsetDateTime since);

    /** The chain tip for an org (highest id) — its entry_hash is the next prev_hash. */
    Optional<AuditLog> findFirstByOrgIdOrderByIdDesc(UUID orgId);

    /** Full chain for an org in insertion order, for {@code verifyChain}. */
    List<AuditLog> findByOrgIdOrderByIdAsc(UUID orgId);
}
