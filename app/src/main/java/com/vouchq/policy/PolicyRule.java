package com.vouchq.policy;

import com.vouchq.tenancy.OrgScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A DB-backed, editable policy rule (MA3-92, 기획서 §5.2 정책 룰). Replaces the
 * static {@code @ConfigurationProperties} rules (MA3-86) as the source of truth:
 * rules can now be created/updated/deleted at runtime via
 * {@code /api/settings/policy-rules} with no redeploy.
 *
 * <p>{@code condition} is a small jsonb object of ANDed conditions
 * ({@code {minRiskScore?, severity?, findingCategory?, nameRegex?}}); an omitted
 * key is ignored. Rules are evaluated in ascending {@code priority} order and the
 * first matching enabled rule's {@code action} wins (so give the strictest the
 * lowest priority). Actions: {@code AUTO_BLOCK} (status → BLOCKED) | {@code HOLD}.
 */
@Entity
@Table(name = "policy_rule")
@Filter(name = OrgScoped.FILTER, condition = OrgScoped.CONDITION)
public class PolicyRule {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "priority", nullable = false)
    private int priority;

    // jsonb {minRiskScore?, severity?, findingCategory?, nameRegex?}; mapped natively.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "condition", nullable = false, columnDefinition = "jsonb")
    private String condition;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 16)
    private PolicyProperties.Action action;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected PolicyRule() {}

    public PolicyRule(UUID id, UUID orgId, String name, int priority, String condition,
                      PolicyProperties.Action action, boolean enabled) {
        this.id = id;
        this.orgId = orgId;
        this.name = name;
        this.priority = priority;
        this.condition = condition == null || condition.isBlank() ? "{}" : condition;
        this.action = action;
        this.enabled = enabled;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public String getCondition() {
        return condition;
    }

    public void setCondition(String condition) {
        this.condition = condition == null || condition.isBlank() ? "{}" : condition;
    }

    public PolicyProperties.Action getAction() {
        return action;
    }

    public void setAction(PolicyProperties.Action action) {
        this.action = action;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
