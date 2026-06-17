package com.vouchq.policy;

import com.vouchq.tenancy.OrgScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Filter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A false-positive suppression / acknowledgement of scan findings (MA3-94).
 * It silences a finding at <em>read</em> time only — the raw
 * {@code scan_result} is never mutated — so the API and {@link PolicyEngine}
 * compute an <em>effective</em> risk over the non-suppressed findings while the
 * underlying detection stays on the record.
 *
 * <p>A row matches a finding when all of the following hold:
 * <ul>
 *   <li>{@code ruleId} equals the finding's rule;</li>
 *   <li>{@code toolId} is {@code null} (org-wide for the rule) or equals the
 *       finding's tool;</li>
 *   <li>{@code fingerprint} is {@code null} (the whole rule, in scope) or equals
 *       the finding's fingerprint
 *       ({@code sha256hex(ruleId | path | line)}, see {@link FindingSuppressionService}).</li>
 * </ul>
 */
@Entity
@Table(name = "finding_suppression")
@Filter(name = OrgScoped.FILTER, condition = OrgScoped.CONDITION)
public class FindingSuppression {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "rule_id", nullable = false, length = 128)
    private String ruleId;

    /** Null = org-wide for the rule; set = scoped to a single tool. */
    @Column(name = "tool_id")
    private UUID toolId;

    /** Null = the whole rule (in scope); set = a single acknowledged finding. */
    @Column(name = "fingerprint", length = 64)
    private String fingerprint;

    @Column(name = "reason")
    private String reason;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected FindingSuppression() {}

    public FindingSuppression(UUID id, UUID orgId, String ruleId, UUID toolId,
                              String fingerprint, String reason, String createdBy) {
        this.id = id;
        this.orgId = orgId;
        this.ruleId = ruleId;
        this.toolId = toolId;
        this.fingerprint = fingerprint;
        this.reason = reason;
        this.createdBy = createdBy;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public String getRuleId() {
        return ruleId;
    }

    public UUID getToolId() {
        return toolId;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public String getReason() {
        return reason;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
