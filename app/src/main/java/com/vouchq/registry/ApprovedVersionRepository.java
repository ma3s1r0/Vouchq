package com.vouchq.registry;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ApprovedVersionRepository extends JpaRepository<ApprovedVersion, UUID> {
}
