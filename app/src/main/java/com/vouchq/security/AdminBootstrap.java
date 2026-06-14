package com.vouchq.security;

import com.vouchq.ingestion.DefaultOrganization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * First-boot admin seed (MA3-93) — the no-lockout guarantee. The switch from the
 * in-memory store to DB-backed auth means an empty {@code app_user} table would
 * lock everyone out. So on the very first boot, if no users exist, this seeds a
 * single ADMIN into the default org from env:
 *
 * <pre>
 *   VOUCHQ_ADMIN_EMAIL     (default admin@vouchq.local)
 *   VOUCHQ_ADMIN_PASSWORD  (default admin — logged with a prominent WARN)
 * </pre>
 *
 * Once any user exists this is a no-op, so it never clobbers a real admin or
 * resets a password. Runs before {@link com.vouchq.policy.SettingsSeeder} so the
 * default org exists for both.
 */
@Component
@Order(50)
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final AppUserRepository users;
    private final DefaultOrganization defaultOrg;
    private final PasswordEncoder encoder;
    private final String adminEmail;
    private final String adminPassword;

    public AdminBootstrap(AppUserRepository users,
                          DefaultOrganization defaultOrg,
                          PasswordEncoder encoder,
                          @Value("${vouchq.security.bootstrap.admin-email:${VOUCHQ_ADMIN_EMAIL:admin@vouchq.local}}")
                          String adminEmail,
                          @Value("${vouchq.security.bootstrap.admin-password:${VOUCHQ_ADMIN_PASSWORD:admin}}")
                          String adminPassword) {
        this.users = users;
        this.defaultOrg = defaultOrg;
        this.encoder = encoder;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.count() > 0) {
            return; // Real users already exist — never touch them.
        }
        UUID orgId = defaultOrg.ensure();
        users.save(new AppUser(UUID.randomUUID(), orgId, adminEmail, "Administrator",
                AppUser.Role.ADMIN, encoder.encode(adminPassword)));

        boolean defaultPassword = "admin".equals(adminPassword);
        if (defaultPassword) {
            log.warn("==========================================================================");
            log.warn("BOOTSTRAP ADMIN SEEDED with the DEFAULT password (email={}, password=admin).", adminEmail);
            log.warn("Set VOUCHQ_ADMIN_EMAIL / VOUCHQ_ADMIN_PASSWORD before any real deployment,");
            log.warn("or change it immediately via PATCH /api/settings/members/{id}.");
            log.warn("==========================================================================");
        } else {
            log.info("Bootstrap admin seeded (email={}) into the default org.", adminEmail);
        }
    }
}
