package com.vouchq.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A user account (기획서 §6 app_user, §10 인증/인가). Each user belongs to exactly
 * one organization ({@code orgId}) for the MVP — multi-org/SSO is Phase 2 (§12).
 *
 * <p><b>Not</b> org-filtered: authentication looks the user up by globally-unique
 * email <em>before</em> any org is bound, so the Hibernate {@code orgFilter} must
 * not apply here. Member-management queries scope by {@code orgId} explicitly via
 * {@code findByOrgId} / {@code findByIdAndOrgId} finders instead.
 */
@Entity
@Table(name = "app_user")
public class AppUser {

    /** Mirrors the {@code app_user.role} CHECK constraint (V2). */
    public enum Role {
        ADMIN,
        MEMBER,
        VIEWER
    }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "email", nullable = false, length = 320)
    private String email;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "role", nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private Role role;

    @Column(name = "password_hash", length = 100)
    private String passwordHash;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected AppUser() {}

    public AppUser(UUID id, UUID orgId, String email, String displayName, Role role,
                   String passwordHash) {
        this.id = id;
        this.orgId = orgId;
        this.email = email;
        this.displayName = displayName;
        this.role = role;
        this.passwordHash = passwordHash;
        this.active = true;
    }

    public UUID getId() {
        return id;
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

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
