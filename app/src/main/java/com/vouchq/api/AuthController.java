package com.vouchq.api;

import com.vouchq.security.AppUser;
import com.vouchq.security.AppUserPrincipal;
import com.vouchq.security.AppUserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Session login for the console (MA3-93). {@code POST /api/auth/login} verifies
 * the email/password against {@code app_user} (BCrypt) and establishes a session
 * ({@code JSESSIONID} cookie); {@code POST /api/auth/logout} invalidates it; and
 * {@code GET /api/auth/me} returns the current user + org + role.
 *
 * <p>HTTP Basic (for API/CI clients) keeps working independently — this only adds
 * the cookie-session front door the browser console needs.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final SecurityContextRepository securityContextRepository =
            new HttpSessionSecurityContextRepository();

    public AuthController(AuthenticationManager authenticationManager,
                          AppUserRepository users,
                          PasswordEncoder passwordEncoder) {
        this.authenticationManager = authenticationManager;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    public record LoginRequest(String email, String password) {}

    public record ChangePasswordRequest(String currentPassword, String newPassword) {}

    public record MeView(String userId, String email, String displayName, String role,
                         String orgId) {
        static MeView of(AppUserPrincipal p) {
            return new MeView(p.getUserId().toString(), p.getEmail(), p.getDisplayName(),
                    p.getRole().name(), p.getOrgId().toString());
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req,
                                   HttpServletRequest request,
                                   HttpServletResponse response) {
        if (req == null || req.email() == null || req.password() == null) {
            return ResponseEntity.badRequest()
                    .body(new ApiDtos.ApiError("bad_request", "email and password are required"));
        }
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(req.email(), req.password()));
        } catch (AuthenticationException ex) {
            return ResponseEntity.status(401)
                    .body(new ApiDtos.ApiError("unauthorized", "Invalid email or password"));
        }

        // Establish the session-bound SecurityContext (JSESSIONID cookie).
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        return ResponseEntity.ok(MeView.of((AppUserPrincipal) authentication.getPrincipal()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public ResponseEntity<?> me() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AppUserPrincipal principal)) {
            return ResponseEntity.status(401)
                    .body(new ApiDtos.ApiError("unauthorized", "Not authenticated"));
        }
        return ResponseEntity.ok(MeView.of(principal));
    }

    /**
     * Self-service password change for the logged-in user. Requires the current
     * password (so a hijacked session can't silently rotate it) and a new password
     * of at least 8 chars. Stored BCrypt-hashed like every other credential.
     */
    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(@RequestBody ChangePasswordRequest req) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AppUserPrincipal principal)) {
            return ResponseEntity.status(401)
                    .body(new ApiDtos.ApiError("unauthorized", "Not authenticated"));
        }
        if (req == null || req.currentPassword() == null || req.newPassword() == null) {
            return ResponseEntity.badRequest()
                    .body(new ApiDtos.ApiError("bad_request", "currentPassword and newPassword are required"));
        }
        if (req.newPassword().length() < 8) {
            return ResponseEntity.badRequest()
                    .body(new ApiDtos.ApiError("bad_request", "newPassword must be at least 8 characters"));
        }
        AppUser user = users.findById(principal.getUserId()).orElse(null);
        if (user == null || user.getPasswordHash() == null
                || !passwordEncoder.matches(req.currentPassword(), user.getPasswordHash())) {
            return ResponseEntity.status(403)
                    .body(new ApiDtos.ApiError("forbidden", "Current password is incorrect"));
        }
        user.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        users.save(user);
        return ResponseEntity.noContent().build();
    }
}
