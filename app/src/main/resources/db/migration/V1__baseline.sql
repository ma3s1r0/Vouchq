-- V1 baseline. Confirms Flyway + Postgres are wired end to end.
-- The full domain schema (organization, source, tool, tool_version,
-- approved_version, scan_result, drift_event, audit_log, user/role) lands in
-- V2 under Linear MA3-72.

CREATE TABLE vouchq_meta (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

INSERT INTO vouchq_meta (key, value) VALUES ('schema_baseline', 'v1');
