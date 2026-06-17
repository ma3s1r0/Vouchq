/**
 * Authentication &amp; authorization for the control plane (MA3-71).
 *
 * <p>DB-backed users over {@code app_user} ({@link com.vouchq.security.DbUserDetailsService},
 * BCrypt) behind two front doors — a session cookie for the console and HTTP
 * Basic for API/CI — wired in {@link com.vouchq.security.SecurityConfig}. Roles:
 * ADMIN / MEMBER / VIEWER. Read (GET) is open to any authenticated role; mutating
 * calls require MEMBER or ADMIN; member management is ADMIN-only. First boot seeds
 * a bootstrap admin ({@link com.vouchq.security.AdminBootstrap}) so nobody is
 * locked out. SSO/SAML is not yet implemented.
 */
package com.vouchq.security;
