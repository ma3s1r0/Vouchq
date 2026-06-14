package com.vouchq.tenancy;

import org.springframework.security.core.Authentication;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the organization for an authenticated principal (MA3-70). Kept as an
 * interface so the mapping can evolve (a per-user / per-membership lookup over
 * the {@code app_user} table) without touching {@link OrgContextFilter}.
 *
 * <p>MVP behaviour ({@link DefaultOrgResolver}): the in-memory HTTP Basic users
 * (admin/member/viewer) all map to the single default org. The mechanism —
 * resolve-per-request then bind to {@link CurrentOrgContext} — is what matters;
 * multi-org-per-user mapping lands later.
 */
public interface OrgResolver {

    /** The org for this authentication, or empty if it cannot be resolved. */
    Optional<UUID> resolve(Authentication authentication);
}
