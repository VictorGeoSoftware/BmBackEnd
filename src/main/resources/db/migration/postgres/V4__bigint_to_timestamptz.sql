-- V4: Convert BIGINT epoch-millis timestamp columns to TIMESTAMPTZ
-- Existing data is epoch milliseconds; we convert using to_timestamp(col / 1000.0)

-- ── user_data ────────────────────────────────────────────────────

ALTER TABLE user_data
    ALTER COLUMN token_issued_at TYPE TIMESTAMPTZ
        USING to_timestamp(token_issued_at / 1000.0),
    ALTER COLUMN token_expires_at TYPE TIMESTAMPTZ
        USING to_timestamp(token_expires_at / 1000.0),
    ALTER COLUMN last_login_at TYPE TIMESTAMPTZ
        USING to_timestamp(last_login_at / 1000.0),
    ALTER COLUMN created_at TYPE TIMESTAMPTZ
        USING to_timestamp(created_at / 1000.0),
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ
        USING to_timestamp(updated_at / 1000.0);

-- ── user_activity ────────────────────────────────────────────────

ALTER TABLE user_activity
    ALTER COLUMN usage_started_at TYPE TIMESTAMPTZ
        USING CASE WHEN usage_started_at IS NOT NULL
                   THEN to_timestamp(usage_started_at / 1000.0)
                   ELSE NULL END,
    ALTER COLUMN first_connected_at TYPE TIMESTAMPTZ
        USING CASE WHEN first_connected_at IS NOT NULL
                   THEN to_timestamp(first_connected_at / 1000.0)
                   ELSE NULL END,
    ALTER COLUMN last_connected_at TYPE TIMESTAMPTZ
        USING CASE WHEN last_connected_at IS NOT NULL
                   THEN to_timestamp(last_connected_at / 1000.0)
                   ELSE NULL END,
    ALTER COLUMN last_disconnected_at TYPE TIMESTAMPTZ
        USING CASE WHEN last_disconnected_at IS NOT NULL
                   THEN to_timestamp(last_disconnected_at / 1000.0)
                   ELSE NULL END,
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ
        USING to_timestamp(updated_at / 1000.0);
