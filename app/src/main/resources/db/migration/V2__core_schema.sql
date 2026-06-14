-- V2 core schema (기획서 §6). Multitenant via org_id on every domain table.
-- PostgreSQL 16. Enums modeled as varchar + CHECK for Flyway simplicity.
-- uuid PKs default gen_random_uuid() (pgcrypto built into PG16).

-- ---------------------------------------------------------------------------
-- organization (multitenancy root)
-- ---------------------------------------------------------------------------
CREATE TABLE organization (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name       varchar(255) NOT NULL,
    slug       varchar(255) NOT NULL UNIQUE,
    created_at timestamptz  NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- app_user (RBAC)
-- ---------------------------------------------------------------------------
CREATE TABLE app_user (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id       uuid         NOT NULL REFERENCES organization (id),
    email        varchar(320) NOT NULL,
    display_name varchar(255),
    role         varchar(16)  NOT NULL CHECK (role IN ('ADMIN', 'MEMBER', 'VIEWER')),
    created_at   timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (org_id, email)
);

-- ---------------------------------------------------------------------------
-- source (connected git repo / file upload / MCP server)
-- ---------------------------------------------------------------------------
CREATE TABLE source (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id     uuid        NOT NULL REFERENCES organization (id),
    type       varchar(20) NOT NULL CHECK (type IN ('GIT_REPOSITORY', 'FILE_UPLOAD', 'MCP_SERVER')),
    uri        varchar(2048) NOT NULL,
    auth_ref   text,
    created_at timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- registered_server (MCP server or Skill bundle)
-- ---------------------------------------------------------------------------
CREATE TABLE registered_server (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id     uuid        NOT NULL REFERENCES organization (id),
    source_id  uuid        NOT NULL REFERENCES source (id),
    kind       varchar(16) NOT NULL CHECK (kind IN ('SKILL_BUNDLE', 'MCP_SERVER')),
    name       varchar(255) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- tool (individual tool/skill)
-- current_version_id / approved_version_id FKs added after dependent tables
-- exist (circular dependency).
-- ---------------------------------------------------------------------------
CREATE TABLE tool (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id              uuid        NOT NULL REFERENCES organization (id),
    server_id           uuid        NOT NULL REFERENCES registered_server (id),
    kind                varchar(16) NOT NULL CHECK (kind IN ('SKILL', 'MCP_TOOL')),
    name                varchar(255) NOT NULL,
    status              varchar(16) NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'DRIFTED', 'BLOCKED')),
    current_version_id  uuid,
    approved_version_id uuid,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- tool_version (definition snapshot + hash, per observation)
-- ---------------------------------------------------------------------------
CREATE TABLE tool_version (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id      uuid        NOT NULL REFERENCES organization (id),
    tool_id     uuid        NOT NULL REFERENCES tool (id),
    definition  jsonb       NOT NULL,
    hash        char(64)    NOT NULL,
    observed_at timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- approved_version (approved / pinned canonical version)
-- ---------------------------------------------------------------------------
CREATE TABLE approved_version (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id          uuid        NOT NULL REFERENCES organization (id),
    tool_id         uuid        NOT NULL REFERENCES tool (id),
    tool_version_id uuid        NOT NULL REFERENCES tool_version (id),
    hash            char(64)    NOT NULL,
    approved_by     varchar(255) NOT NULL,
    approved_at     timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- scan_result (risk score + findings)
-- ---------------------------------------------------------------------------
CREATE TABLE scan_result (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id           uuid        NOT NULL REFERENCES organization (id),
    tool_version_id  uuid        NOT NULL REFERENCES tool_version (id),
    risk_score       int         NOT NULL,
    highest_severity varchar(16) CHECK (highest_severity IN ('INFO', 'WARN', 'CRITICAL')),
    findings         jsonb       NOT NULL,
    scanned_at       timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- drift_event (mismatch between pinned and observed)
-- ---------------------------------------------------------------------------
CREATE TABLE drift_event (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id        uuid        NOT NULL REFERENCES organization (id),
    tool_id       uuid        NOT NULL REFERENCES tool (id),
    approved_hash char(64)    NOT NULL,
    observed_hash char(64)    NOT NULL,
    diff          jsonb       NOT NULL,
    severity      varchar(16) NOT NULL CHECK (severity IN ('INFO', 'WARN', 'CRITICAL')),
    detected_at   timestamptz NOT NULL DEFAULT now(),
    resolved      boolean     NOT NULL DEFAULT false
);

-- ---------------------------------------------------------------------------
-- audit_log (append-only, hash chain)
-- ---------------------------------------------------------------------------
CREATE TABLE audit_log (
    id         bigserial PRIMARY KEY,
    org_id     uuid        NOT NULL,
    actor      varchar(255) NOT NULL,
    action     varchar(64) NOT NULL,
    target_id  uuid,
    payload    jsonb       NOT NULL,
    prev_hash  char(64),
    entry_hash char(64)    NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- Resolve circular FKs on tool now that tool_version / approved_version exist.
-- ---------------------------------------------------------------------------
ALTER TABLE tool
    ADD CONSTRAINT fk_tool_current_version
        FOREIGN KEY (current_version_id) REFERENCES tool_version (id);

ALTER TABLE tool
    ADD CONSTRAINT fk_tool_approved_version
        FOREIGN KEY (approved_version_id) REFERENCES approved_version (id);

-- ---------------------------------------------------------------------------
-- Indexes
-- ---------------------------------------------------------------------------
-- org_id on every domain table
CREATE INDEX idx_app_user_org          ON app_user (org_id);
CREATE INDEX idx_source_org            ON source (org_id);
CREATE INDEX idx_registered_server_org ON registered_server (org_id);
CREATE INDEX idx_tool_org              ON tool (org_id);
CREATE INDEX idx_tool_version_org      ON tool_version (org_id);
CREATE INDEX idx_approved_version_org  ON approved_version (org_id);
CREATE INDEX idx_scan_result_org       ON scan_result (org_id);
CREATE INDEX idx_drift_event_org       ON drift_event (org_id);

-- status / resolved filters
CREATE INDEX idx_tool_status        ON tool (status);
CREATE INDEX idx_drift_event_resolved ON drift_event (resolved);

-- audit_log ordered scan per org
CREATE INDEX idx_audit_log_org_id ON audit_log (org_id, id);

-- btree on hash / *_hash char(64) columns
CREATE INDEX idx_tool_version_hash         ON tool_version (hash);
CREATE INDEX idx_approved_version_hash     ON approved_version (hash);
CREATE INDEX idx_drift_event_approved_hash ON drift_event (approved_hash);
CREATE INDEX idx_drift_event_observed_hash ON drift_event (observed_hash);
CREATE INDEX idx_audit_log_prev_hash       ON audit_log (prev_hash);
CREATE INDEX idx_audit_log_entry_hash      ON audit_log (entry_hash);

-- GIN on jsonb columns
CREATE INDEX idx_tool_version_definition_gin ON tool_version USING gin (definition);
CREATE INDEX idx_scan_result_findings_gin    ON scan_result USING gin (findings);
CREATE INDEX idx_drift_event_diff_gin        ON drift_event USING gin (diff);
