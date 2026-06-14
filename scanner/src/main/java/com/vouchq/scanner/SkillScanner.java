package com.vouchq.scanner;

import com.vouchq.parser.ParsedSkill;
import com.vouchq.parser.SkillFile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Runs a set of {@link Rule}s over a skill's files and aggregates the result.
 * Stateless and reusable. Defaults to {@link BuiltinRules}; pass your own list
 * to extend or replace them.
 */
public final class SkillScanner {

    private static final String INLINE_ALLOW = "vouchq:allow=";

    private final List<Rule> rules;

    public SkillScanner() {
        this(BuiltinRules.all());
    }

    public SkillScanner(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    public ScanResult scan(ParsedSkill skill) {
        return scan(skill.files(), ScanConfig.permissive());
    }

    public ScanResult scan(ParsedSkill skill, ScanConfig config) {
        return scan(skill.files(), config);
    }

    public ScanResult scan(List<SkillFile> files, ScanConfig config) {
        List<Finding> findings = new ArrayList<>();
        for (SkillFile file : files) {
            String[] lines = file.content().split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                scanLine(file.path(), lines[i], i + 1, config, findings);
            }
        }
        return summarize(findings);
    }

    private void scanLine(String path, String line, int lineNumber, ScanConfig config, List<Finding> out) {
        for (Rule rule : rules) {
            if (config.isSuppressed(rule.id()) || hasInlineAllow(line, rule.id())) {
                continue;
            }
            String evidence = rule.match(line);
            if (evidence != null) {
                out.add(new Finding(rule.id(), rule.category(), rule.severity(), path, lineNumber, evidence));
            }
        }
    }

    private static boolean hasInlineAllow(String line, String ruleId) {
        return line.contains(INLINE_ALLOW + ruleId) || line.contains(INLINE_ALLOW + "*");
    }

    /**
     * Aggregate a list of findings into a {@link ScanResult} (risk score +
     * highest severity). Exposed so consumers that <em>filter</em> findings
     * (e.g. false-positive suppression at read time, MA3-94) recompute the
     * effective risk with the <em>same</em> formula the scan was produced with,
     * rather than forking it.
     */
    public static ScanResult summarize(List<Finding> findings) {
        if (findings.isEmpty()) {
            return new ScanResult(0, null, List.of());
        }
        Severity highest = findings.stream()
                .map(Finding::severity)
                .max(Comparator.comparingInt(Severity::weight))
                .orElseThrow();
        int score = Math.min(100, highest.weight() + 3 * (findings.size() - 1));
        return new ScanResult(score, highest, findings);
    }
}
