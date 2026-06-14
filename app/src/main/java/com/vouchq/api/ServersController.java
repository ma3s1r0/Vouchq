package com.vouchq.api;

import com.vouchq.registry.RegisteredServerRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Server / Skill-bundle inventory (기획서 §8).
 *
 * <p>RBAC (MA3-71): read-only (GET) — open to any authenticated role via
 * {@code com.vouchq.security.SecurityConfig}. Org context from
 * the authenticated user's org ({@link com.vouchq.tenancy.CurrentOrg}); query-level multitenancy is MA3-70.
 */
@RestController
@RequestMapping("/api/servers")
public class ServersController {

    private final RegisteredServerRepository servers;
    private final com.vouchq.tenancy.CurrentOrg currentOrg;

    public ServersController(RegisteredServerRepository servers, com.vouchq.tenancy.CurrentOrg currentOrg) {
        this.servers = servers;
        this.currentOrg = currentOrg;
    }

    @GetMapping
    public List<ApiDtos.ServerView> list() {
        UUID orgId = currentOrg.require();
        return servers.findByOrgIdOrderByCreatedAtDesc(orgId).stream()
                .map(ApiDtos.ServerView::from)
                .toList();
    }
}
