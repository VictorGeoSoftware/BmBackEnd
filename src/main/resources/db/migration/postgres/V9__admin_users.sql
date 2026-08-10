-- V9: Create admin_users table
-- Allowlist of accounts permitted to access the BmWeb dashboard and the
-- /admin/* endpoints. Kept separate from granted_users (which gates BmApp
-- access) so regular app users never gain administrative privileges.
-- One row per admin account, keyed by normalized (trimmed, lowercase) email.
-- Managed directly via SQL for now.

CREATE TABLE admin_users (
    id         SERIAL PRIMARY KEY,
    email      VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX admin_users_email_unique ON admin_users(email);
