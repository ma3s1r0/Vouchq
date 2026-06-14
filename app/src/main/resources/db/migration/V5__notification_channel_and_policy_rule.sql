-- V5 — DB-backed, editable notification channels and policy rules (MA3-92,
-- 기획서 §5.2 알림 채널 + 정책 룰, §9.7). Both org-scoped like every other domain
-- table. Until now these lived in @ConfigurationProperties (MA3-85/86); from here
-- the DB is the editable source of truth (properties only seed an empty DB on
-- first run). Self-hosted default (기획서 §7): no enabled channel ⇒ zero outbound.

-- ---------------------------------------------------------------------------
-- notification_channel — one outbound delivery target (Email / Slack / Webhook)
-- ---------------------------------------------------------------------------
CREATE TABLE notification_channel (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id     uuid        NOT NULL REFERENCES organization (id),
    type       varchar(16) NOT NULL CHECK (type IN ('WEBHOOK', 'SLACK', 'EMAIL')),
    name       varchar(255) NOT NULL,
    -- Primary delivery address: webhook/slack URL, or the email "from" address.
    target     varchar(2048) NOT NULL,
    -- Type-specific extras as jsonb (e.g. EMAIL {"to":["a@x"]}). Defaults to {}.
    config     jsonb       NOT NULL DEFAULT '{}'::jsonb,
    enabled    boolean     NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_notification_channel_org ON notification_channel (org_id);

-- ---------------------------------------------------------------------------
-- policy_rule — one condition → action rule, evaluated in priority order
-- ---------------------------------------------------------------------------
CREATE TABLE policy_rule (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id     uuid        NOT NULL REFERENCES organization (id),
    name       varchar(255) NOT NULL,
    -- Lower priority is evaluated first; the first matching rule wins.
    priority   int         NOT NULL DEFAULT 100,
    -- ANDed conditions as jsonb: {minRiskScore?, severity?, findingCategory?, nameRegex?}.
    condition  jsonb       NOT NULL DEFAULT '{}'::jsonb,
    action     varchar(16) NOT NULL CHECK (action IN ('AUTO_BLOCK', 'HOLD')),
    enabled    boolean     NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_policy_rule_org_priority ON policy_rule (org_id, priority);
