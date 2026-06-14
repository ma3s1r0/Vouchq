package com.vouchq.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MA3-93 — the DB {@link DbUserDetailsService} loads an {@code app_user}, exposes
 * its org/role on the principal, and the stored BCrypt hash verifies against the
 * raw password.
 */
class DbUserDetailsServiceTest {

    private final AppUserRepository users = mock(AppUserRepository.class);
    private final DbUserDetailsService service = new DbUserDetailsService(users);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    @Test
    void loadsAppUser_withOrgRoleAndBcryptMatch() {
        UUID id = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        String hash = encoder.encode("s3cret");
        AppUser user = new AppUser(id, orgId, "admin@vouchq.local", "Admin",
                AppUser.Role.ADMIN, hash);
        when(users.findByEmailIgnoreCase("admin@vouchq.local")).thenReturn(Optional.of(user));

        UserDetails details = service.loadUserByUsername("admin@vouchq.local");

        assertThat(details).isInstanceOf(AppUserPrincipal.class);
        AppUserPrincipal principal = (AppUserPrincipal) details;
        assertThat(principal.getUserId()).isEqualTo(id);
        assertThat(principal.getOrgId()).isEqualTo(orgId);
        assertThat(principal.getRole()).isEqualTo(AppUser.Role.ADMIN);
        assertThat(principal.getAuthorities()).extracting("authority").containsExactly("ROLE_ADMIN");
        assertThat(encoder.matches("s3cret", principal.getPassword())).isTrue();
        assertThat(encoder.matches("wrong", principal.getPassword())).isFalse();
        assertThat(principal.isEnabled()).isTrue();
    }

    @Test
    void unknownEmail_throws() {
        when(users.findByEmailIgnoreCase("nobody@x")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.loadUserByUsername("nobody@x"))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void userWithoutPassword_isDisabled() {
        AppUser user = new AppUser(UUID.randomUUID(), UUID.randomUUID(), "new@x", "New",
                AppUser.Role.MEMBER, null);
        when(users.findByEmailIgnoreCase("new@x")).thenReturn(Optional.of(user));
        AppUserPrincipal principal = (AppUserPrincipal) service.loadUserByUsername("new@x");
        assertThat(principal.isEnabled()).isFalse();
    }
}
