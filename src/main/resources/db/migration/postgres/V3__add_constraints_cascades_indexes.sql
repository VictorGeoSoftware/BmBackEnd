-- V3: Add UNIQUE constraint on natural key, ON DELETE CASCADE FKs, and indexes (Postgres only)

-- 1. Natural key uniqueness for price_table_results
ALTER TABLE price_table_results
    ADD CONSTRAINT uq_price_table_results_file_company UNIQUE (file_name, company_name);

-- 2. Drop existing FK constraints and re-add with ON DELETE CASCADE
-- termino_de_potencia -> price_table_results
ALTER TABLE termino_de_potencia
    DROP CONSTRAINT IF EXISTS termino_de_potencia_result_id_fkey,
    ADD CONSTRAINT termino_de_potencia_result_id_fkey
        FOREIGN KEY (result_id) REFERENCES price_table_results(id) ON DELETE CASCADE;

-- termino_de_energia -> price_table_results
ALTER TABLE termino_de_energia
    DROP CONSTRAINT IF EXISTS termino_de_energia_result_id_fkey,
    ADD CONSTRAINT termino_de_energia_result_id_fkey
        FOREIGN KEY (result_id) REFERENCES price_table_results(id) ON DELETE CASCADE;

-- tarifas_potencia -> termino_de_potencia
ALTER TABLE tarifas_potencia
    DROP CONSTRAINT IF EXISTS tarifas_potencia_termino_id_fkey,
    ADD CONSTRAINT tarifas_potencia_termino_id_fkey
        FOREIGN KEY (termino_id) REFERENCES termino_de_potencia(id) ON DELETE CASCADE;

-- tarifas_energia_base -> termino_de_energia
ALTER TABLE tarifas_energia_base
    DROP CONSTRAINT IF EXISTS tarifas_energia_base_termino_id_fkey,
    ADD CONSTRAINT tarifas_energia_base_termino_id_fkey
        FOREIGN KEY (termino_id) REFERENCES termino_de_energia(id) ON DELETE CASCADE;

-- tarifas_energia_unica -> termino_de_energia
ALTER TABLE tarifas_energia_unica
    DROP CONSTRAINT IF EXISTS tarifas_energia_unica_termino_id_fkey,
    ADD CONSTRAINT tarifas_energia_unica_termino_id_fkey
        FOREIGN KEY (termino_id) REFERENCES termino_de_energia(id) ON DELETE CASCADE;

-- 3. Indexes for common query patterns
CREATE INDEX IF NOT EXISTS idx_user_activity_month_key ON user_activity(month_key);
CREATE INDEX IF NOT EXISTS idx_tarifas_potencia_termino_id ON tarifas_potencia(termino_id);
CREATE INDEX IF NOT EXISTS idx_tarifas_energia_base_termino_id ON tarifas_energia_base(termino_id);
CREATE INDEX IF NOT EXISTS idx_tarifas_energia_unica_termino_id ON tarifas_energia_unica(termino_id);
CREATE INDEX IF NOT EXISTS idx_termino_de_potencia_result_id ON termino_de_potencia(result_id);
CREATE INDEX IF NOT EXISTS idx_termino_de_energia_result_id ON termino_de_energia(result_id);
