-- V14: Create web_push_tokens table
--
-- Stores the Firebase Cloud Messaging (FCM) registration tokens issued to the
-- PowerAppWeb PWA (installed web app) when a broker turns notifications on.
--
-- The Android app does not need this: it subscribes itself to the
-- "price_updates" and "force_logout" topics from the device. The FCM JS SDK
-- cannot subscribe to topics from the browser, so web tokens must be kept here
-- and subscribed to topics server-side (Admin SDK subscribeToTopic). That
-- subscription is done by a later feature (web-push-price-updates).
--
-- One row per token. A token that moves to another account (different broker
-- signing in on the same browser) is reassigned by upsert. Rows are removed on
-- manual logout (owner-checked) and by the grant deletion wipe (by email).

CREATE TABLE web_push_tokens (
    id         SERIAL PRIMARY KEY,
    token      TEXT         NOT NULL UNIQUE,
    uid        VARCHAR(128) NOT NULL,
    email      VARCHAR(255) NOT NULL,
    platform   VARCHAR(16)  NOT NULL CHECK (platform IN ('ios', 'android', 'desktop')),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_web_push_tokens_email ON web_push_tokens (email);
