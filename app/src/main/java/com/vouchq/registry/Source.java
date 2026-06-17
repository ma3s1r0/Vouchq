package com.vouchq.registry;

import com.vouchq.tenancy.OrgScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Filter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A connected source: a Git repository, an uploaded bundle, or an MCP
 * server endpoint. {@code authRef} holds the source credential <em>encrypted at
 * rest</em> ({@code com.vouchq.credentials.CredentialCipher} ciphertext) — never the
 * raw token (secrets encrypted at rest).
 */
@Entity
@Table(name = "source")
@Filter(name = OrgScoped.FILTER, condition = OrgScoped.CONDITION)
public class Source {

    /** Mirrors the {@code source.type} CHECK constraint in V2. */
    public enum Type {
        GIT_REPOSITORY,
        FILE_UPLOAD,
        MCP_SERVER
    }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "type", nullable = false, length = 20)
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private Type type;

    @Column(name = "uri", nullable = false, length = 2048)
    private String uri;

    @Column(name = "auth_ref")
    private String authRef;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Source() {}

    public Source(UUID id, UUID orgId, Type type, String uri, String authRef) {
        this.id = id;
        this.orgId = orgId;
        this.type = type;
        this.uri = uri;
        this.authRef = authRef;
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

    public String getUri() {
        return uri;
    }

    public String getAuthRef() {
        return authRef;
    }

    /**
     * Replace the stored (encrypted) credential — credential rotation without
     * recreating the source (MA3-89). Pass {@code null} to clear it. The caller is
     * responsible for passing ciphertext, not a raw token.
     */
    public void setAuthRef(String authRef) {
        this.authRef = authRef;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
