package com.vouchq.registry;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ToolVersionRepository extends JpaRepository<ToolVersion, UUID> {

    /** Version history for a tool, newest observation first. */
    List<ToolVersion> findByOrgIdAndToolIdOrderByObservedAtDesc(UUID orgId, UUID toolId);
}
