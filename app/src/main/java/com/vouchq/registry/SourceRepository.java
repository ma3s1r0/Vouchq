package com.vouchq.registry;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SourceRepository extends JpaRepository<Source, UUID> {

    Optional<Source> findByOrgIdAndUri(UUID orgId, String uri);

    List<Source> findByOrgIdOrderByCreatedAtDesc(UUID orgId);

    Optional<Source> findByIdAndOrgId(UUID id, UUID orgId);
}
