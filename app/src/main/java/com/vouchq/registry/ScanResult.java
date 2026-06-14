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
 * The risk-scan outcome for one {@link ToolVersion} (기획서 §6). Produced by the
 * OSS {@code com.vouchq.scanner.SkillScanner} when a new version is ingested and
 * persisted to the {@code scan_result} table: an aggregate {@code risk_score}
 * (0–100), the {@code highest_severity} seen (null when clean), and the raw
 * findings as jsonb. This is what the policy engine (MA3-86) evaluates and what
 * the API surfaces as the tool's risk.
 */
@Entity
@Table(name = "scan_result")
@Filter(name = OrgScoped.FILTER, condition = OrgScoped.CONDITION)
public class ScanResult {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "tool_version_id", nullable = false)
    private UUID toolVersionId;

    @Column(name = "risk_score", nullable = false)
    private int riskScore;

    // Nullable: clean scans have no highest severity. CHECK INFO/WARN/CRITICAL in V2.
    @Column(name = "highest_severity", length = 16)
    private String highestSeverity;

    // jsonb array of findings; Hibernate 6 maps a JSON-typed String natively.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "findings", nullable = false, columnDefinition = "jsonb")
    private String findings;

    @Column(name = "scanned_at", nullable = false)
    private OffsetDateTime scannedAt;

    protected ScanResult() {}

    public ScanResult(UUID id, UUID orgId, UUID toolVersionId, int riskScore,
                      String highestSeverity, String findings, OffsetDateTime scannedAt) {
        this.id = id;
        this.orgId = orgId;
        this.toolVersionId = toolVersionId;
        this.riskScore = riskScore;
        this.highestSeverity = highestSeverity;
        this.findings = findings;
        this.scannedAt = scannedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getToolVersionId() {
        return toolVersionId;
    }

    public int getRiskScore() {
        return riskScore;
    }

    public String getHighestSeverity() {
        return highestSeverity;
    }

    public String getFindings() {
        return findings;
    }

    public OffsetDateTime getScannedAt() {
        return scannedAt;
    }
}
