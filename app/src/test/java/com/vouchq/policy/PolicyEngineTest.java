package com.vouchq.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vouchq.audit.AuditLogService;
import com.vouchq.registry.Tool;
import com.vouchq.registry.ToolRepository;
import com.vouchq.scanner.Category;
import com.vouchq.scanner.Finding;
import com.vouchq.scanner.ScanResult;
import com.vouchq.scanner.Severity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure-logic test of the DB-backed policy engine (MA3-86/MA3-92): each jsonb
 * condition matches, the right action is applied, and a POLICY_APPLIED audit entry
 * is written.
 */
class PolicyEngineTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PolicyRuleRepository rules;
    private ToolRepository tools;
    private AuditLogService audit;

    private PolicyEngine engineWith(PolicyRule... ruleList) {
        rules = mock(PolicyRuleRepository.class);
        tools = mock(ToolRepository.class);
        audit = mock(AuditLogService.class);
        FindingSuppressionRepository suppressions = mock(FindingSuppressionRepository.class);
        when(suppressions.findByOrgId(any())).thenReturn(List.of());
        when(rules.findByOrgIdAndEnabledTrueOrderByPriorityAscCreatedAtAsc(ORG))
                .thenReturn(List.of(ruleList));
        return new PolicyEngine(rules, tools, audit, MAPPER, new FindingSuppressionService(suppressions));
    }

    private static PolicyRule rule(String name, int priority, String conditionJson,
                                   PolicyProperties.Action action) {
        return new PolicyRule(UUID.randomUUID(), ORG, name, priority, conditionJson, action, true);
    }

    private static Tool tool(String name) {
        return new Tool(UUID.randomUUID(), ORG, UUID.randomUUID(), Tool.Kind.SKILL, name,
                Tool.Status.PENDING);
    }

    private static ScanResult scan(int risk, Severity highest, Category... cats) {
        List<Finding> findings = java.util.Arrays.stream(cats)
                .map(c -> new Finding("r", c, highest, "f", 1, "e"))
                .toList();
        return new ScanResult(risk, highest, findings);
    }

    @Test
    void riskScoreThresholdTriggersAutoBlock() {
        PolicyEngine engine = engineWith(
                rule("high", 100, "{\"minRiskScore\":80}", PolicyProperties.Action.AUTO_BLOCK));
        Tool t = tool("x");

        PolicyEngine.Decision d = engine.evaluate(ORG, t, scan(90, Severity.CRITICAL, Category.SECRET));

        assertThat(d.matched()).isTrue();
        assertThat(d.action()).isEqualTo(PolicyProperties.Action.AUTO_BLOCK);
        assertThat(t.getStatus()).isEqualTo(Tool.Status.BLOCKED);
        verify(tools, times(1)).save(t);
        verifyAuditAction("POLICY_APPLIED");
    }

    @Test
    void belowThresholdDoesNotMatch() {
        PolicyEngine engine = engineWith(
                rule("high", 100, "{\"minRiskScore\":80}", PolicyProperties.Action.AUTO_BLOCK));
        Tool t = tool("x");

        PolicyEngine.Decision d = engine.evaluate(ORG, t, scan(40, Severity.WARN, Category.DATA_EXFILTRATION));

        assertThat(d.matched()).isFalse();
        assertThat(t.getStatus()).isEqualTo(Tool.Status.PENDING);
        verify(audit, never()).append(any(), any(), any(), any(), any());
    }

    @Test
    void severityConditionMatchesHold() {
        PolicyEngine engine = engineWith(
                rule("crit", 100, "{\"severity\":\"CRITICAL\"}", PolicyProperties.Action.HOLD));
        Tool t = tool("x");

        PolicyEngine.Decision d = engine.evaluate(ORG, t, scan(50, Severity.CRITICAL, Category.PROMPT_INJECTION));

        assertThat(d.matched()).isTrue();
        assertThat(d.action()).isEqualTo(PolicyProperties.Action.HOLD);
        // HOLD does not change status.
        assertThat(t.getStatus()).isEqualTo(Tool.Status.PENDING);
        verify(tools, never()).save(any());
        verifyAuditAction("POLICY_APPLIED");
    }

    @Test
    void findingCategoryConditionMatches() {
        PolicyEngine engine = engineWith(
                rule("exfil", 100, "{\"findingCategory\":\"DATA_EXFILTRATION\"}", PolicyProperties.Action.HOLD));
        Tool t = tool("x");

        PolicyEngine.Decision matchD =
                engine.evaluate(ORG, t, scan(50, Severity.WARN, Category.DATA_EXFILTRATION));
        assertThat(matchD.matched()).isTrue();

        Tool t2 = tool("y");
        PolicyEngine.Decision noD = engine.evaluate(ORG, t2, scan(50, Severity.WARN, Category.SECRET));
        assertThat(noD.matched()).isFalse();
    }

    @Test
    void nameRegexConditionMatches() {
        PolicyEngine engine = engineWith(
                rule("named", 100, "{\"nameRegex\":\"(?i).*malware.*\"}", PolicyProperties.Action.AUTO_BLOCK));

        Tool bad = tool("evil-malware-skill");
        assertThat(engine.evaluate(ORG, bad, scan(0, null)).matched()).isTrue();
        assertThat(bad.getStatus()).isEqualTo(Tool.Status.BLOCKED);

        Tool good = tool("greeter");
        assertThat(engine.evaluate(ORG, good, scan(0, null)).matched()).isFalse();
    }

    @Test
    void firstMatchingRuleWinsInPriorityOrder() {
        PolicyEngine engine = engineWith(
                rule("block", 10, "{\"minRiskScore\":80}", PolicyProperties.Action.AUTO_BLOCK),
                rule("hold", 20, "{\"severity\":\"CRITICAL\"}", PolicyProperties.Action.HOLD));
        Tool t = tool("x");

        PolicyEngine.Decision d = engine.evaluate(ORG, t, scan(90, Severity.CRITICAL, Category.SECRET));

        assertThat(d.matched()).isTrue();
        assertThat(t.getStatus()).isEqualTo(Tool.Status.BLOCKED);
    }

    @Test
    void noEnabledRulesEvaluatesNothing() {
        PolicyEngine engine = engineWith(); // empty list
        Tool t = tool("x");

        assertThat(engine.evaluate(ORG, t, scan(100, Severity.CRITICAL, Category.SECRET)).matched()).isFalse();
        verify(audit, never()).append(any(), any(), any(), any(), any());
    }

    @Test
    void malformedConditionNeverMatches() {
        PolicyEngine engine = engineWith(
                rule("broken", 100, "not-json", PolicyProperties.Action.AUTO_BLOCK));
        Tool t = tool("x");

        assertThat(engine.evaluate(ORG, t, scan(100, Severity.CRITICAL, Category.SECRET)).matched()).isFalse();
        assertThat(t.getStatus()).isEqualTo(Tool.Status.PENDING);
    }

    @Test
    void emptyConditionMatchesEverything() {
        PolicyEngine engine = engineWith(
                rule("catch-all", 100, "{}", PolicyProperties.Action.HOLD));
        Tool t = tool("anything");

        assertThat(engine.evaluate(ORG, t, scan(0, null)).matched()).isTrue();
    }

    private void verifyAuditAction(String action) {
        ArgumentCaptor<String> actionCap = ArgumentCaptor.forClass(String.class);
        verify(audit, times(1)).append(any(), any(), actionCap.capture(), any(), any());
        assertThat(actionCap.getValue()).isEqualTo(action);
    }

    private static <T> T any() {
        return org.mockito.ArgumentMatchers.any();
    }
}
