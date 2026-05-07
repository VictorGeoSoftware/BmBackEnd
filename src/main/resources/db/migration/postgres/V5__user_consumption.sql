-- V5: Create user_consumption table with JSONB storage
-- Stores the full UserConsumption payload per user, keyed by uid.

CREATE TABLE user_consumption (
    id         SERIAL PRIMARY KEY,
    uid        VARCHAR(128) NOT NULL UNIQUE,
    data       JSONB        NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_user_consumption_uid ON user_consumption(uid);
