package com.vouchq.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * {@link UserDetails} backed by an {@link AppUser} row (MA3-93). Carries the
 * {@code userId}/{@code orgId}/{@code role} so the {@code OrgResolver} can derive
 * the tenant straight from the authenticated principal with no extra DB hit, and
 * {@code GET /api/auth/me} can report the current user + org + role.
 *
 * <p>The single granted authority is {@code ROLE_<role>}, matching the RBAC
 * matrix in {@link SecurityConfig} (ADMIN/MEMBER/VIEWER). Username is the email.
 */
public class AppUserPrincipal implements UserDetails {

    private final UUID userId;
    private final UUID orgId;
    private final String email;
    private final String displayName;
    private final AppUser.Role role;
    private final String passwordHash;
    private final boolean active;

    public AppUserPrincipal(AppUser user) {
        this.userId = user.getId();
        this.orgId = user.getOrgId();
        this.email = user.getEmail();
        this.displayName = user.getDisplayName();
        this.role = user.getRole();
        this.passwordHash = user.getPasswordHash();
        this.active = user.isActive();
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public String getEmail() {
        return email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public AppUser.Role getRole() {
        return role;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return active && passwordHash != null;
    }
}
