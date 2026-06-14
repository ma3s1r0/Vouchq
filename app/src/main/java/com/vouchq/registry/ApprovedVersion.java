package com.vouchq.registry;

import com.vouchq.tenancy.OrgScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The approved / pinned canonical version of a tool (기획서 §6, "박제"). When an
 * engineer approves a {@link Tool}, the current {@link ToolVersion}'s id and
 * SHA-256 {@code hash} are captured here together with who approved it and when.
 *
 * <p>This row is the authoritative trust record and is <strong>immutable</strong>:
 * a re-approval (e.g. after drift) creates a NEW {@code ApprovedVersion} row
 * rather than mutating an existing one, preserving the audit trail of every
 * pin decision. {@code tool.approved_version_id} points at the latest one.
 */
@Entity
@Table(name = "approved_version")
@Filter(name = OrgScoped.FILTER, condition = OrgScoped.CONDITION)
public class ApprovedVersion {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "tool_id", nullable = false, updatable = false)
    private UUID toolId;

    @Column(name = "tool_version_id", nullable = false, updatable = false)
    private UUID toolVersionId;

    // char(64) in PG reports as JDBC CHAR (bpchar); map it explicitly so
    // ddl-auto:validate matches (a plain String otherwise expects VARCHAR).
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "hash", nullable = false, length = 64, updatable = false)
    private String hash;

    @Column(name = "approved_by", nullable = false, updatable = false)
    private String approvedBy;

    @Column(name = "approved_at", nullable = false, updatable = false)
    private OffsetDateTime approvedAt;

    protected ApprovedVersion() {}

    public ApprovedVersion(UUID id, UUID orgId, UUID toolId, UUID toolVersionId, String hash,
                           String approvedBy, OffsetDateTime approvedAt) {
        this.id = id;
        this.orgId = orgId;
        this.toolId = toolId;
        this.toolVersionId = toolVersionId;
        this.hash = hash;
        this.approvedBy = approvedBy;
        this.approvedAt = approvedAt;
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

    public UUID getToolVersionId() {
        return toolVersionId;
    }

    public String getHash() {
        return hash;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public OffsetDateTime getApprovedAt() {
        return approvedAt;
    }
}
