package com.vouchq.sentinel;

import com.vouchq.api.ApiDtos;
import com.vouchq.scanner.RulesetSelfCheck;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Exposes the Sentinel verdict (ruleset self-test) for operators and the console.
 * Read-only (VIEWER+): callers can see whether vouchq's own scanner is verified,
 * its rule-set fingerprint, and which canaries (if any) went undetected.
 */
@RestController
public class RulesetHealthController {

    private final RulesetHealth health;

    public RulesetHealthController(RulesetHealth health) {
        this.health = health;
    }

    @GetMapping("/api/ruleset/health")
    public ApiDtos.RulesetHealthView health() {
        RulesetSelfCheck.Result result = health.lastResult();
        int total = result != null ? result.total() : 0;
        List<String> failed = result != null
                ? result.failures().stream().map(RulesetSelfCheck.CanaryFailure::canaryId).toList()
                : List.of();
        OffsetDateTime checkedAt = health.lastCheckedAt() != null
                ? OffsetDateTime.ofInstant(health.lastCheckedAt(), ZoneOffset.UTC) : null;
        return new ApiDtos.RulesetHealthView(health.state().name(), health.rulesetHash(),
                checkedAt, total, failed.size(), failed);
    }
}
