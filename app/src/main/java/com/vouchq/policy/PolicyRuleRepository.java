package com.vouchq.policy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for editable policy rules (MA3-92). Org scoping is also enforced by
 * the Hibernate {@code orgFilter}; the explicit {@code org_id} predicates keep
 * finders safe even outside a filtered session. Enabled rules are returned in
 * evaluation order (ascending priority, then creation order as a stable tiebreak).
 */
public interface PolicyRuleRepository extends JpaRepository<PolicyRule, UUID> {

    List<PolicyRule> findByOrgIdOrderByPriorityAscCreatedAtAsc(UUID orgId);

    List<PolicyRule> findByOrgIdAndEnabledTrueOrderByPriorityAscCreatedAtAsc(UUID orgId);

    Optional<PolicyRule> findByIdAndOrgId(UUID id, UUID orgId);

    long countByOrgId(UUID orgId);
}
