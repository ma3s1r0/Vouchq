package com.vouchq.security;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DB-backed {@link UserDetailsService} over {@code app_user} (MA3-93). Replaces
 * the in-memory store: users, BCrypt password hashes, and ADMIN/MEMBER/VIEWER
 * roles all live in the database. Backs both the console session login and HTTP
 * Basic (Spring Security uses one provider for both).
 *
 * <p>Lookup is by email (the login username), globally unique in this MVP. The
 * returned {@link AppUserPrincipal} also carries the org so tenancy can be
 * derived without a second query.
 */
@Service
public class DbUserDetailsService implements UserDetailsService {

    private final AppUserRepository users;

    public DbUserDetailsService(AppUserRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        AppUser user = users.findByEmailIgnoreCase(username)
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user: " + username));
        return new AppUserPrincipal(user);
    }
}
