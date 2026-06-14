package com.vouchq.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure-logic test of the hash-chained audit log (MA3-79, 기획서 §10). Uses an
 * in-memory fake repository (no DB / Testcontainers) so it runs in the build
 * container. Covers the 완료 기준: build a multi-entry chain, verify it passes,
 * then tamper a record and assert {@code verifyChain} FAILS at the broken entry.
 */
class AuditLogServiceTest {

    private static final UUID ORG = UUID.randomUUID();

    private AuditLogService service;
    private final List<AuditLog> store = new ArrayList<>();
    private final AtomicLong seq = new AtomicLong();

    @BeforeEach
    void setUp() {
        AuditLogRepository repo = mock(AuditLogRepository.class);

        when(repo.save(any(AuditLog.class))).thenAnswer(inv -> {
            AuditLog e = inv.getArgument(0);
            e.assignIdForTest(seq.incrementAndGet());
            store.add(e);
            return e;
        });
        when(repo.findFirstByOrgIdOrderByIdDesc(any())).thenAnswer(inv -> {
            UUID org = inv.getArgument(0);
            return store.stream()
                    .filter(e -> e.getOrgId().equals(org))
                    .max(Comparator.comparing(AuditLog::getId));
        });
        when(repo.findByOrgIdOrderByIdAsc(any())).thenAnswer(inv -> {
            UUID org = inv.getArgument(0);
            return store.stream()
                    .filter(e -> e.getOrgId().equals(org))
                    .sorted(Comparator.comparing(AuditLog::getId))
                    .toList();
        });

        service = new AuditLogService(repo);
    }

    @Test
    void firstEntryHasNullPrevHashAndLaterEntriesChain() {
        AuditLog a = service.append(ORG, "alice", "SOURCE_CONNECTED", UUID.randomUUID(), "{\"a\":1}");
        AuditLog b = service.append(ORG, "bob", "TOOL_APPROVED", UUID.randomUUID(), "{\"b\":2}");
        AuditLog c = service.append(ORG, "system", "DRIFT_DETECTED", UUID.randomUUID(), "{\"c\":3}");

        assertThat(a.getPrevHash()).isNull();
        assertThat(a.getEntryHash()).isNotBlank();

        // Each row's prev_hash == previous row's entry_hash.
        assertThat(b.getPrevHash()).isEqualTo(a.getEntryHash());
        assertThat(c.getPrevHash()).isEqualTo(b.getEntryHash());
    }

    @Test
    void verifyChainPassesForIntactChain() {
        service.append(ORG, "alice", "SOURCE_CONNECTED", null, "{\"a\":1}");
        service.append(ORG, "alice", "SCAN_RUN", null, "{\"b\":2}");
        service.append(ORG, "bob", "TOOL_APPROVED", null, "{\"c\":3}");
        service.append(ORG, "system", "DRIFT_DETECTED", null, "{\"d\":4}");

        AuditLogService.VerifyResult result = service.verifyChain(ORG);

        assertThat(result.ok()).isTrue();
        assertThat(result.brokenEntryId()).isNull();
    }

    @Test
    void tamperingPayloadBreaksChainAtThatEntry() {
        service.append(ORG, "alice", "SOURCE_CONNECTED", null, "{\"a\":1}");
        AuditLog target = service.append(ORG, "alice", "SCAN_RUN", null, "{\"b\":2}");
        service.append(ORG, "bob", "TOOL_APPROVED", null, "{\"c\":3}");

        assertThat(service.verifyChain(ORG).ok()).isTrue();

        // Mutate the at-rest payload without recomputing entry_hash.
        target.tamperPayloadForTest("{\"b\":999}");

        AuditLogService.VerifyResult result = service.verifyChain(ORG);
        assertThat(result.ok()).isFalse();
        assertThat(result.brokenEntryId()).isEqualTo(target.getId());
        assertThat(result.reason()).contains("entry_hash");
    }

    @Test
    void tamperingEntryHashBreaksChainAtNextEntryViaPrevLinkage() {
        AuditLog first = service.append(ORG, "alice", "SOURCE_CONNECTED", null, "{\"a\":1}");
        AuditLog second = service.append(ORG, "alice", "SCAN_RUN", null, "{\"b\":2}");
        service.append(ORG, "bob", "TOOL_APPROVED", null, "{\"c\":3}");

        // Overwrite the stored entry_hash of the first row. Its own recompute now
        // fails first, but this also proves the prev->entry linkage is checked.
        first.tamperEntryHashForTest("0".repeat(64));

        AuditLogService.VerifyResult result = service.verifyChain(ORG);
        assertThat(result.ok()).isFalse();
        // The first entry is where the recomputed hash diverges.
        assertThat(result.brokenEntryId()).isEqualTo(first.getId());
        assertThat(second.getPrevHash()).isNotEqualTo(first.getEntryHash());
    }

    @Test
    void prevLinkageBreakIsReportedEvenWhenEntryHashSelfConsistent() {
        // Build a synthetic chain where entry #2's entry_hash is internally valid
        // but its prev_hash does NOT point at entry #1's entry_hash.
        UUID org = UUID.randomUUID();
        var e1 = makeEntry(1L, org, null, "alice", "A", "{\"x\":1}");
        // e2.prev deliberately wrong (null instead of e1.entryHash) but self-consistent.
        var e2 = makeEntry(2L, org, null, "bob", "B", "{\"y\":2}");

        AuditLogService.VerifyResult result =
                AuditLogService.verify(List.of(e1, e2));

        assertThat(result.ok()).isFalse();
        assertThat(result.brokenEntryId()).isEqualTo(2L);
        assertThat(result.reason()).contains("prev_hash");
    }

    @Test
    void appendRejectsNullPayload() {
        assertThatThrownBy(() -> service.append(ORG, "alice", "A", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Build a self-consistent entry (entry_hash computed from its own fields). */
    private static AuditLog makeEntry(Long id, UUID org, String prevHash,
                                      String actor, String action, String payload) {
        var createdAt = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)
                .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        String entryHash = AuditLogService.computeEntryHash(
                prevHash, org, actor, action, null, payload, createdAt);
        AuditLog e = new AuditLog(org, actor, action, null, payload, prevHash, entryHash, createdAt);
        e.assignIdForTest(id);
        return e;
    }
}
