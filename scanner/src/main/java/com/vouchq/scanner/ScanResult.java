package com.vouchq.scanner;

import java.util.List;

/**
 * The outcome of scanning a skill: an aggregate {@code riskScore} (0–100), the
 * {@code highestSeverity} seen (null when clean), and the individual findings.
 *
 * @param riskScore       0 when clean, else weighted by the worst finding
 * @param highestSeverity worst severity among findings, or {@code null} if clean
 * @param findings        all findings, in scan order (file, then line)
 */
public record ScanResult(int riskScore, Severity highestSeverity, List<Finding> findings) {

    public ScanResult {
        findings = List.copyOf(findings);
    }

    public boolean isClean() {
        return findings.isEmpty();
    }
}
