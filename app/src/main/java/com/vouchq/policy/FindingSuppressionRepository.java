package com.vouchq.policy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for false-positive suppressions (MA3-94). Org scoping is also
 * enforced by the Hibernate {@code orgFilter}; the explicit {@code org_id}
 * predicates keep finders safe even outside a filtered session.
 */
public interface FindingSuppressionRepository extends JpaRepository<FindingSuppression, UUID> {

    List<FindingSuppression> findByOrgIdOrderByCreatedAtDesc(UUID orgId);

    List<FindingSuppression> findByOrgId(UUID orgId);

    Optional<FindingSuppression> findByIdAndOrgId(UUID id, UUID orgId);
}
