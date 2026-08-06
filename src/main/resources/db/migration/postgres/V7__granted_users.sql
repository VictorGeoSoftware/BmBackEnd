-- V7: Create granted_users table
-- Replaces the BM_AUTH_EMAIL_ALLOWLIST environment variable as the source of
-- truth for which accounts may access the app. One row per granted account,
-- keyed by normalized (trimmed, lowercase) email. Managed from the BmWeb
-- "Usuarios" dashboard via the admin endpoints.

CREATE TABLE granted_users (
    id         SERIAL PRIMARY KEY,
    email      VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX granted_users_email_unique ON granted_users(email);
