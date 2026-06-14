package com.vouchq.tenancy;

import com.vouchq.security.AppUserPrincipal;
import com.vouchq.security.AppUserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * {@link OrgResolver} that derives the tenant from the authenticated
 * {@code app_user} (MA3-93). Replaces the everyone→default mapping: each user
 * belongs to one org ({@code app_user.org_id}), and that is the org bound to the
 * request by {@link OrgContextFilter}, so the Hibernate {@code orgFilter} scopes
 * every query to the user's own tenant.
 *
 * <p>Fast path: a session/Basic login produces an {@link AppUserPrincipal} that
 * already carries the org id — no DB hit. Fallback: if the principal is some
 * other {@link Authentication} (e.g. a test {@code @WithMockUser}), look the user
 * up by name (email); empty if not found, leaving the request unscoped.
 */
@Component
public class AppUserOrgResolver implements OrgResolver {

    private final AppUserRepository users;

    public AppUserOrgResolver(AppUserRepository users) {
        this.users = users;
    }

    @Override
    public Optional<UUID> resolve(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof AppUserPrincipal appUser) {
            return Optional.of(appUser.getOrgId());
        }
        return users.findByEmailIgnoreCase(authentication.getName())
                .map(com.vouchq.security.AppUser::getOrgId);
    }
}
