-- V2: Backfill usage_started_at for existing user_activity rows
UPDATE user_activity
SET usage_started_at = COALESCE(last_connected_at, last_disconnected_at, updated_at)
WHERE usage_started_at IS NULL;
