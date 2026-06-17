package com.vouchq.scanner;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A stable content hash of the active rule set — its identity. Computed over each
 * rule's {@code id|category|severity|regex} (id-sorted), so adding, removing,
 * downgrading, or loosening a rule moves the fingerprint. Informational: the
 * behavioral self-test ({@link RulesetSelfCheck}) is the gate; the fingerprint
 * lets an operator (or a transparency log) confirm which rule set is running.
 */
public final class RulesetFingerprint {

    private RulesetFingerprint() {}

    public static String of(List<Rule> rules) {
        String canonical = rules.stream()
                .sorted(Comparator.comparing(Rule::id))
                .map(r -> r.id() + "|" + r.category() + "|" + r.severity() + "|"
                        + (r instanceof RegexRule regex ? regex.pattern() : ""))
                .collect(Collectors.joining("\n"));
        return sha256Hex(canonical.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
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
