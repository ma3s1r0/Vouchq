package com.vouchq.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Append-only, hash-chained audit log writer + verifier (MA3-79, 기획서 §6 / §10).
 *
 * <p><strong>Append-only contract:</strong> this service exposes only
 * {@code append}, {@code verifyChain}, and read helpers — it never updates or
 * deletes a row. Each entry's {@code entry_hash} is chained off the previous
 * entry's {@code entry_hash} ({@code prev_hash}), so mutating, reordering, or
 * dropping any historical row breaks the chain at that point.
 *
 * <h2>Canonical serialization (hash recipe — must stay byte-stable)</h2>
 * <p>{@code entry_hash = lowerhex( SHA-256( UTF-8( canonical ) ) )} where
 * {@code canonical} is the following seven fields joined by a single newline
 * {@code '\n'}, in this exact order:
 * <pre>
 *   1. prev_hash      (empty string if null — i.e. the first entry of an org)
 *   2. org_id         (UUID.toString())
 *   3. actor
 *   4. action
 *   5. target_id      (UUID.toString(), or empty string if null)
 *   6. payload        (CANONICAL JSON — keys sorted lexicographically, compact,
 *                      no insignificant whitespace; see below)
 *   7. created_at     (ISO-8601 instant in UTC, microsecond precision:
 *                      e.g. 2026-06-13T09:41:22.123456Z)
 * </pre>
 * Notes that keep verification reproducible:
 * <ul>
 *   <li>{@code created_at} is normalized to UTC and truncated to microseconds
 *       so the Java value folded into the hash equals the Postgres
 *       {@code timestamptz} round-trip (Postgres stores microsecond precision).</li>
 *   <li>{@code payload} is hashed in a <em>canonical JSON</em> form: parsed and
 *       re-serialized with object keys sorted and no insignificant whitespace.
 *       This is essential because the column is {@code jsonb} — Postgres does
 *       not preserve key order or whitespace, so hashing the raw text would not
 *       survive the storage round-trip. Canonicalizing on both write and verify
 *       makes the hash invariant to how {@code jsonb} stores the value.</li>
 *   <li>No field separators other than the single {@code '\n'}; nulls collapse
 *       to empty strings (not the literal "null").</li>
 * </ul>
 */
@Service
public class AuditLogService {

    /** Microsecond ISO instant, e.g. {@code 2026-06-13T09:41:22.123456Z}. */
    private static final DateTimeFormatter CANONICAL_TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'");

    /**
     * Dedicated mapper that sorts object keys, so payload canonicalization is
     * stable regardless of the order callers built their JSON in.
     */
    private static final ObjectMapper CANONICAL_JSON = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private final AuditLogRepository repository;

    @PersistenceContext
    private EntityManager entityManager;

    public AuditLogService(AuditLogRepository repository) {
        this.repository = repository;
    }

    /**
     * Append one entry to the org's chain and return the persisted row.
     *
     * <p>Runs inside the caller's transaction (no new propagation) so the log
     * row commits together with the governed action it records.
     *
     * @param orgId    tenant
     * @param actor    user or system identity performing the action
     * @param action   action code (e.g. {@code SOURCE_CONNECTED}, 기획서 §6)
     * @param targetId optional subject of the action (tool/source id), may be null
     * @param payload  canonical JSON string with action context, must be non-null
     */
    @Transactional
    public AuditLog append(UUID orgId, String actor, String action, UUID targetId, String payload) {
        if (orgId == null || actor == null || action == null || payload == null) {
            throw new IllegalArgumentException("orgId, actor, action, payload are required");
        }
        // Serialize appends per org so concurrent writers can't both read the same
        // tail entry as prev_hash and fork the chain (MA3-109). The transaction-scoped
        // advisory lock is held until this tx commits and works across app instances.
        // (entityManager is null only in the pure-logic unit test, which is
        // single-threaded and uses a mock repo — no lock needed there.)
        if (entityManager != null) {
            entityManager
                    .createNativeQuery("SELECT pg_advisory_xact_lock(:key)")
                    .setParameter("key", advisoryKey(orgId))
                    .getSingleResult();
        }

        String prevHash = repository.findFirstByOrgIdOrderByIdDesc(orgId)
                .map(AuditLog::getEntryHash)
                .orElse(null);

        // Truncate to micros + UTC so the hashed timestamp == the persisted one.
        OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);

        String entryHash = computeEntryHash(prevHash, orgId, actor, action, targetId, payload, createdAt);

        return repository.save(new AuditLog(
                orgId, actor, action, targetId, payload, prevHash, entryHash, createdAt));
    }

    /** Stable 64-bit advisory-lock key for an org (folds the UUID's 128 bits). */
    private static long advisoryKey(UUID orgId) {
        return orgId.getMostSignificantBits() ^ orgId.getLeastSignificantBits();
    }

    /** Verify result; {@link #ok()} when the whole chain is intact. */
    public record VerifyResult(boolean valid, Long brokenEntryId, String reason) {
        public boolean ok() {
            return valid;
        }
        static VerifyResult intact() {
            return new VerifyResult(true, null, null);
        }
        static VerifyResult broken(Long id, String reason) {
            return new VerifyResult(false, id, reason);
        }
    }

    /**
     * Walk an org's chain in insertion order and verify, for every entry, that
     * (a) the recomputed {@code entry_hash} matches the stored one and (b) its
     * {@code prev_hash} equals the previous entry's stored {@code entry_hash}
     * (null for the first entry). Returns the first broken entry, or OK.
     */
    @Transactional(readOnly = true)
    public VerifyResult verifyChain(UUID orgId) {
        return verify(repository.findByOrgIdOrderByIdAsc(orgId));
    }

    /**
     * Pure verification over an ordered list of entries — no DB. Exposed for
     * unit testing the chain logic (and reused by {@link #verifyChain}).
     */
    public static VerifyResult verify(List<AuditLog> chain) {
        String expectedPrev = null;
        for (AuditLog e : chain) {
            if (!java.util.Objects.equals(expectedPrev, e.getPrevHash())) {
                return VerifyResult.broken(e.getId(),
                        "prev_hash does not match previous entry's entry_hash");
            }
            String recomputed = computeEntryHash(
                    e.getPrevHash(), e.getOrgId(), e.getActor(), e.getAction(),
                    e.getTargetId(), e.getPayload(), e.getCreatedAt());
            if (!recomputed.equals(e.getEntryHash())) {
                return VerifyResult.broken(e.getId(),
                        "entry_hash does not match recomputed hash (record tampered)");
            }
            expectedPrev = e.getEntryHash();
        }
        return VerifyResult.intact();
    }

    /** Compute {@code entry_hash} per the documented canonical recipe. */
    public static String computeEntryHash(String prevHash, UUID orgId, String actor, String action,
                                          UUID targetId, String payload, OffsetDateTime createdAt) {
        String canonical = String.join("\n",
                nullToEmpty(prevHash),
                orgId.toString(),
                actor,
                action,
                targetId == null ? "" : targetId.toString(),
                canonicalizePayload(payload),
                createdAt.withOffsetSameInstant(ZoneOffset.UTC).format(CANONICAL_TS));
        return sha256Hex(canonical);
    }

    /**
     * Canonical JSON: parse {@code payload} into a generic Map/List tree and
     * re-serialize with keys sorted and no whitespace. Invariant to jsonb's
     * storage formatting and to the caller's original key order. Non-JSON or
     * blank payloads hash as their raw bytes (defensive — callers pass JSON).
     */
    static String canonicalizePayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return nullToEmpty(payload);
        }
        try {
            Object tree = CANONICAL_JSON.readValue(payload, Object.class);
            return CANONICAL_JSON.writeValueAsString(tree);
        } catch (JsonProcessingException e) {
            return payload;
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
