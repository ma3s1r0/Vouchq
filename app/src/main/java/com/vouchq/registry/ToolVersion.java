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
 * A point-in-time snapshot of a tool's definition plus its SHA-256 hash
 *. {@code definition} is the serialized {@code ParsedSkill}; the
 * {@code hash} is the parser's stable {@code definitionHash}. New observations
 * with a changed hash become new rows — the foundation for drift detection.
 */
@Entity
@Table(name = "tool_version")
@Filter(name = OrgScoped.FILTER, condition = OrgScoped.CONDITION)
public class ToolVersion {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "tool_id", nullable = false)
    private UUID toolId;

    // jsonb in Postgres; Hibernate 6 maps a JSON-typed String natively.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "definition", nullable = false, columnDefinition = "jsonb")
    private String definition;

    // char(64) in PG reports as JDBC CHAR (bpchar); map it explicitly so
    // ddl-auto:validate matches (a plain String otherwise expects VARCHAR).
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "hash", nullable = false, length = 64)
    private String hash;

    @Column(name = "observed_at", nullable = false)
    private OffsetDateTime observedAt;

    protected ToolVersion() {}

    public ToolVersion(UUID id, UUID orgId, UUID toolId, String definition, String hash,
                       OffsetDateTime observedAt) {
        this.id = id;
        this.orgId = orgId;
        this.toolId = toolId;
        this.definition = definition;
        this.hash = hash;
        this.observedAt = observedAt;
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

    public String getDefinition() {
        return definition;
    }

    public String getHash() {
        return hash;
    }

    public OffsetDateTime getObservedAt() {
        return observedAt;
    }
}
