package com.vouchq.registry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vouchq.scanner.Finding;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Persists a scanner outcome ({@code com.vouchq.scanner.ScanResult}) as a
 * {@code scan_result} row for a {@link ToolVersion} (MA3-86, 기획서 §5.1 "위험 스캔").
 *
 * <p>Shared by the Git and MCP ingestion paths so both write risk the same way.
 * The scanner's findings list is serialized to the jsonb {@code findings} column;
 * {@code highest_severity} is null for a clean scan.
 */
@Service
public class ScanRecorder {

    private final ScanResultRepository scanResults;
    private final ObjectMapper objectMapper;

    public ScanRecorder(ScanResultRepository scanResults, ObjectMapper objectMapper) {
        this.scanResults = scanResults;
        this.objectMapper = objectMapper;
    }

    /**
     * Persist a scan outcome for a tool version. Runs in the caller's transaction
     * so the scan row commits with the version it describes.
     */
    @Transactional
    public ScanResult record(UUID orgId, UUID toolVersionId, com.vouchq.scanner.ScanResult result) {
        String highest = result.highestSeverity() == null ? null : result.highestSeverity().name();
        return scanResults.save(new ScanResult(
                UUID.randomUUID(), orgId, toolVersionId,
                result.riskScore(), highest, serializeFindings(result.findings()),
                OffsetDateTime.now()));
    }

    private String serializeFindings(List<Finding> findings) {
        try {
            return objectMapper.writeValueAsString(findings);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize scan findings", e);
        }
    }
}
