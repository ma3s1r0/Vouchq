-- ---------------------------------------------------------------------------
-- V4 — MA3-91: DB-level append-only (WORM) enforcement for audit_log.
--
-- Threat model
-- ------------
-- audit_log is hash-chained at the application layer (AuditLogService:
-- prev_hash -> entry_hash over a canonical 7-field record). That chain makes
-- tampering *detectable* by `GET /api/audit-logs/verify` and the export. But
-- "append-only" was, until now, only a service *convention* — nothing stopped a
-- row from being mutated or removed at the database itself. This migration
-- closes that gap by enforcing WORM (write-once, read-many) in Postgres, so the
-- guarantee survives even when the application code is bypassed.
--
-- What this defends against:
--   * A buggy or compromised application code path that issues an UPDATE/DELETE
--     against audit_log (e.g. a future ORM mapping mistake, an injection, or a
--     rogue endpoint) — the DB refuses it regardless of what the JVM does.
--   * An operator/insider who tries to rewrite history *and re-chain* it: an
--     attacker who can both edit a row and recompute every downstream entry_hash
--     could defeat the application-level hash chain (the chain would still
--     "verify"). Forbidding UPDATE/DELETE at the DB removes that re-chaining
--     path entirely — to alter the past they would have to drop the trigger,
--     which is itself a privileged, auditable schema change.
--
-- What this does NOT defend against: a superuser who can ALTER/DROP the trigger
-- or TRUNCATE the table (TRUNCATE does not fire row-level triggers). Defending
-- those requires OS-level controls / least-privilege DB roles / off-box export,
-- which are deployment concerns. The integrity export gives an
-- auditor tamper-evident evidence to compare against an out-of-band copy.
--
-- Mechanism: a BEFORE UPDATE OR DELETE row-level trigger that RAISE EXCEPTION.
-- INSERT is intentionally NOT covered, so the normal append path still works.
-- This is pure DDL (no column changes) so `ddl-auto: validate` stays happy.
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION audit_log_worm_guard()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'audit_log is append-only (WORM): % is not permitted (MA3-91)', TG_OP
        USING ERRCODE = 'integrity_constraint_violation',
              HINT = 'Audit entries are immutable; the hash chain makes tampering evident.';
END;
$$;

DROP TRIGGER IF EXISTS audit_log_no_update_delete ON audit_log;

CREATE TRIGGER audit_log_no_update_delete
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW
    EXECUTE FUNCTION audit_log_worm_guard();
