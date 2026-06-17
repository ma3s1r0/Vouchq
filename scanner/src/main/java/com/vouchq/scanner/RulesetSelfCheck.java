package com.vouchq.scanner;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Runs the canary corpus against a {@link SkillScanner} to prove the rule set
 * still detects known-malicious input. A canary fails if any of its expected rule
 * ids did not fire, or the scan's highest severity falls below the canary's bar.
 * The check is pure (no I/O), so it runs identically in CI (a build gate against
 * rule-weakening) and at runtime (fail-closed self-test).
 */
public final class RulesetSelfCheck {

    private RulesetSelfCheck() {}

    /** Why a canary failed: which expected rules were missing, and the severity gap. */
    public record CanaryFailure(String canaryId, Set<String> missingRuleIds,
                                Severity got, Severity expected) {}

    /** Outcome: {@code pass} only when every canary met its expectation. */
    public record Result(boolean pass, int total, List<CanaryFailure> failures) {}

    public static Result check(SkillScanner scanner, List<Canary> canaries) {
        List<CanaryFailure> failures = new ArrayList<>();
        for (Canary canary : canaries) {
            ScanResult scan = scanner.scan(canary.files(), ScanConfig.permissive());
            Set<String> fired = scan.findings().stream()
                    .map(Finding::ruleId).collect(Collectors.toSet());
            Set<String> missing = canary.expectedRuleIds().stream()
                    .filter(id -> !fired.contains(id))
                    .collect(Collectors.toSet());
            boolean severityOk = scan.highestSeverity() != null
                    && scan.highestSeverity().weight() >= canary.minSeverity().weight();
            if (!missing.isEmpty() || !severityOk) {
                failures.add(new CanaryFailure(canary.id(), missing,
                        scan.highestSeverity(), canary.minSeverity()));
            }
        }
        return new Result(failures.isEmpty(), canaries.size(), failures);
    }
}
