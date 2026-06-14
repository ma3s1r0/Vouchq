package com.vouchq.scanner;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A {@link Rule} backed by a regular expression. When {@code maskMatch} is set,
 * the matched substring is masked in the evidence so secrets are never echoed
 * back in full (기획서 §10).
 */
public final class RegexRule implements Rule {

    private final String id;
    private final Category category;
    private final Severity severity;
    private final Pattern pattern;
    private final boolean maskMatch;

    public RegexRule(String id, Category category, Severity severity, String regex, boolean maskMatch) {
        this.id = id;
        this.category = category;
        this.severity = severity;
        this.pattern = Pattern.compile(regex);
        this.maskMatch = maskMatch;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public Category category() {
        return category;
    }

    @Override
    public Severity severity() {
        return severity;
    }

    @Override
    public String match(String line) {
        Matcher m = pattern.matcher(line);
        if (!m.find()) {
            return null;
        }
        String snippet = line.strip();
        if (!maskMatch) {
            return snippet;
        }
        String matched = m.group();
        String masked = matched.length() <= 4 ? "****" : matched.substring(0, 4) + "****";
        return snippet.replace(matched, masked);
    }
}
