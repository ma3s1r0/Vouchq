package com.vouchq.notify;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for editable notification channels (MA3-92). Org scoping is also
 * enforced by the Hibernate {@code orgFilter}; the explicit {@code org_id}
 * predicates here keep finders safe even outside a filtered session.
 */
public interface NotificationChannelRepository extends JpaRepository<NotificationChannelEntity, UUID> {

    List<NotificationChannelEntity> findByOrgIdOrderByCreatedAtAsc(UUID orgId);

    List<NotificationChannelEntity> findByOrgIdAndEnabledTrueOrderByCreatedAtAsc(UUID orgId);

    Optional<NotificationChannelEntity> findByIdAndOrgId(UUID id, UUID orgId);

    long countByOrgId(UUID orgId);
}
