package com.vouchq.policy;

import com.vouchq.scanner.Category;
import com.vouchq.scanner.Finding;
import com.vouchq.scanner.ScanResult;
import com.vouchq.scanner.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure-logic test of false-positive suppression (MA3-94). The load-bearing
 * property: suppressing a known FP drops it from the effective risk, but any
 * other finding (incl. a brand-new CRITICAL) is still detected.
 */
class FindingSuppressionServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID TOOL = UUID.randomUUID();

    private final Finding fpWarn = new Finding("secret.generic", Category.SECRET, Severity.WARN, "a.py", 3, "e");
    private final Finding realCrit = new Finding("exfil.cloud-metadata", Category.DATA_EXFILTRATION,
            Severity.CRITICAL, "b.py", 9, "e");

    private FindingSuppressionService serviceWith(FindingSuppression... rows) {
        FindingSuppressionRepository repo = mock(FindingSuppressionRepository.class);
        when(repo.findByOrgId(ORG)).thenReturn(List.of(rows));
        return new FindingSuppressionService(repo);
    }

    private FindingSuppression suppress(String ruleId, UUID toolId, String fingerprint) {
        return new FindingSuppression(UUID.randomUUID(), ORG, ruleId, toolId, fingerprint, "reason", "admin");
    }

    @Test
    void fingerprintIsStableAndLocationSpecific() {
        assertThat(FindingSuppressionService.fingerprint(fpWarn))
                .isEqualTo(FindingSuppressionService.fingerprint(
                        new Finding("secret.generic", Category.SECRET, Severity.WARN, "a.py", 3, "different evidence")));
        assertThat(FindingSuppressionService.fingerprint(fpWarn))
                .isNotEqualTo(FindingSuppressionService.fingerprint(realCrit));
    }

    @Test
    void ruleWideSuppressionDropsFpButKeepsOtherCritical() {
        FindingSuppressionService svc = serviceWith(suppress("secret.generic", null, null));

        ScanResult eff = svc.effective(ORG, TOOL, List.of(fpWarn, realCrit));

        // FP gone, the unrelated CRITICAL still drives effective risk/severity.
        assertThat(eff.findings()).containsExactly(realCrit);
        assertThat(eff.highestSeverity()).isEqualTo(Severity.CRITICAL);
        assertThat(eff.riskScore()).isEqualTo(90);
    }

    @Test
    void fingerprintSuppressionOnlyHitsThatFinding() {
        FindingSuppressionService svc = serviceWith(
                suppress("secret.generic", TOOL, FindingSuppressionService.fingerprint(fpWarn)));

        Finding otherSameRule = new Finding("secret.generic", Category.SECRET, Severity.WARN, "c.py", 1, "e");
        ScanResult eff = svc.effective(ORG, TOOL, List.of(fpWarn, otherSameRule, realCrit));

        // Only the acknowledged finding is removed; the other secret.generic remains.
        assertThat(eff.findings()).containsExactlyInAnyOrder(otherSameRule, realCrit);
    }

    @Test
    void toolScopedSuppressionDoesNotAffectOtherTool() {
        FindingSuppressionService svc = serviceWith(suppress("secret.generic", UUID.randomUUID(), null));

        ScanResult eff = svc.effective(ORG, TOOL, List.of(fpWarn));

        // Suppression is scoped to a different tool → FP still counts here.
        assertThat(eff.findings()).containsExactly(fpWarn);
        assertThat(eff.highestSeverity()).isEqualTo(Severity.WARN);
    }

    @Test
    void noSuppressionIsIdentity() {
        FindingSuppressionService svc = serviceWith();

        ScanResult eff = svc.effective(ORG, TOOL, List.of(fpWarn, realCrit));

        assertThat(eff.findings()).containsExactly(fpWarn, realCrit);
        assertThat(eff.highestSeverity()).isEqualTo(Severity.CRITICAL);
    }
}
