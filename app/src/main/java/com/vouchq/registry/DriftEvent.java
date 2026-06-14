package com.vouchq.registry;

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
 * A detected mismatch between a tool's pinned (박제) definition and its currently
 * observed definition (기획서 §6, "rug-pull" detection). Created by
 * {@link DriftDetectionService} when a re-scan finds the current
 * {@link ToolVersion} hash differs from the {@link ApprovedVersion} hash.
 *
 * <p>The {@code diff} jsonb records what changed (description, files added /
 * removed / changed by path + sha256) so the console can show a human-readable
 * delta without re-deriving it. Rows are immutable except for {@code resolved},
 * which is flipped when an engineer re-approves or blocks the tool.
 */
@Entity
@Table(name = "drift_event")
@Filter(name = OrgScoped.FILTER, condition = OrgScoped.CONDITION)
public class DriftEvent {

    /** Mirrors the {@code drift_event.severity} CHECK constraint in V2. */
    public enum Severity {
        INFO,
        WARN,
        CRITICAL
    }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "tool_id", nullable = false, updatable = false)
    private UUID toolId;

    // char(64) in PG reports as JDBC CHAR (bpchar); map it explicitly so
    // ddl-auto:validate matches (a plain String otherwise expects VARCHAR).
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "approved_hash", nullable = false, length = 64, updatable = false)
    private String approvedHash;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "observed_hash", nullable = false, length = 64, updatable = false)
    private String observedHash;

    // jsonb in Postgres; Hibernate 6 maps a JSON-typed String natively.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "diff", nullable = false, columnDefinition = "jsonb", updatable = false)
    private String diff;

    @Column(name = "severity", nullable = false, length = 16, updatable = false)
    @Enumerated(EnumType.STRING)
    private Severity severity;

    @Column(name = "detected_at", nullable = false, updatable = false)
    private OffsetDateTime detectedAt;

    @Column(name = "resolved", nullable = false)
    private boolean resolved;

    protected DriftEvent() {}

    public DriftEvent(UUID id, UUID orgId, UUID toolId, String approvedHash, String observedHash,
                      String diff, Severity severity, OffsetDateTime detectedAt) {
        this.id = id;
        this.orgId = orgId;
        this.toolId = toolId;
        this.approvedHash = approvedHash;
        this.observedHash = observedHash;
        this.diff = diff;
        this.severity = severity;
        this.detectedAt = detectedAt;
        this.resolved = false;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getToolId() {
        return toolId;
    }

    public String getApprovedHash() {
        return approvedHash;
    }

    public String getObservedHash() {
        return observedHash;
    }

    public String getDiff() {
        return diff;
    }

    public Severity getSeverity() {
        return severity;
    }

    public OffsetDateTime getDetectedAt() {
        return detectedAt;
    }

    public boolean isResolved() {
        return resolved;
    }

    public void resolve() {
        this.resolved = true;
    }
}
