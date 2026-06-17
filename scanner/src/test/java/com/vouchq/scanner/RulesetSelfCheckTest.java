package com.vouchq.scanner;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ruleset self-test as a CI gate: the built-in rule set must catch every
 * canary. A PR that weakens or removes a CRITICAL rule fails here — you cannot
 * quietly neuter detection without a canary going dark.
 */
class RulesetSelfCheckTest {

    @Test
    void builtinRulesetCatchesEveryCanary() {
        RulesetSelfCheck.Result result =
                RulesetSelfCheck.check(new SkillScanner(BuiltinRules.all()), BuiltinCanaries.all());
        assertTrue(result.pass(),
                () -> "ruleset self-test failed — canaries went undetected: " + result.failures());
        assertEquals(BuiltinCanaries.all().size(), result.total());
    }

    @Test
    void detectsAWeakenedRuleset() {
        // Simulate a poisoned ruleset: drop the rm -rf rule.
        List<Rule> weakened = BuiltinRules.all().stream()
                .filter(r -> !r.id().equals("danger.rm-rf"))
                .toList();
        RulesetSelfCheck.Result result =
                RulesetSelfCheck.check(new SkillScanner(weakened), BuiltinCanaries.all());
        assertFalse(result.pass());
        assertTrue(result.failures().stream().anyMatch(f -> f.canaryId().equals("danger-rm-rf")));
    }

    @Test
    void fingerprintChangesWhenARuleIsRemoved() {
        String full = RulesetFingerprint.of(BuiltinRules.all());
        String weakened = RulesetFingerprint.of(BuiltinRules.all().stream()
                .filter(r -> !r.id().equals("danger.rm-rf")).toList());
        assertNotEquals(full, weakened);
        assertEquals(64, full.length());
    }
}
