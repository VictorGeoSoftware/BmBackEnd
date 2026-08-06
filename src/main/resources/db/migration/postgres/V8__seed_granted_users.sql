-- V8: Seed granted_users with the production allowlist previously provided via
-- the BM_AUTH_EMAIL_ALLOWLIST environment variable / GitHub secret.
-- Emails are stored normalized: trimmed and lowercase.

INSERT INTO granted_users (email) VALUES
    ('madridconsultores2019@gmail.com'),
    ('asesor.victor.toribio@gmail.com'),
    ('errerux@gmail.com'),
    ('brionestecnico81@gmail.com'),
    ('victor.carrasco@brielmarnysos.com')
ON CONFLICT (email) DO NOTHING;
