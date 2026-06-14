package com.vouchq.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vouchq.policy.FindingSuppression;
import com.vouchq.policy.FindingSuppressionService;
import com.vouchq.registry.ScanResult;
import com.vouchq.scanner.Finding;
import com.vouchq.scanner.SkillScanner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Builds the API view of a stored {@link ScanResult}, applying false-positive
 * suppression at read time (MA3-94). The raw {@code scan_result} row is never
 * mutated: the raw risk/severity/findings are preserved for transparency, and an
 * <em>effective</em> risk/severity is computed over the non-suppressed findings
 * (each finding is annotated with a {@code suppressed} flag).
 */
@Component
public class ScanViewAssembler {

    private final FindingSuppressionService suppressionService;
    private final ObjectMapper objectMapper;

    public ScanViewAssembler(FindingSuppressionService suppressionService, ObjectMapper objectMapper) {
        this.suppressionService = suppressionService;
        this.objectMapper = objectMapper;
    }

    /** The parsed findings of a stored scan plus the org's active suppressions. */
    public record Effective(int riskScore, String highestSeverity, ArrayNode annotatedFindings) {}

    /**
     * Compute the effective risk/severity and annotated findings for one scan.
     * Findings that fail to parse are passed through unannotated (defensive — the
     * scanner always writes well-formed findings).
     */
    public Effective compute(UUID orgId, UUID toolId, ScanResult scan) {
        List<Finding> findings = parse(scan.getFindings());
        List<FindingSuppression> active = suppressionService.active(orgId);

        ArrayNode out = objectMapper.createArrayNode();
        List<Finding> kept = new java.util.ArrayList<>();
        for (Finding f : findings) {
            boolean suppressed = FindingSuppressionService.isSuppressed(f, toolId, active);
            if (!suppressed) {
                kept.add(f);
            }
            ObjectNode node = objectMapper.valueToTree(f);
            node.put("fingerprint", FindingSuppressionService.fingerprint(f));
            node.put("suppressed", suppressed);
            out.add(node);
        }

        com.vouchq.scanner.ScanResult eff = SkillScanner.summarize(kept);
        String effHighest = eff.highestSeverity() == null ? null : eff.highestSeverity().name();
        return new Effective(eff.riskScore(), effHighest, out);
    }

    /** Build the full {@link ApiDtos.ScanResultView} for a tool's scan. */
    public ApiDtos.ScanResultView toView(UUID orgId, UUID toolId, ScanResult scan) {
        Effective e = compute(orgId, toolId, scan);
        return new ApiDtos.ScanResultView(
                scan.getRiskScore(), scan.getHighestSeverity(),
                e.riskScore(), e.highestSeverity(),
                e.annotatedFindings().toString(), scan.getScannedAt());
    }

    private List<Finding> parse(String findingsJson) {
        if (findingsJson == null || findingsJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(findingsJson);
            if (!node.isArray()) {
                return List.of();
            }
            return objectMapper.convertValue(node, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, Finding.class));
        } catch (Exception e) {
            return List.of();
        }
    }
}
