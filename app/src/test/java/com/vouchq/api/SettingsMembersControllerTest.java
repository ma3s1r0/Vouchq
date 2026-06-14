package com.vouchq.api;

import com.vouchq.security.AppUser;
import com.vouchq.security.AppUserRepository;
import com.vouchq.security.DbUserDetailsService;
import com.vouchq.security.SecurityConfig;
import com.vouchq.tenancy.CurrentOrg;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MA3-93 — members API RBAC + CRUD at the boundary. Member management is
 * ADMIN-only (the listing too); create scopes into the admin's org and hashes the
 * password; non-admins are forbidden.
 */
@WebMvcTest(SettingsMembersController.class)
@Import(SecurityConfig.class)
class SettingsMembersControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean AppUserRepository users;
    @MockBean CurrentOrg currentOrg;
    @MockBean DbUserDetailsService userDetailsService;
    @MockBean com.vouchq.tenancy.OrgResolver orgResolver;
    @Autowired PasswordEncoder encoder;

    private static final UUID ORG = UUID.randomUUID();

    @Test
    @WithMockUser(roles = "ADMIN")
    void admin_canListMembers() throws Exception {
        when(currentOrg.require()).thenReturn(ORG);
        when(users.findByOrgIdOrderByCreatedAtAsc(ORG)).thenReturn(List.of());
        mockMvc.perform(get("/api/settings/members")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void member_cannotListMembers_is403() throws Exception {
        mockMvc.perform(get("/api/settings/members")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void viewer_cannotListMembers_is403() throws Exception {
        mockMvc.perform(get("/api/settings/members")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void admin_createsMember_inOwnOrg_withHashedPassword() throws Exception {
        when(currentOrg.require()).thenReturn(ORG);
        when(users.existsByEmailIgnoreCase("new@x")).thenReturn(false);
        when(users.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(post("/api/settings/members").with(csrf())
                        .contentType("application/json")
                        .content("{\"email\":\"new@x\",\"displayName\":\"New\",\"role\":\"MEMBER\",\"password\":\"pw12345\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("new@x"))
                .andExpect(jsonPath("$.role").value("MEMBER"));

        org.mockito.ArgumentCaptor<AppUser> cap = org.mockito.ArgumentCaptor.forClass(AppUser.class);
        org.mockito.Mockito.verify(users).save(cap.capture());
        AppUser saved = cap.getValue();
        org.assertj.core.api.Assertions.assertThat(saved.getOrgId()).isEqualTo(ORG);
        // Password is stored hashed, never as plaintext, and verifies.
        org.assertj.core.api.Assertions.assertThat(saved.getPasswordHash()).isNotEqualTo("pw12345");
        org.assertj.core.api.Assertions.assertThat(encoder.matches("pw12345", saved.getPasswordHash())).isTrue();
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void member_cannotCreate_is403() throws Exception {
        mockMvc.perform(post("/api/settings/members").with(csrf())
                        .contentType("application/json")
                        .content("{\"email\":\"x@x\",\"role\":\"MEMBER\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void admin_changesRole() throws Exception {
        UUID id = UUID.randomUUID();
        when(currentOrg.require()).thenReturn(ORG);
        AppUser existing = new AppUser(id, ORG, "u@x", "U", AppUser.Role.VIEWER, "h");
        when(users.findByIdAndOrgId(id, ORG)).thenReturn(Optional.of(existing));
        when(users.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(patch("/api/settings/members/{id}", id).with(csrf())
                        .contentType("application/json")
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void admin_deactivates() throws Exception {
        UUID id = UUID.randomUUID();
        when(currentOrg.require()).thenReturn(ORG);
        AppUser existing = new AppUser(id, ORG, "u@x", "U", AppUser.Role.MEMBER, "h");
        when(users.findByIdAndOrgId(id, ORG)).thenReturn(Optional.of(existing));
        when(users.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(delete("/api/settings/members/{id}", id).with(csrf()))
                .andExpect(status().isNoContent());
        org.assertj.core.api.Assertions.assertThat(existing.isActive()).isFalse();
    }
}
