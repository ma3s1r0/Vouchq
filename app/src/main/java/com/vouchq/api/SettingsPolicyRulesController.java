package com.vouchq.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vouchq.policy.PolicyProperties;
import com.vouchq.policy.PolicyRule;
import com.vouchq.policy.PolicyRuleRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * CRUD for DB-backed policy rules (MA3-92). Rules are evaluated
 * by {@link com.vouchq.policy.PolicyEngine} in ascending {@code priority} order;
 * the first matching enabled rule wins.
 *
 * <p>RBAC: reads (GET) open to VIEWER+; mutations require ADMIN — enforced in
 * {@code com.vouchq.security.SecurityConfig} for {@code /api/settings/**}.
 */
@RestController
@RequestMapping("/api/settings/policy-rules")
public class SettingsPolicyRulesController {

    private static final int DEFAULT_PRIORITY = 100;

    private final PolicyRuleRepository rules;
    private final ObjectMapper objectMapper;
    private final com.vouchq.tenancy.CurrentOrg currentOrg;

    public SettingsPolicyRulesController(PolicyRuleRepository rules,
                                         ObjectMapper objectMapper,
                                         com.vouchq.tenancy.CurrentOrg currentOrg) {
        this.rules = rules;
        this.objectMapper = objectMapper;
        this.currentOrg = currentOrg;
    }

    @GetMapping
    public List<ApiDtos.PolicyRuleView> list() {
        UUID orgId = currentOrg.require();
        return rules.findByOrgIdOrderByPriorityAscCreatedAtAsc(orgId).stream()
                .map(ApiDtos.PolicyRuleView::from)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiDtos.PolicyRuleView create(@RequestBody ApiDtos.PolicyRuleRequest req) {
        UUID orgId = currentOrg.require();
        String name = require(req.name(), "name");
        PolicyProperties.Action action = parseAction(req.action());
        int priority = req.priority() != null ? req.priority() : DEFAULT_PRIORITY;
        String condition = normalizeCondition(req.condition());
        boolean enabled = req.enabled() == null || req.enabled();
        PolicyRule saved = rules.save(new PolicyRule(
                UUID.randomUUID(), orgId, name, priority, condition, action, enabled));
        return ApiDtos.PolicyRuleView.from(saved);
    }

    @PutMapping("/{id}")
    public ApiDtos.PolicyRuleView update(@PathVariable UUID id,
                                         @RequestBody ApiDtos.PolicyRuleRequest req) {
        UUID orgId = currentOrg.require();
        PolicyRule r = load(id, orgId);
        r.setName(require(req.name(), "name"));
        r.setAction(parseAction(req.action()));
        r.setPriority(req.priority() != null ? req.priority() : DEFAULT_PRIORITY);
        r.setCondition(normalizeCondition(req.condition()));
        r.setEnabled(req.enabled() == null || req.enabled());
        return ApiDtos.PolicyRuleView.from(rules.save(r));
    }

    @PatchMapping("/{id}")
    public ApiDtos.PolicyRuleView patch(@PathVariable UUID id,
                                        @RequestBody ApiDtos.PolicyRuleRequest req) {
        UUID orgId = currentOrg.require();
        PolicyRule r = load(id, orgId);
        if (req.name() != null) {
            r.setName(require(req.name(), "name"));
        }
        if (req.action() != null) {
            r.setAction(parseAction(req.action()));
        }
        if (req.priority() != null) {
            r.setPriority(req.priority());
        }
        if (req.condition() != null) {
            r.setCondition(normalizeCondition(req.condition()));
        }
        if (req.enabled() != null) {
            r.setEnabled(req.enabled());
        }
        return ApiDtos.PolicyRuleView.from(rules.save(r));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        UUID orgId = currentOrg.require();
        rules.delete(load(id, orgId));
    }

    private PolicyRule load(UUID id, UUID orgId) {
        return rules.findByIdAndOrgId(id, orgId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown policy rule: " + id));
    }

    private static PolicyProperties.Action parseAction(String action) {
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("action is required (AUTO_BLOCK | HOLD)");
        }
        try {
            return PolicyProperties.Action.valueOf(action.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown action: " + action + " (AUTO_BLOCK | HOLD)");
        }
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    /** Validate condition is a JSON object (or default {@code {}}); rejects malformed JSON. */
    private String normalizeCondition(String condition) {
        if (condition == null || condition.isBlank()) {
            return "{}";
        }
        try {
            var node = objectMapper.readTree(condition);
            if (!node.isObject()) {
                throw new IllegalArgumentException("condition must be a JSON object");
            }
            return node.toString();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("condition is not valid JSON: " + e.getOriginalMessage());
        }
    }
}
