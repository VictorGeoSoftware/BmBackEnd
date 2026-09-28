-- Add product tiers to BmApp grants. Existing and future grants default to
-- BASIC so this migration does not remove any currently available feature.

ALTER TABLE granted_users
    ADD COLUMN tier VARCHAR(20) NOT NULL DEFAULT 'BASIC';

ALTER TABLE granted_users
    ADD CONSTRAINT granted_users_tier_check
        CHECK (tier IN ('BASIC', 'PREMIUM'));
