package com.vouchq.registry;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ScanResultRepository extends JpaRepository<ScanResult, UUID> {

    /** The scan for a given tool version (one per version at ingest time). */
    Optional<ScanResult> findFirstByOrgIdAndToolVersionIdOrderByScannedAtDesc(UUID orgId, UUID toolVersionId);
}
