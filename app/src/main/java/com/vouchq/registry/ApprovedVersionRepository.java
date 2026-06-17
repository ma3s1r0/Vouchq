package com.vouchq.registry;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ApprovedVersionRepository extends JpaRepository<ApprovedVersion, UUID> {

    /** All approved (pinned) versions for an org — the verify hash set (MA3-98). */
    List<ApprovedVersion> findByOrgId(UUID orgId);
}
