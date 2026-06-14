package com.vouchq.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vouchq.audit.AuditLogService;
import com.vouchq.policy.FindingSuppression;
import com.vouchq.policy.FindingSuppressionRepository;
import com.vouchq.tenancy.CurrentOrg;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Manage false-positive suppressions / acknowledgements (MA3-94, 기획서 §13).
 *
 * <p>A suppression silences a finding at read time (the raw scan is preserved):
 * the API and {@link com.vouchq.policy.PolicyEngine} compute an effective risk
 * over the non-suppressed findings, so acknowledging a known FP never blinds
 * detection of a new finding.
 *
 * <p>Scope of the body: {@code ruleId} (required), optional {@code toolId}
 * (null = org-wide for the rule), optional {@code fingerprint} (null = the whole
 * rule; set = a single acknowledged finding), optional {@code reason}.
 *
 * <p>RBAC: reads VIEWER+, mutations ADMIN-only — enforced in
 * {@code com.vouchq.security.SecurityConfig} for {@code /api/settings/**}. Every
 * create/delete is a trust-affecting action and is written to the audit log.
 */
@RestController
@RequestMapping("/api/settings/suppressions")
public class SettingsSuppressionsController {

    private final FindingSuppressionRepository suppressions;
    private final AuditLogService auditLog;
    private final ObjectMapper objectMapper;
    private final CurrentOrg currentOrg;

    public SettingsSuppressionsController(FindingSuppressionRepository suppressions,
                                          AuditLogService auditLog,
                                          ObjectMapper objectMapper,
                                          CurrentOrg currentOrg) {
        this.suppressions = suppressions;
        this.auditLog = auditLog;
        this.objectMapper = objectMapper;
        this.currentOrg = currentOrg;
    }

    @GetMapping
    public List<ApiDtos.SuppressionView> list() {
        UUID orgId = currentOrg.require();
        return suppressions.findByOrgIdOrderByCreatedAtDesc(orgId).stream()
                .map(ApiDtos.SuppressionView::from)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiDtos.SuppressionView create(@RequestBody ApiDtos.SuppressionRequest req,
                                          Authentication auth) {
        UUID orgId = currentOrg.require();
        String ruleId = require(req.ruleId(), "ruleId");
        UUID toolId = req.toolId();
        String fingerprint = blankToNull(req.fingerprint());
        String reason = blankToNull(req.reason());
        String actor = actor(auth);

        FindingSuppression saved = suppressions.save(new FindingSuppression(
                UUID.randomUUID(), orgId, ruleId, toolId, fingerprint, reason, actor));

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("suppressionId", saved.getId().toString());
        payload.put("ruleId", ruleId);
        payload.put("scope", toolId == null ? "ORG" : "TOOL");
        if (toolId != null) {
            payload.put("toolId", toolId.toString());
        }
        payload.put("target", fingerprint == null ? "RULE" : "FINDING");
        if (fingerprint != null) {
            payload.put("fingerprint", fingerprint);
        }
        payload.put("reason", reason == null ? "" : reason);
        auditLog.append(orgId, actor, "SUPPRESSION_CREATED", toolId, payload.toString());

        return ApiDtos.SuppressionView.from(saved);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, Authentication auth) {
        UUID orgId = currentOrg.require();
        FindingSuppression s = suppressions.findByIdAndOrgId(id, orgId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown suppression: " + id));
        String actor = actor(auth);
        suppressions.delete(s);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("suppressionId", s.getId().toString());
        payload.put("ruleId", s.getRuleId());
        payload.put("scope", s.getToolId() == null ? "ORG" : "TOOL");
        payload.put("target", s.getFingerprint() == null ? "RULE" : "FINDING");
        auditLog.append(orgId, actor, "SUPPRESSION_DELETED", s.getToolId(), payload.toString());
    }

    private static String actor(Authentication auth) {
        return auth != null && auth.getName() != null ? auth.getName() : "system";
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
