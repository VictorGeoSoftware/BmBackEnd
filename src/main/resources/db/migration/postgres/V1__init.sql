-- V1: Initial schema (PostgreSQL dialect)
-- Mirrors Exposed IntIdTable definitions from Entities.kt

CREATE TABLE IF NOT EXISTS price_table_results (
    id SERIAL PRIMARY KEY,
    file_name VARCHAR(255) NOT NULL,
    company_name VARCHAR(255) NOT NULL
);

CREATE TABLE IF NOT EXISTS termino_de_potencia (
    id SERIAL PRIMARY KEY,
    result_id INTEGER NOT NULL REFERENCES price_table_results(id),
    titulo TEXT NOT NULL,
    tabla_titulo TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS termino_de_energia (
    id SERIAL PRIMARY KEY,
    result_id INTEGER NOT NULL REFERENCES price_table_results(id),
    titulo TEXT NOT NULL,
    tabla_base_titulo TEXT NOT NULL,
    tabla_unica_titulo TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS tarifas_potencia (
    id SERIAL PRIMARY KEY,
    termino_id INTEGER NOT NULL REFERENCES termino_de_potencia(id),
    tarifa VARCHAR(50) NOT NULL,
    potencia_contratada VARCHAR(100),
    p1 DOUBLE PRECISION,
    p2 DOUBLE PRECISION,
    p3 DOUBLE PRECISION,
    p4 DOUBLE PRECISION,
    p5 DOUBLE PRECISION,
    p6 DOUBLE PRECISION
);

CREATE TABLE IF NOT EXISTS tarifas_energia_base (
    id SERIAL PRIMARY KEY,
    termino_id INTEGER NOT NULL REFERENCES termino_de_energia(id),
    tarifa VARCHAR(50) NOT NULL,
    potencia_contratada VARCHAR(100),
    p1 DOUBLE PRECISION,
    p2 DOUBLE PRECISION,
    p3 DOUBLE PRECISION,
    p4 DOUBLE PRECISION,
    p5 DOUBLE PRECISION,
    p6 DOUBLE PRECISION
);

CREATE TABLE IF NOT EXISTS tarifas_energia_unica (
    id SERIAL PRIMARY KEY,
    termino_id INTEGER NOT NULL REFERENCES termino_de_energia(id),
    tarifa VARCHAR(50) NOT NULL,
    potencia_contratada VARCHAR(100),
    p1 DOUBLE PRECISION,
    p2 DOUBLE PRECISION,
    p3 DOUBLE PRECISION,
    p4 DOUBLE PRECISION,
    p5 DOUBLE PRECISION,
    p6 DOUBLE PRECISION
);

CREATE TABLE IF NOT EXISTS tax_settings (
    id SERIAL PRIMARY KEY,
    iva DOUBLE PRECISION NOT NULL,
    impuesto_electrico DOUBLE PRECISION NOT NULL
);

CREATE TABLE IF NOT EXISTS user_data (
    id SERIAL PRIMARY KEY,
    uid VARCHAR(128) NOT NULL,
    email VARCHAR(255),
    display_name VARCHAR(255),
    photo_url TEXT,
    provider_ids TEXT NOT NULL,
    token_issued_at BIGINT NOT NULL,
    token_expires_at BIGINT NOT NULL,
    last_login_at BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS user_data_uid_unique ON user_data(uid);

CREATE TABLE IF NOT EXISTS user_activity (
    id SERIAL PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    is_online BOOLEAN NOT NULL,
    monthly_usage_count INTEGER NOT NULL,
    month_key VARCHAR(7) NOT NULL,
    usage_started_at BIGINT,
    first_connected_at BIGINT,
    last_connected_at BIGINT,
    last_disconnected_at BIGINT,
    updated_at BIGINT NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS user_activity_email_unique ON user_activity(email);
