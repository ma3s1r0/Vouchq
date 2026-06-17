package com.vouchq.audit;

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
 * Append-only, hash-chained audit log entry. Rows are written
 * by {@link AuditLogService} (MA3-79) and never updated or deleted — the chain
 * is what makes the log tamper-evident. This entity is the read view backing
 * {@code GET /api/audit-logs} plus the constructor/getters the writer needs.
 *
 * <p>{@code created_at} is set explicitly by the writer (not DB-defaulted) so
 * the value folded into {@code entry_hash} matches the value persisted exactly;
 * the V2 {@code DEFAULT now()} only guards rows inserted outside this path.
 */
@Entity
@Table(name = "audit_log")
@Filter(name = OrgScoped.FILTER, condition = OrgScoped.CONDITION)
public class AuditLog {

    @Id
    @jakarta.persistence.GeneratedValue(strategy = jakarta.persistence.GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "actor", nullable = false)
    private String actor;

    @Column(name = "action", nullable = false, length = 64)
    private String action;

    @Column(name = "target_id")
    private UUID targetId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "prev_hash", length = 64)
    private String prevHash;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "entry_hash", nullable = false, length = 64)
    private String entryHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected AuditLog() {}

    /**
     * Writer constructor. The {@code entryHash} must already be computed over
     * the canonical serialization of these fields (see {@link AuditLogService}).
     */
    AuditLog(UUID orgId, String actor, String action, UUID targetId, String payload,
             String prevHash, String entryHash, OffsetDateTime createdAt) {
        this.orgId = orgId;
        this.actor = actor;
        this.action = action;
        this.targetId = targetId;
        this.payload = payload;
        this.prevHash = prevHash;
        this.entryHash = entryHash;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public String getActor() {
        return actor;
    }

    public String getAction() {
        return action;
    }

    public UUID getTargetId() {
        return targetId;
    }

    public String getPayload() {
        return payload;
    }

    public String getPrevHash() {
        return prevHash;
    }

    public String getEntryHash() {
        return entryHash;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    // --- package-private test support -----------------------------------------
    // These exist only so the chain unit test can assign synthetic ids and
    // simulate tampering of an at-rest row. They are NOT part of the write API
    // (the service builds rows via the constructor above and never mutates).

    void assignIdForTest(Long id) {
        this.id = id;
    }

    void tamperPayloadForTest(String payload) {
        this.payload = payload;
    }

    void tamperEntryHashForTest(String entryHash) {
        this.entryHash = entryHash;
    }
}
