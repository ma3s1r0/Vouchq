package com.vouchq.security;

import com.vouchq.api.ToolsController;
import com.vouchq.registry.ApprovalService;
import com.vouchq.registry.ApprovedVersion;
import com.vouchq.registry.ApprovedVersionRepository;
import com.vouchq.registry.DriftEventRepository;
import com.vouchq.registry.ToolRepository;
import com.vouchq.registry.ToolVersionRepository;
import com.vouchq.tenancy.CurrentOrg;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MA3-71/MA3-93 완료 기준 proof — DB/Docker-free MVC slice over {@link ToolsController}
 * with the real {@link SecurityConfig} imported. {@code @WithMockUser} supplies the
 * role; the org is supplied by a mocked {@link CurrentOrg} (the real
 * resolve→bind→Hibernate-filter chain is exercised by the live e2e and the JPA
 * isolation test).
 *
 * <p>Asserts the RBAC matrix at the boundary:
 * <ul>
 *   <li>unauthenticated → 401</li>
 *   <li>VIEWER GET → allowed (200)</li>
 *   <li>VIEWER POST approve → 403 (forbidden)</li>
 *   <li>ADMIN POST approve → not 401/403 (business 2xx)</li>
 *   <li>MEMBER POST block → not 401/403</li>
 * </ul>
 */
@WebMvcTest(ToolsController.class)
@Import(SecurityConfig.class)
class RbacSecuritySliceTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean ApprovalService approval;
    @MockBean ToolRepository tools;
    @MockBean ToolVersionRepository toolVersions;
    @MockBean ApprovedVersionRepository approvedVersions;
    @MockBean com.vouchq.registry.ScanResultRepository scanResults;
    @MockBean DriftEventRepository driftEvents;
    @MockBean com.vouchq.api.ScanViewAssembler scanViews;
    // ToolsController also depends on these (MCP-install view, MA3-110).
    @MockBean com.vouchq.registry.RegisteredServerRepository registeredServers;
    @MockBean com.vouchq.registry.SourceRepository sources;
    // Sentinel gate on approve (ruleset self-test) — mocked here; this slice
    // exercises RBAC at the boundary, not ruleset health.
    @MockBean com.vouchq.sentinel.RulesetHealth rulesetHealth;
    @MockBean CurrentOrg currentOrg;
    // SecurityConfig#authenticationManager needs a UserDetailsService bean.
    @MockBean DbUserDetailsService userDetailsService;
    // OrgContextFilter (MA3-70) is a Filter bean picked up by @WebMvcTest; it
    // needs an OrgResolver. The mock returns no org — RBAC at the boundary is
    // what this slice exercises.
    @MockBean com.vouchq.tenancy.OrgResolver orgResolver;

    private static final UUID TOOL_ID = UUID.randomUUID();

    @Test
    void unauthenticated_list_is401() throws Exception {
        mockMvc.perform(get("/api/tools"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void viewer_canRead() throws Exception {
        when(currentOrg.require()).thenReturn(UUID.randomUUID());
        when(tools.search(any(), any(), any())).thenReturn(List.of());
        mockMvc.perform(get("/api/tools"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void viewer_cannotApprove_is403() throws Exception {
        mockMvc.perform(post("/api/tools/{id}/approve", TOOL_ID).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void admin_canApprove_notForbidden() throws Exception {
        when(currentOrg.require()).thenReturn(UUID.randomUUID());
        ApprovedVersion pinned = org.mockito.Mockito.mock(ApprovedVersion.class);
        when(pinned.getId()).thenReturn(UUID.randomUUID());
        when(approval.approve(any(), any(), any())).thenReturn(pinned);

        int sc = mockMvc.perform(post("/api/tools/{id}/approve", TOOL_ID).with(csrf()))
                .andReturn().getResponse().getStatus();
        // The point is authorization passed: never 401/403.
        org.junit.jupiter.api.Assertions.assertTrue(sc != 401 && sc != 403,
                "ADMIN approve must not be unauthorized/forbidden, got " + sc);
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void member_canBlock_notForbidden() throws Exception {
        when(currentOrg.require()).thenReturn(UUID.randomUUID());
        int sc = mockMvc.perform(post("/api/tools/{id}/block", TOOL_ID).with(csrf()))
                .andReturn().getResponse().getStatus();
        org.junit.jupiter.api.Assertions.assertTrue(sc != 401 && sc != 403,
                "MEMBER block must not be unauthorized/forbidden, got " + sc);
    }
}
