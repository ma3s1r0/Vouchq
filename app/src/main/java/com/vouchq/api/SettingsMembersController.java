package com.vouchq.api;

import com.vouchq.security.AppUser;
import com.vouchq.security.AppUserRepository;
import com.vouchq.tenancy.CurrentOrg;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Member management for an org (MA3-93, the backend the MA3-92 "members" tab was
 * deferred to). ADMIN-only — enforced in {@code com.vouchq.security.SecurityConfig}
 * for {@code /api/settings/members/**} (including the GET listing).
 *
 * <p>All operations are scoped to the calling admin's own org ({@link CurrentOrg}),
 * so an admin can never see or mutate another tenant's users. Passwords are stored
 * BCrypt-hashed and never returned.
 */
@RestController
@RequestMapping("/api/settings/members")
public class SettingsMembersController {

    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final CurrentOrg currentOrg;

    public SettingsMembersController(AppUserRepository users, PasswordEncoder encoder,
                                     CurrentOrg currentOrg) {
        this.users = users;
        this.encoder = encoder;
        this.currentOrg = currentOrg;
    }

    public record MemberView(UUID id, String email, String displayName, String role,
                             boolean active, OffsetDateTime createdAt) {
        static MemberView from(AppUser u) {
            return new MemberView(u.getId(), u.getEmail(), u.getDisplayName(),
                    u.getRole().name(), u.isActive(), u.getCreatedAt());
        }
    }

    /** Create: email + displayName + role required; password optional (settable later). */
    public record CreateMemberRequest(String email, String displayName, String role,
                                      String password) {}

    /** Patch: any field optional. password set/reset; active toggles deactivation. */
    public record UpdateMemberRequest(String displayName, String role, String password,
                                      Boolean active) {}

    @GetMapping
    public List<MemberView> list() {
        UUID orgId = currentOrg.require();
        return users.findByOrgIdOrderByCreatedAtAsc(orgId).stream()
                .map(MemberView::from)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MemberView create(@RequestBody CreateMemberRequest req) {
        UUID orgId = currentOrg.require();
        String email = require(req.email(), "email").toLowerCase();
        if (users.existsByEmailIgnoreCase(email)) {
            throw new IllegalArgumentException("A user with that email already exists: " + email);
        }
        AppUser.Role role = parseRole(req.role());
        String hash = (req.password() == null || req.password().isBlank())
                ? null : encoder.encode(req.password());
        AppUser saved = users.save(new AppUser(UUID.randomUUID(), orgId, email,
                trimOrNull(req.displayName()), role, hash));
        return MemberView.from(saved);
    }

    @PatchMapping("/{id}")
    public MemberView update(@PathVariable UUID id, @RequestBody UpdateMemberRequest req) {
        UUID orgId = currentOrg.require();
        AppUser user = load(id, orgId);
        if (req.displayName() != null) {
            user.setDisplayName(trimOrNull(req.displayName()));
        }
        if (req.role() != null) {
            user.setRole(parseRole(req.role()));
        }
        if (req.password() != null && !req.password().isBlank()) {
            user.setPasswordHash(encoder.encode(req.password()));
        }
        if (req.active() != null) {
            user.setActive(req.active());
        }
        return MemberView.from(users.save(user));
    }

    /** Deactivate (soft): keeps audit trail and avoids FK breakage. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(@PathVariable UUID id) {
        UUID orgId = currentOrg.require();
        AppUser user = load(id, orgId);
        user.setActive(false);
        users.save(user);
    }

    private AppUser load(UUID id, UUID orgId) {
        return users.findByIdAndOrgId(id, orgId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown member: " + id));
    }

    private static AppUser.Role parseRole(String role) {
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("role is required (ADMIN | MEMBER | VIEWER)");
        }
        try {
            return AppUser.Role.valueOf(role.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown role: " + role + " (ADMIN | MEMBER | VIEWER)");
        }
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String trimOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
