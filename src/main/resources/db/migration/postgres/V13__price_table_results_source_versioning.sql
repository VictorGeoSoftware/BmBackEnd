-- V13: Track the source document behind each price_table_results row.
--
-- Web uploads used to be persisted under a random temp name
-- (price_proposal_<n>.pdf), so re-uploading the same proposal always created a
-- new row. The backend now keeps the original filename, and the natural key
-- (file_name, company_name) from V3 identifies the proposal again.
--
-- source_sha256 is the SHA-256 of the uploaded PDF bytes. It is NULL for rows
-- that arrive through /batch-process-price-tables (n8n), where the backend never
-- sees the PDF.
--
-- Rule on re-upload for the same (file_name, company_name):
--   * same hash      -> nothing changes (the upload is skipped before extraction)
--   * different hash -> data is overwritten in place and version is incremented
-- No history is kept.

ALTER TABLE price_table_results
    ADD COLUMN source_sha256 CHAR(64),
    ADD COLUMN version       INTEGER     NOT NULL DEFAULT 1,
    ADD COLUMN updated_at    TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE INDEX IF NOT EXISTS idx_price_table_results_source_sha256
    ON price_table_results(source_sha256);
