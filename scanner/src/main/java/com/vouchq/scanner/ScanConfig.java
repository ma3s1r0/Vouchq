package com.vouchq.scanner;

import java.util.Set;

/**
 * Scan tuning. {@code suppressedRuleIds} silences specific rules globally; an
 * inline {@code vouchq:allow=<ruleId>} comment on a line silences that rule for
 * that line only. Both exist to manage false positives (기획서 §13).
 */
public record ScanConfig(Set<String> suppressedRuleIds) {

    public ScanConfig {
        suppressedRuleIds = Set.copyOf(suppressedRuleIds);
    }

    public static ScanConfig permissive() {
        return new ScanConfig(Set.of());
    }

    public static ScanConfig suppressing(String... ruleIds) {
        return new ScanConfig(Set.of(ruleIds));
    }

    public boolean isSuppressed(String ruleId) {
        return suppressedRuleIds.contains(ruleId);
    }
}
