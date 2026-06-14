package com.vouchq.registry;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RegisteredServerRepository extends JpaRepository<RegisteredServer, UUID> {

    Optional<RegisteredServer> findByOrgIdAndSourceIdAndName(UUID orgId, UUID sourceId, String name);

    List<RegisteredServer> findByOrgIdOrderByCreatedAtDesc(UUID orgId);

    List<RegisteredServer> findByOrgIdAndSourceId(UUID orgId, UUID sourceId);
}
