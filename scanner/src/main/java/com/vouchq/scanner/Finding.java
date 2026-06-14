package com.vouchq.scanner;

/**
 * A single risk detection.
 *
 * @param ruleId   stable id of the rule that fired (used for suppression)
 * @param category risk class
 * @param severity finding severity
 * @param path     file (relative to the skill) where it was found
 * @param line     1-based line number
 * @param evidence the offending line, with any secret masked
 */
public record Finding(
        String ruleId,
        Category category,
        Severity severity,
        String path,
        int line,
        String evidence) {}
