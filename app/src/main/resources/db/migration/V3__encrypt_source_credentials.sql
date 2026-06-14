-- ---------------------------------------------------------------------------
-- V3 — MA3-89: source credentials are now encrypted at rest in source.auth_ref.
--
-- The column type (text) already holds the ciphertext, so no DDL change is
-- needed and ddl-auto: validate stays happy. This migration only clears the
-- legacy non-sensitive placeholder ('token:in-memory') that earlier ingestion
-- wrote — it was never a real credential, and the rescan path now treats a
-- non-decryptable auth_ref as "no token". Operators must re-supply real tokens
-- via POST /api/sources/{id}/credentials so private sources keep re-verifying.
-- ---------------------------------------------------------------------------
UPDATE source
SET auth_ref = NULL
WHERE auth_ref = 'token:in-memory';
