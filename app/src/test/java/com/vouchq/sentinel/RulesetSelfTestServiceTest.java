package com.vouchq.sentinel;

import com.vouchq.audit.AuditLogService;
import com.vouchq.registry.OrganizationRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * App-layer wiring of Sentinel: health starts DEGRADED (fail-safe), and a passing
 * self-test against the real built-in rule set flips it to HEALTHY and opens the
 * approve gate, auditing the transition.
 */
class RulesetSelfTestServiceTest {

    @Test
    void startsDegradedThenHealthyAfterPassingSelfTest() {
        RulesetHealth health = new RulesetHealth();
        // Fail-safe: before any check, approvals are blocked.
        assertThatThrownBy(health::requireHealthy).isInstanceOf(RulesetDegradedException.class);

        AuditLogService auditLog = mock(AuditLogService.class);
        OrganizationRepository orgs = mock(OrganizationRepository.class);
        var org = mock(com.vouchq.registry.Organization.class);
        var orgId = java.util.UUID.randomUUID();
        when(org.getId()).thenReturn(orgId);
        when(orgs.findAll()).thenReturn(List.of(org));

        new RulesetSelfTestService(health, auditLog, orgs).runCheck();

        // The real built-in ruleset catches every canary → HEALTHY, gate opens.
        assertThat(health.state()).isEqualTo(RulesetHealth.State.HEALTHY);
        health.requireHealthy(); // no throw
        assertThat(health.rulesetHash()).hasSize(64);
        // The DEGRADED→HEALTHY transition is audited per org.
        verify(auditLog).append(eq(orgId), eq("system"), eq("RULESET_SELFTEST_PASSED"), any(), any());
    }
}
