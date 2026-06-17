package com.vouchq.sentinel;

/**
 * Thrown when vouchq is asked to mint new trust (approve a tool) while its
 * scanner's ruleset self-test is failing. vouchq won't vouch for anything when it
 * can't prove its own detection still works — mapped to HTTP 503.
 */
public class RulesetDegradedException extends RuntimeException {
    public RulesetDegradedException(String message) {
        super(message);
    }
}
