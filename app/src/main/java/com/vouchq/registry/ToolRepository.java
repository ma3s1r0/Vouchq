package com.vouchq.registry;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ToolRepository extends JpaRepository<Tool, UUID> {

    Optional<Tool> findByOrgIdAndServerIdAndName(UUID orgId, UUID serverId, String name);

    Optional<Tool> findByIdAndOrgId(UUID id, UUID orgId);

    /** All tools for an org — used by the CI verify name/status lookup (MA3-98). */
    List<Tool> findByOrgId(UUID orgId);

    List<Tool> findByOrgIdAndServerIdIn(UUID orgId, List<UUID> serverIds);

    /**
     * Inventory listing with optional kind/status filters. A null filter matches
     * any value (so callers can mix and match). Newest-updated first.
     */
    @Query("""
            SELECT t FROM Tool t
            WHERE t.orgId = :orgId
              AND (:kind IS NULL OR t.kind = :kind)
              AND (:status IS NULL OR t.status = :status)
            ORDER BY t.updatedAt DESC
            """)
    List<Tool> search(@Param("orgId") UUID orgId,
                      @Param("kind") Tool.Kind kind,
                      @Param("status") Tool.Status status);

    /**
     * One page of the inventory with optional kind/status/name-query (MA3-118).
     * The name filter uses COALESCE(:q, t.name) so a null query binds with a
     * known type (Postgres rejects a bare untyped {@code :q IS NULL}) and reduces
     * to {@code name LIKE %name%} — always true.
     */
    @Query("""
            SELECT t FROM Tool t
            WHERE t.orgId = :orgId
              AND (:kind IS NULL OR t.kind = :kind)
              AND (:status IS NULL OR t.status = :status)
              AND LOWER(t.name) LIKE LOWER(CONCAT('%', COALESCE(:q, t.name), '%'))
            ORDER BY t.updatedAt DESC
            """)
    List<Tool> searchPage(@Param("orgId") UUID orgId,
                          @Param("kind") Tool.Kind kind,
                          @Param("status") Tool.Status status,
                          @Param("q") String q,
                          Pageable pageable);

    @Query("""
            SELECT COUNT(t) FROM Tool t
            WHERE t.orgId = :orgId
              AND (:kind IS NULL OR t.kind = :kind)
              AND (:status IS NULL OR t.status = :status)
              AND LOWER(t.name) LIKE LOWER(CONCAT('%', COALESCE(:q, t.name), '%'))
            """)
    long countSearch(@Param("orgId") UUID orgId,
                     @Param("kind") Tool.Kind kind,
                     @Param("status") Tool.Status status,
                     @Param("q") String q);

    long countByOrgId(UUID orgId);

    long countByOrgIdAndStatus(UUID orgId, Tool.Status status);
}
