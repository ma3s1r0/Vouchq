package com.vouchq.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure serializer tests for the integrity export (MA3-91). No DB: builds an
 * in-memory chain and asserts CSV/JSON shape + that the chain-verification
 * result is embedded. Covers both an intact chain and a tampered one.
 */
class AuditExportServiceTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID TARGET = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Build a real, valid 2-entry chain via the canonical hash recipe. */
    private List<AuditLog> validChain() {
        OffsetDateTime t = OffsetDateTime.of(2026, 6, 13, 9, 0, 0, 0, ZoneOffset.UTC);
        String p1 = "{\"b\":2,\"a\":1}";
        String h1 = AuditLogService.computeEntryHash(null, ORG, "sys", "SOURCE_CONNECTED", TARGET, p1, t);
        AuditLog e1 = new AuditLog(ORG, "sys", "SOURCE_CONNECTED", TARGET, p1, null, h1, t);
        e1.assignIdForTest(1L);

        String p2 = "{\"k\":\"v, with comma\"}";
        String h2 = AuditLogService.computeEntryHash(h1, ORG, "alice", "TOOL_APPROVED", null, p2, t);
        AuditLog e2 = new AuditLog(ORG, "alice", "TOOL_APPROVED", null, p2, h1, h2, t);
        e2.assignIdForTest(2L);
        return List.of(e1, e2);
    }

    @Test
    void jsonHasVerificationAndAllEntriesInOrder() throws Exception {
        List<AuditLog> chain = validChain();
        AuditLogService.VerifyResult v = AuditLogService.verify(chain);
        assertThat(v.valid()).isTrue();

        JsonNode root = MAPPER.readTree(AuditExportService.toJson(chain, v));

        assertThat(root.get("verification").get("valid").asBoolean()).isTrue();
        assertThat(root.get("verification").get("firstBrokenId").isNull()).isTrue();

        JsonNode entries = root.get("entries");
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).get("id").asLong()).isEqualTo(1L);
        assertThat(entries.get(0).get("prevHash").isNull()).isTrue();
        assertThat(entries.get(0).get("entryHash").asText()).isEqualTo(chain.get(0).getEntryHash());
        // payload embedded as a JSON object, not a quoted string.
        assertThat(entries.get(0).get("payload").get("a").asInt()).isEqualTo(1);
        assertThat(entries.get(1).get("prevHash").asText()).isEqualTo(chain.get(0).getEntryHash());
        assertThat(entries.get(1).get("targetId").isNull()).isTrue();
    }

    @Test
    void jsonReportsFirstBrokenIdWhenTampered() throws Exception {
        List<AuditLog> chain = validChain();
        chain.get(1).tamperPayloadForTest("{\"a\":999}"); // breaks entry 2's hash
        AuditLogService.VerifyResult v = AuditLogService.verify(chain);

        JsonNode root = MAPPER.readTree(AuditExportService.toJson(chain, v));
        assertThat(root.get("verification").get("valid").asBoolean()).isFalse();
        assertThat(root.get("verification").get("firstBrokenId").asLong()).isEqualTo(2L);
        // full log still exported (all rows present) — evidence, not truncation.
        assertThat(root.get("entries")).hasSize(2);
    }

    @Test
    void csvHasHeaderVerificationCommentsAndQuotedFields() {
        List<AuditLog> chain = validChain();
        AuditLogService.VerifyResult v = AuditLogService.verify(chain);

        String csv = AuditExportService.toCsv(chain, v);
        String[] lines = csv.split("\n");

        assertThat(lines[0]).isEqualTo("# chain_valid,true");
        assertThat(lines[1]).isEqualTo("# first_broken_id,");
        assertThat(lines[3])
                .isEqualTo("id,org_id,actor,action,target_id,payload,prev_hash,entry_hash,created_at");
        // 3 comment lines + header + 2 data rows
        assertThat(lines).hasSize(6);
        assertThat(lines[4]).startsWith("1," + ORG + ",sys,SOURCE_CONNECTED," + TARGET + ",");
        // payload of entry 2 contains a comma -> must be RFC-4180 quoted.
        assertThat(lines[5]).contains("\"{\"\"k\"\":\"\"v, with comma\"\"}\"");
    }
}
