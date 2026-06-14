package com.vouchq.notify;

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
 * A DB-backed, editable outbound notification channel (MA3-92, 기획서 §5.2 알림 채널).
 * Replaces the static {@code @ConfigurationProperties}-driven channel beans
 * (MA3-85) as the source of truth: channels can now be created/updated/deleted at
 * runtime via {@code /api/settings/notification-channels} with no redeploy.
 *
 * <p>{@code target} is the primary address (webhook/Slack URL, or the email
 * {@code from} address); {@code config} is a small jsonb bag of type-specific
 * extras (e.g. EMAIL {@code {"to":["ops@x"]}}). A row is only ever contacted when
 * {@code enabled=true}, preserving the self-hosted default-off rule (기획서 §7):
 * with no enabled rows {@link NotificationService} makes zero outbound calls.
 */
@Entity
@Table(name = "notification_channel")
@Filter(name = OrgScoped.FILTER, condition = OrgScoped.CONDITION)
public class NotificationChannelEntity {

    /** Mirrors the {@code notification_channel.type} CHECK constraint in V5. */
    public enum Type {
        WEBHOOK,
        SLACK,
        EMAIL
    }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    private Type type;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "target", nullable = false, length = 2048)
    private String target;

    // jsonb bag of type-specific extras; Hibernate 6 maps a JSON-typed String natively.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config", nullable = false, columnDefinition = "jsonb")
    private String config;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected NotificationChannelEntity() {}

    public NotificationChannelEntity(UUID id, UUID orgId, Type type, String name,
                                     String target, String config, boolean enabled) {
        this.id = id;
        this.orgId = orgId;
        this.type = type;
        this.name = name;
        this.target = target;
        this.config = config == null || config.isBlank() ? "{}" : config;
        this.enabled = enabled;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public String getConfig() {
        return config;
    }

    public void setConfig(String config) {
        this.config = config == null || config.isBlank() ? "{}" : config;
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
