-- V6 — real auth over app_user (MA3-93 인증/인가).
-- Adds the credential + lifecycle columns the V2 app_user table was missing so
-- it can back a DB UserDetailsService (BCrypt) and org membership. One org per
-- user (app_user.org_id from V2) for the MVP; multi-org/SSO is a later addition.

ALTER TABLE app_user
    ADD COLUMN password_hash varchar(100),
    ADD COLUMN active        boolean NOT NULL DEFAULT true;

-- Login is by email; a partial-free index speeds the UserDetailsService lookup.
-- (UNIQUE (org_id, email) already exists; email is globally unique for login so
-- the same address can't belong to two orgs in this MVP.)
CREATE UNIQUE INDEX idx_app_user_email ON app_user (email);
