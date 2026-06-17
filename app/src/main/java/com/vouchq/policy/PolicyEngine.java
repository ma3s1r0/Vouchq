package com.vouchq.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vouchq.audit.AuditLogService;
import com.vouchq.registry.Tool;
import com.vouchq.registry.ToolRepository;
import com.vouchq.scanner.Finding;
import com.vouchq.scanner.ScanResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The policy rule engine (MA3-86 / MA3-92 "정책 룰"). After a tool is
 * ingested and scanned, it evaluates the org's <em>DB-backed</em> {@link PolicyRule}
 * rows against the scan outcome and the tool, applies the first matching enabled
 * rule's action, and appends a {@code POLICY_APPLIED} audit entry.
 *
 * <p><b>DB-backed (MA3-92):</b> rules now live in {@code policy_rule} editable at
 * runtime via {@code /api/settings/policy-rules} — no redeploy. The
 * {@code vouchq.policy.*} properties only seed an empty DB on first boot
 * ({@link PolicyRuleSeeder}).
 *
 * <p>Conditions (jsonb, ANDed; omit to ignore): {@code minRiskScore} (riskScore
 * &ge; N), {@code severity} (highest severity equals), {@code findingCategory}
 * (any finding of that category present), {@code nameRegex} (tool name matches).
 * Actions: {@code AUTO_BLOCK} (status → BLOCKED) and {@code HOLD} (left PENDING).
 *
 * <p>Rules are evaluated in ascending priority order; the first enabled match wins.
 * Evaluation never throws on a bad rule — a malformed {@code nameRegex}, bad
 * {@code severity}, or unparseable condition JSON simply does not match.
 */
@Service
public class PolicyEngine {

    private static final Logger log = LoggerFactory.getLogger(PolicyEngine.class);

    /** Policy decisions are system-initiated; no human actor. */
    private static final String AUDIT_ACTOR = "system";

    private final PolicyRuleRepository rules;
    private final ToolRepository tools;
    private final AuditLogService auditLog;
    private final ObjectMapper objectMapper;
    private final FindingSuppressionService suppressionService;

    public PolicyEngine(PolicyRuleRepository rules,
                        ToolRepository tools,
                        AuditLogService auditLog,
                        ObjectMapper objectMapper,
                        FindingSuppressionService suppressionService) {
        this.rules = rules;
        this.tools = tools;
        this.auditLog = auditLog;
        this.objectMapper = objectMapper;
        this.suppressionService = suppressionService;
    }

    /** Outcome of evaluating the policy rules against one scanned tool. */
    public record Decision(boolean matched, String ruleId, PolicyProperties.Action action) {
        public static Decision none() {
            return new Decision(false, null, null);
        }
    }

    /**
     * Evaluate the org's enabled rules (priority order) against a freshly scanned
     * tool and apply the first matching rule's action. Runs in the caller's
     * transaction so the status change + audit entry commit with the ingest.
     *
     * @param orgId tenant
     * @param tool  the tool just (re)observed — its status may be mutated
     * @param scan  the scanner outcome for the new version
     * @return the decision (which rule matched, if any)
     */
    @Transactional
    public Decision evaluate(UUID orgId, Tool tool, ScanResult scan) {
        List<PolicyRule> active = rules.findByOrgIdAndEnabledTrueOrderByPriorityAscCreatedAtAsc(orgId);
        if (active.isEmpty()) {
            return Decision.none();
        }
        // Evaluate on the EFFECTIVE (post-suppression) scan so acknowledged FPs do
        // not trip policy — but only suppressed findings are dropped, so any other
        // finding (incl. a brand-new rule) still counts and can still match (MA3-94).
        ScanResult effective = suppressionService.effective(orgId, tool.getId(), scan.findings());
        for (PolicyRule rule : active) {
            Condition cond = parse(rule);
            if (matches(cond, tool, effective)) {
                apply(orgId, tool, effective, rule);
                return new Decision(true, rule.getId().toString(), rule.getAction());
            }
        }
        return Decision.none();
    }

    /** Parsed view of a rule's jsonb condition; unparseable JSON yields an inert (never-matching) rule. */
    private record Condition(Integer minRiskScore, String severity, String findingCategory, String nameRegex,
                             boolean valid) {
        static Condition inert() {
            return new Condition(null, null, null, null, false);
        }
    }

    private Condition parse(PolicyRule rule) {
        try {
            JsonNode c = objectMapper.readTree(rule.getCondition());
            return new Condition(
                    c.hasNonNull("minRiskScore") ? c.get("minRiskScore").asInt() : null,
                    text(c, "severity"),
                    text(c, "findingCategory"),
                    text(c, "nameRegex"),
                    true);
        } catch (Exception e) {
            log.warn("Policy rule {} has an unparseable condition; skipping", rule.getId());
            return Condition.inert();
        }
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    /** A rule matches when every present condition matches (AND). An empty condition matches everything. */
    private boolean matches(Condition cond, Tool tool, ScanResult scan) {
        if (!cond.valid()) {
            return false;
        }
        if (cond.minRiskScore() != null && scan.riskScore() < cond.minRiskScore()) {
            return false;
        }
        if (cond.severity() != null && !cond.severity().isBlank()) {
            String highest = scan.highestSeverity() == null ? null : scan.highestSeverity().name();
            if (!cond.severity().trim().equalsIgnoreCase(highest)) {
                return false;
            }
        }
        if (cond.findingCategory() != null && !cond.findingCategory().isBlank()
                && !hasCategory(scan, cond.findingCategory().trim())) {
            return false;
        }
        if (cond.nameRegex() != null && !cond.nameRegex().isBlank()
                && !nameMatches(tool.getName(), cond.nameRegex())) {
            return false;
        }
        return true;
    }

    private static boolean hasCategory(ScanResult scan, String category) {
        for (Finding f : scan.findings()) {
            if (f.category() != null && f.category().name().equalsIgnoreCase(category)) {
                return true;
            }
        }
        return false;
    }

    private static boolean nameMatches(String name, String regex) {
        if (name == null) {
            return false;
        }
        try {
            return Pattern.compile(regex).matcher(name).matches();
        } catch (PatternSyntaxException e) {
            return false; // a broken rule never matches (never throws on ingest)
        }
    }

    private void apply(UUID orgId, Tool tool, ScanResult scan, PolicyRule rule) {
        if (rule.getAction() == PolicyProperties.Action.AUTO_BLOCK) {
            tool.setStatus(Tool.Status.BLOCKED);
            tool.touch();
            tools.save(tool);
        }
        // HOLD leaves the tool PENDING (its ingest status) — the audit entry is
        // the "flagged for review" signal; no status mutation needed.

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("ruleId", rule.getId().toString());
        payload.put("ruleName", rule.getName());
        payload.put("action", rule.getAction().name());
        payload.put("toolName", tool.getName());
        payload.put("riskScore", scan.riskScore());
        payload.put("highestSeverity", scan.highestSeverity() == null ? null : scan.highestSeverity().name());
        payload.put("findingCount", scan.findings().size());
        payload.put("outcome", rule.getAction() == PolicyProperties.Action.AUTO_BLOCK ? "BLOCKED" : "HELD");
        auditLog.append(orgId, AUDIT_ACTOR, "POLICY_APPLIED", tool.getId(), payload.toString());

        log.info("Policy {} ({}) applied to tool={} org={} action={} risk={}",
                rule.getId(), rule.getName(), tool.getId(), orgId, rule.getAction(), scan.riskScore());
    }
}
