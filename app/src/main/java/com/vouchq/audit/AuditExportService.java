package com.vouchq.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Tamper-evident integrity export of an org's full audit chain (MA3-91).
 *
 * <p>Unlike the capped UI list ({@code GET /api/audit-logs}), the export returns
 * the <em>complete</em> log in ascending id (chain) order, including each row's
 * {@code prev_hash} and {@code entry_hash}, plus an overall chain-verification
 * result. An auditor can recompute the chain offline and compare it to an
 * out-of-band copy — that, together with the DB-level WORM trigger (V4), is the
 * evidence that the log has not been rewritten.
 *
 * <p>Serialization is split into pure {@code toCsv}/{@code toJson} methods that
 * take an already-loaded chain + verification result, so the export shape is
 * unit-testable without a database.
 */
@Service
public class AuditExportService {

    /** ISO-8601 instant; matches the canonical hash timestamp form. */
    private static final DateTimeFormatter TS = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AuditLogRepository repository;

    public AuditExportService(AuditLogRepository repository) {
        this.repository = repository;
    }

    /** Output format negotiated from the {@code format} query param. */
    public enum Format { CSV, JSON }

    /** A ready-to-serve export: media type, suggested filename, and body. */
    public record Export(Format format, String filename, String body) {}

    /**
     * Load the org's full ascending chain, verify it, and render to {@code format}.
     * Runs read-only.
     */
    @Transactional(readOnly = true)
    public Export export(UUID orgId, Format format) {
        List<AuditLog> chain = repository.findByOrgIdOrderByIdAsc(orgId);
        AuditLogService.VerifyResult verification = AuditLogService.verify(chain);
        String body = switch (format) {
            case CSV -> toCsv(chain, verification);
            case JSON -> toJson(chain, verification);
        };
        String ext = format == Format.CSV ? "csv" : "json";
        return new Export(format, "audit-log-" + orgId + "." + ext, body);
    }

    /**
     * CSV with a header row. The chain-verification result is emitted as leading
     * comment lines (prefixed {@code #}) so the evidence travels with the file
     * while the data rows stay a clean, parseable CSV table.
     */
    public static String toCsv(List<AuditLog> chain, AuditLogService.VerifyResult v) {
        StringBuilder sb = new StringBuilder();
        sb.append("# chain_valid,").append(v.valid()).append('\n');
        sb.append("# first_broken_id,").append(v.brokenEntryId() == null ? "" : v.brokenEntryId()).append('\n');
        sb.append("# reason,").append(v.reason() == null ? "" : csv(v.reason())).append('\n');
        sb.append("id,org_id,actor,action,target_id,payload,prev_hash,entry_hash,created_at\n");
        for (AuditLog e : chain) {
            sb.append(e.getId()).append(',')
              .append(csv(str(e.getOrgId()))).append(',')
              .append(csv(e.getActor())).append(',')
              .append(csv(e.getAction())).append(',')
              .append(csv(str(e.getTargetId()))).append(',')
              .append(csv(e.getPayload())).append(',')
              .append(csv(nz(e.getPrevHash()))).append(',')
              .append(csv(nz(e.getEntryHash()))).append(',')
              .append(csv(e.getCreatedAt() == null ? "" : e.getCreatedAt().format(TS)))
              .append('\n');
        }
        return sb.toString();
    }

    /** JSON shape: {@code {verification:{...}, entries:[...]}}. */
    public static String toJson(List<AuditLog> chain, AuditLogService.VerifyResult v) {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode ver = root.putObject("verification");
        ver.put("valid", v.valid());
        if (v.brokenEntryId() == null) {
            ver.putNull("firstBrokenId");
        } else {
            ver.put("firstBrokenId", v.brokenEntryId());
        }
        ver.put("reason", v.reason());
        ArrayNode entries = root.putArray("entries");
        for (AuditLog e : chain) {
            ObjectNode n = entries.addObject();
            n.put("id", e.getId());
            n.put("orgId", str(e.getOrgId()));
            n.put("actor", e.getActor());
            n.put("action", e.getAction());
            n.put("targetId", e.getTargetId() == null ? null : e.getTargetId().toString());
            n.set("payload", payloadNode(e.getPayload()));
            n.put("prevHash", e.getPrevHash());
            n.put("entryHash", e.getEntryHash());
            n.put("createdAt", e.getCreatedAt() == null ? null : e.getCreatedAt().format(TS));
        }
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception ex) {
            throw new IllegalStateException("audit export JSON serialization failed", ex);
        }
    }

    /** payload is stored as jsonb text; embed it as a JSON value, not a string. */
    private static com.fasterxml.jackson.databind.JsonNode payloadNode(String payload) {
        if (payload == null) {
            return MAPPER.nullNode();
        }
        try {
            return MAPPER.readTree(payload);
        } catch (Exception e) {
            return MAPPER.getNodeFactory().textNode(payload);
        }
    }

    private static String str(UUID u) {
        return u == null ? "" : u.toString();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** RFC-4180 CSV field quoting. */
    private static String csv(String field) {
        String f = field == null ? "" : field;
        if (f.contains(",") || f.contains("\"") || f.contains("\n") || f.contains("\r")) {
            return '"' + f.replace("\"", "\"\"") + '"';
        }
        return f;
    }
}
