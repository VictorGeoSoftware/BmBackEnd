-- V6: Add phone_uuid to user_data (PostgreSQL dialect)
-- Opaque per-install device identifier used to bind a single phone to a
-- Google/Firebase account (one-phone-per-account requirement).
ALTER TABLE user_data ADD COLUMN IF NOT EXISTS phone_uuid VARCHAR(64);
