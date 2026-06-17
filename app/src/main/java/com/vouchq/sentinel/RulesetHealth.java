package com.vouchq.sentinel;

import com.vouchq.scanner.RulesetSelfCheck;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * The live verdict of the ruleset self-test (Sentinel). A single global holder —
 * the rule set is global — read by the approve path to fail closed when the
 * scanner can no longer be trusted. Updated only by {@link RulesetSelfTestService}.
 */
@Component
public class RulesetHealth {

    public enum State { HEALTHY, DEGRADED }

    // Start DEGRADED (fail-safe): the first self-test runs at startup and flips
    // this to HEALTHY before traffic. If that check never runs, approvals stay
    // blocked rather than minting trust under an unverified ruleset.
    private volatile State state = State.DEGRADED;
    private volatile RulesetSelfCheck.Result lastResult;
    private volatile String rulesetHash = "";
    private volatile Instant lastCheckedAt;

    /** Block new trust issuance while the ruleset self-test is failing. */
    public void requireHealthy() {
        if (state != State.HEALTHY) {
            throw new RulesetDegradedException(
                    "Ruleset integrity check failed — approvals suspended until the scanner self-test passes");
        }
    }

    void update(State state, RulesetSelfCheck.Result result, String rulesetHash, Instant at) {
        this.state = state;
        this.lastResult = result;
        this.rulesetHash = rulesetHash;
        this.lastCheckedAt = at;
    }

    public State state() {
        return state;
    }

    public RulesetSelfCheck.Result lastResult() {
        return lastResult;
    }

    public String rulesetHash() {
        return rulesetHash;
    }

    public Instant lastCheckedAt() {
        return lastCheckedAt;
    }
}
