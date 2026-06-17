package com.vouchq.scanner;

import com.vouchq.parser.SkillFile;

import java.util.List;
import java.util.Set;

/**
 * A sealed known-malicious fixture used to prove the rule set still works (the
 * ruleset self-test). Each canary carries content that <em>must</em> trip a
 * specific rule at a minimum severity; if the active rule set fails to flag it,
 * the scanner has been weakened (poisoned or broken) and vouchq fails closed.
 *
 * @param id              stable canary id
 * @param files           the malicious definition (scanned like any Skill)
 * @param minSeverity     the lowest severity the scan must reach
 * @param expectedRuleIds rule ids that must fire (a subset of what the scan emits)
 */
public record Canary(String id, List<SkillFile> files, Severity minSeverity, Set<String> expectedRuleIds) {}
