package com.vouchq.security;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository over {@code app_user} (MA3-93). Login resolves by globally-unique
 * {@code email}; member management is org-scoped via the {@code orgId} finders.
 */
public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    Optional<AppUser> findByEmailIgnoreCase(String email);

    List<AppUser> findByOrgIdOrderByCreatedAtAsc(UUID orgId);

    Optional<AppUser> findByIdAndOrgId(UUID id, UUID orgId);

    boolean existsByEmailIgnoreCase(String email);

    long count();
}
