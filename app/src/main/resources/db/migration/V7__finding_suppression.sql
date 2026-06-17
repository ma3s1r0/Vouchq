-- V7 — false-positive suppression / acknowledgement of scan findings (MA3-94).
-- A suppression silences a finding at READ time: the raw scan_result
-- rows are never mutated, so the underlying detection stays on the record. The
-- API and the policy engine apply suppression to compute an EFFECTIVE risk over
-- the non-suppressed findings only — so acknowledging a known FP can never blind
-- detection of a new/other finding.
--
-- Scope of one suppression row (matches when ALL hold against a finding):
--   rule_id      = finding.ruleId
--   tool_id      IS NULL  (org-wide for that rule)  OR  = the finding's tool
--   fingerprint  IS NULL  (the whole rule)          OR  = the finding's fingerprint
-- where fingerprint = sha256hex(ruleId | path | line) (see FindingSuppressionService).

CREATE TABLE finding_suppression (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id      uuid        NOT NULL REFERENCES organization (id),
    -- Scanner rule that is being suppressed (e.g. 'secret.generic').
    rule_id     varchar(128) NOT NULL,
    -- NULL = applies org-wide for the rule; set = scoped to a single tool.
    tool_id     uuid        NULL REFERENCES tool (id),
    -- NULL = suppress every finding of the rule (in scope); set = a single
    -- acknowledged finding identified by its stable fingerprint.
    fingerprint varchar(64) NULL,
    reason      text        NULL,
    created_by  varchar(255) NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_finding_suppression_org_rule ON finding_suppression (org_id, rule_id);
