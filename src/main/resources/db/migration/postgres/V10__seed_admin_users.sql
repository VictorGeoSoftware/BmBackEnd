-- V10: Seed admin_users with the initial BmWeb administrator accounts.
-- Emails are stored normalized: trimmed and lowercase.

INSERT INTO admin_users (email) VALUES
    ('victor.carrasco@brielmarnysos.com'),
    ('manuel.briones@brielmarnysos.com')
ON CONFLICT (email) DO NOTHING;
