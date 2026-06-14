package com.vouchq.registry;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DriftEventRepository extends JpaRepository<DriftEvent, UUID> {

    /**
     * Idempotency guard: an unresolved event already recording this exact
     * approved→observed hash transition for the tool. Re-scanning an unchanged
     * drift must not create a duplicate.
     */
    Optional<DriftEvent> findFirstByOrgIdAndToolIdAndApprovedHashAndObservedHashAndResolvedFalse(
            UUID orgId, UUID toolId, String approvedHash, String observedHash);

    Optional<DriftEvent> findByIdAndOrgId(UUID id, UUID orgId);

    List<DriftEvent> findByOrgIdOrderByDetectedAtDesc(UUID orgId);

    List<DriftEvent> findByOrgIdAndResolvedOrderByDetectedAtDesc(UUID orgId, boolean resolved);

    List<DriftEvent> findByOrgIdAndToolIdAndResolvedFalse(UUID orgId, UUID toolId);

    long countByOrgIdAndResolvedFalseAndSeverity(UUID orgId, DriftEvent.Severity severity);
}
