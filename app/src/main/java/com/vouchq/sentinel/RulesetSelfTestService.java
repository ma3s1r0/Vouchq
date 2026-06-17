package com.vouchq.sentinel;

import com.vouchq.audit.AuditLogService;
import com.vouchq.registry.Organization;
import com.vouchq.registry.OrganizationRepository;
import com.vouchq.scanner.BuiltinCanaries;
import com.vouchq.scanner.BuiltinRules;
import com.vouchq.scanner.Canary;
import com.vouchq.scanner.RulesetFingerprint;
import com.vouchq.scanner.RulesetSelfCheck;
import com.vouchq.scanner.SkillScanner;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Sentinel — vouchq governing its own ruleset. Periodically (and at startup, and
 * before serving) it runs the canary corpus against the live scanner; if any
 * canary goes undetected the ruleset has been weakened (poisoned or broken) and
 * the system flips to DEGRADED, which fails the approve path closed. State
 * transitions are written to the WORM audit log of every org, so the integrity
 * history is tamper-evident.
 *
 * <p>This is the dogfooding answer to the open-source supply-chain risk: a PR (or
 * a build/runtime tamper) that silently neuters a rule is caught behaviorally
 * here — and by the same corpus as a CI gate — not merely by trusting the diff.
 */
@Service
public class RulesetSelfTestService {

    private static final Logger log = LoggerFactory.getLogger(RulesetSelfTestService.class);

    private final RulesetHealth health;
    private final AuditLogService auditLog;
    private final OrganizationRepository organizations;

    private final SkillScanner scanner = new SkillScanner(BuiltinRules.all());
    private final List<Canary> canaries = BuiltinCanaries.all();
    private final String rulesetHash = RulesetFingerprint.of(BuiltinRules.all());

    public RulesetSelfTestService(RulesetHealth health, AuditLogService auditLog,
                                  OrganizationRepository organizations) {
        this.health = health;
        this.auditLog = auditLog;
        this.organizations = organizations;
    }

    /** Run once at startup, before the app serves traffic, so health is correct. */
    @PostConstruct
    public void initialCheck() {
        log.info("Sentinel: ruleset fingerprint {}", rulesetHash);
        runCheck();
    }

    /** Re-prove the ruleset on a schedule so runtime tampering is caught too. */
    @Scheduled(initialDelayString = "${vouchq.sentinel.interval-ms:3600000}",
            fixedDelayString = "${vouchq.sentinel.interval-ms:3600000}")
    public void scheduledCheck() {
        runCheck();
    }

    /** Run the corpus, update health, and audit any state transition. */
    public void runCheck() {
        RulesetSelfCheck.Result result = RulesetSelfCheck.check(scanner, canaries);
        RulesetHealth.State newState = result.pass()
                ? RulesetHealth.State.HEALTHY : RulesetHealth.State.DEGRADED;
        RulesetHealth.State previous = health.state();
        health.update(newState, result, rulesetHash, Instant.now());

        if (newState != previous) {
            recordTransition(newState, result);
        }
    }

    private void recordTransition(RulesetHealth.State state, RulesetSelfCheck.Result result) {
        if (state == RulesetHealth.State.DEGRADED) {
            log.error("Sentinel: RULESET SELF-TEST FAILED — {} canary(ies) undetected; "
                    + "approvals SUSPENDED. failures={}", result.failures().size(), result.failures());
        } else {
            log.info("Sentinel: ruleset self-test passing ({} canaries); approvals enabled",
                    result.total());
        }
        String action = state == RulesetHealth.State.DEGRADED
                ? "RULESET_SELFTEST_FAILED" : "RULESET_SELFTEST_PASSED";
        String payload = "{\"rulesetHash\":\"" + rulesetHash + "\",\"canaries\":" + result.total()
                + ",\"failed\":" + result.failures().size() + "}";
        for (Organization org : organizations.findAll()) {
            auditLog.append(org.getId(), "system", action, null, payload);
        }
    }
}
