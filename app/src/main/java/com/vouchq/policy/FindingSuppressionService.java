package com.vouchq.policy;

import com.vouchq.scanner.Finding;
import com.vouchq.scanner.ScanResult;
import com.vouchq.scanner.SkillScanner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.UUID;

/**
 * Applies false-positive suppressions to scan findings at read time (MA3-94).
 *
 * <p><b>Fingerprint.</b> A finding's stable identity is
 * {@code fingerprint(f) = sha256hex( f.ruleId + "|" + f.path + "|" + f.line )}.
 * It is deterministic across re-scans of the same offending location, so a
 * single acknowledged finding stays acknowledged on the next scan (while a new
 * finding elsewhere — different rule/path/line — has a different fingerprint and
 * is therefore still surfaced).
 *
 * <p><b>Match.</b> A {@link FindingSuppression} suppresses a finding when its
 * {@code ruleId} matches AND ({@code toolId} is null or equals the finding's
 * tool) AND ({@code fingerprint} is null or equals the finding's fingerprint).
 *
 * <p><b>Effective risk.</b> {@link #effective(UUID, UUID, List)} drops the
 * suppressed findings and re-aggregates with {@link SkillScanner#summarize} —
 * the <em>same</em> formula the scan was produced with, so risk/severity are not
 * forked. Crucially, only suppressed findings are removed: any other finding
 * (including a brand-new rule firing) still counts, so suppressing a known FP
 * cannot blind detection of new risk.
 */
@Service
public class FindingSuppressionService {

    private final FindingSuppressionRepository suppressions;

    public FindingSuppressionService(FindingSuppressionRepository suppressions) {
        this.suppressions = suppressions;
    }

    /** Stable fingerprint of a single finding: {@code sha256hex(ruleId|path|line)}. */
    public static String fingerprint(Finding f) {
        return fingerprint(f.ruleId(), f.path(), f.line());
    }

    public static String fingerprint(String ruleId, String path, int line) {
        return sha256Hex(ruleId + "|" + path + "|" + line);
    }

    /**
     * Whether a finding is suppressed for {@code toolId} given the org's active
     * suppressions. {@code toolId} may be null (e.g. before a tool id is known).
     */
    public static boolean isSuppressed(Finding finding, UUID toolId, List<FindingSuppression> active) {
        String fp = fingerprint(finding);
        for (FindingSuppression s : active) {
            if (matches(s, finding.ruleId(), fp, toolId)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(FindingSuppression s, String ruleId, String fingerprint, UUID toolId) {
        if (!s.getRuleId().equals(ruleId)) {
            return false;
        }
        if (s.getToolId() != null && !s.getToolId().equals(toolId)) {
            return false;
        }
        return s.getFingerprint() == null || s.getFingerprint().equals(fingerprint);
    }

    /** Active suppressions for the org (read once, applied to many findings). */
    @Transactional(readOnly = true)
    public List<FindingSuppression> active(UUID orgId) {
        return suppressions.findByOrgId(orgId);
    }

    /**
     * Recompute the effective scan (post-suppression) for a tool's findings,
     * reusing {@link SkillScanner#summarize}. Returns the original list-derived
     * result with suppressed findings removed.
     */
    public ScanResult effective(UUID orgId, UUID toolId, List<Finding> findings) {
        List<FindingSuppression> active = active(orgId);
        List<Finding> kept = findings.stream()
                .filter(f -> !isSuppressed(f, toolId, active))
                .toList();
        return SkillScanner.summarize(kept);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
