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
 * An MCP server or a Skill bundle (기획서 §6). The upper concept that groups the
 * individual {@link Tool}s discovered from a {@link Source}.
 */
@Entity
@Table(name = "registered_server")
@Filter(name = OrgScoped.FILTER, condition = OrgScoped.CONDITION)
public class RegisteredServer {

    /** Mirrors the {@code registered_server.kind} CHECK constraint in V2. */
    public enum Kind {
        SKILL_BUNDLE,
        MCP_SERVER
    }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "source_id", nullable = false)
    private UUID sourceId;

    @Column(name = "kind", nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private Kind kind;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected RegisteredServer() {}

    public RegisteredServer(UUID id, UUID orgId, UUID sourceId, Kind kind, String name) {
        this.id = id;
        this.orgId = orgId;
        this.sourceId = sourceId;
        this.kind = kind;
        this.name = name;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getSourceId() {
        return sourceId;
    }

    public Kind getKind() {
        return kind;
    }

    public String getName() {
        return name;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
