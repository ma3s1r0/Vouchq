package com.vouchq.scanner;

/**
 * A detection rule. Implementations are stateless and matched line by line so a
 * finding can carry a precise location. The rule set is extensible — add a
 * {@link RegexRule} (or any {@code Rule}) and pass it to {@link SkillScanner}.
 */
public interface Rule {

    String id();

    Category category();

    Severity severity();

    /**
     * Test one line.
     *
     * @return rendered evidence (the line, with secrets masked) if it matches,
     *         or {@code null} if it does not
     */
    String match(String line);
}
