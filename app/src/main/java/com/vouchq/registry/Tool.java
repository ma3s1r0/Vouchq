package com.vouchq.registry;

import com.vouchq.tenancy.OrgScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Filter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * An individual tool/skill (기획서 §6). Tracks the latest observed version
 * ({@code current_version_id}) and, once approved, the pinned canonical version.
 */
@Entity
@Table(name = "tool")
@Filter(name = OrgScoped.FILTER, condition = OrgScoped.CONDITION)
public class Tool {

    /** Mirrors the {@code tool.kind} CHECK constraint in V2. */
    public enum Kind {
        SKILL,
        MCP_TOOL
    }

    /** Mirrors the {@code tool.status} CHECK constraint in V2. */
    public enum Status {
        PENDING,
        APPROVED,
        DRIFTED,
        BLOCKED
    }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "server_id", nullable = false)
    private UUID serverId;

    @Column(name = "kind", nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private Kind kind;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "status", nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private Status status;

    @Column(name = "current_version_id")
    private UUID currentVersionId;

    @Column(name = "approved_version_id")
    private UUID approvedVersionId;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    // App-managed so re-ingest bumps it; the DB default only covers the insert.
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected Tool() {}

    public Tool(UUID id, UUID orgId, UUID serverId, Kind kind, String name, Status status) {
        this.id = id;
        this.orgId = orgId;
        this.serverId = serverId;
        this.kind = kind;
        this.name = name;
        this.status = status;
        this.updatedAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getServerId() {
        return serverId;
    }

    public Kind getKind() {
        return kind;
    }

    public String getName() {
        return name;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public UUID getCurrentVersionId() {
        return currentVersionId;
    }

    public void setCurrentVersionId(UUID currentVersionId) {
        this.currentVersionId = currentVersionId;
    }

    public UUID getApprovedVersionId() {
        return approvedVersionId;
    }

    public void setApprovedVersionId(UUID approvedVersionId) {
        this.approvedVersionId = approvedVersionId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void touch() {
        this.updatedAt = OffsetDateTime.now();
    }
}
