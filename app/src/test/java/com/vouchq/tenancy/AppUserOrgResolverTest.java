package com.vouchq.tenancy;

import com.vouchq.security.AppUser;
import com.vouchq.security.AppUserPrincipal;
import com.vouchq.security.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MA3-93 — the org is derived from the authenticated {@code app_user}, not a
 * single default. An {@link AppUserPrincipal} resolves straight off its org id
 * (no DB hit); an unauthenticated request resolves to empty.
 */
class AppUserOrgResolverTest {

    private final AppUserRepository users = mock(AppUserRepository.class);
    private final AppUserOrgResolver resolver = new AppUserOrgResolver(users);

    @Test
    void resolvesOrgFromAppUserPrincipal() {
        UUID orgId = UUID.randomUUID();
        AppUser user = new AppUser(UUID.randomUUID(), orgId, "u@x", "U", AppUser.Role.MEMBER, "h");
        AppUserPrincipal principal = new AppUserPrincipal(user);
        Authentication auth = new UsernamePasswordAuthenticationToken(
                principal, "h", List.of());

        assertThat(resolver.resolve(auth)).contains(orgId);
    }

    @Test
    void fallsBackToDbLookupByName() {
        UUID orgId = UUID.randomUUID();
        AppUser user = new AppUser(UUID.randomUUID(), orgId, "u@x", "U", AppUser.Role.MEMBER, "h");
        when(users.findByEmailIgnoreCase("u@x")).thenReturn(Optional.of(user));
        Authentication auth = new UsernamePasswordAuthenticationToken("u@x", "h", List.of());

        assertThat(resolver.resolve(auth)).contains(orgId);
    }

    @Test
    void nullAuth_isEmpty() {
        assertThat(resolver.resolve(null)).isEmpty();
    }
}
