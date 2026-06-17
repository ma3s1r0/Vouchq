package com.vouchq.policy;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Config-driven policy rules (MA3-86 "정책 룰").
 *
 * <p>Bound from {@code vouchq.policy.*}. A rule is a set of conditions (ANDed
 * together; null/blank conditions are ignored) plus an action to apply when they
 * all match. Rules are evaluated in list order against each newly scanned tool;
 * the first matching rule's action wins (so order the strictest first).
 *
 * <h2>Config shape</h2>
 * <pre>
 * vouchq:
 *   policy:
 *     enabled: true                 # master switch (default true)
 *     rules:
 *       - id: auto-block-high-risk   # stable id (recorded in the audit entry)
 *         min-risk-score: 80         # condition: scan riskScore &gt;= 80
 *         action: AUTO_BLOCK         # set tool status BLOCKED
 *       - id: hold-critical
 *         severity: CRITICAL         # condition: highestSeverity == CRITICAL
 *         action: HOLD               # keep PENDING / flag for review
 *       - id: hold-exfil
 *         finding-category: DATA_EXFILTRATION   # condition: a finding of this category present
 *         action: HOLD
 *       - id: block-named
 *         name-regex: "(?i).*malware.*"          # condition: tool name matches regex
 *         action: AUTO_BLOCK
 * </pre>
 *
 * <p>Conditions: {@code minRiskScore} (riskScore &gt;= N), {@code severity}
 * (highest severity equals), {@code findingCategory} (any finding of that
 * category), {@code nameRegex} (tool name matches). Actions: {@code AUTO_BLOCK},
 * {@code HOLD}.
 */
@ConfigurationProperties(prefix = "vouchq.policy")
public class PolicyProperties {

    /** Master switch. When false the engine evaluates nothing (default true). */
    private boolean enabled = true;

    private List<Rule> rules = List.of();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<Rule> getRules() {
        return rules;
    }

    public void setRules(List<Rule> rules) {
        this.rules = rules == null ? List.of() : rules;
    }

    /** What a matched rule does to the tool. */
    public enum Action {
        /** Set the tool's status to BLOCKED. */
        AUTO_BLOCK,
        /** Keep the tool PENDING / flagged for manual review (no status change). */
        HOLD
    }

    /** One condition → action rule. Conditions are ANDed; null ones are ignored. */
    public static class Rule {
        private String id;
        private Integer minRiskScore;
        private String severity;
        private String findingCategory;
        private String nameRegex;
        private Action action;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public Integer getMinRiskScore() {
            return minRiskScore;
        }

        public void setMinRiskScore(Integer minRiskScore) {
            this.minRiskScore = minRiskScore;
        }

        public String getSeverity() {
            return severity;
        }

        public void setSeverity(String severity) {
            this.severity = severity;
        }

        public String getFindingCategory() {
            return findingCategory;
        }

        public void setFindingCategory(String findingCategory) {
            this.findingCategory = findingCategory;
        }

        public String getNameRegex() {
            return nameRegex;
        }

        public void setNameRegex(String nameRegex) {
            this.nameRegex = nameRegex;
        }

        public Action getAction() {
            return action;
        }

        public void setAction(Action action) {
            this.action = action;
        }
    }
}
