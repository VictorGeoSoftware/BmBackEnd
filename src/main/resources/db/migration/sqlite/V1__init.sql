-- V1: Initial schema (SQLite dialect)
-- Mirrors Exposed IntIdTable definitions from Entities.kt

CREATE TABLE IF NOT EXISTS price_table_results (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    file_name VARCHAR(255) NOT NULL,
    company_name VARCHAR(255) NOT NULL
);

CREATE TABLE IF NOT EXISTS termino_de_potencia (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    result_id INTEGER NOT NULL REFERENCES price_table_results(id),
    titulo TEXT NOT NULL,
    tabla_titulo TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS termino_de_energia (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    result_id INTEGER NOT NULL REFERENCES price_table_results(id),
    titulo TEXT NOT NULL,
    tabla_base_titulo TEXT NOT NULL,
    tabla_unica_titulo TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS tarifas_potencia (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    termino_id INTEGER NOT NULL REFERENCES termino_de_potencia(id),
    tarifa VARCHAR(50) NOT NULL,
    potencia_contratada VARCHAR(100),
    p1 DOUBLE,
    p2 DOUBLE,
    p3 DOUBLE,
    p4 DOUBLE,
    p5 DOUBLE,
    p6 DOUBLE
);

CREATE TABLE IF NOT EXISTS tarifas_energia_base (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    termino_id INTEGER NOT NULL REFERENCES termino_de_energia(id),
    tarifa VARCHAR(50) NOT NULL,
    potencia_contratada VARCHAR(100),
    p1 DOUBLE,
    p2 DOUBLE,
    p3 DOUBLE,
    p4 DOUBLE,
    p5 DOUBLE,
    p6 DOUBLE
);

CREATE TABLE IF NOT EXISTS tarifas_energia_unica (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    termino_id INTEGER NOT NULL REFERENCES termino_de_energia(id),
    tarifa VARCHAR(50) NOT NULL,
    potencia_contratada VARCHAR(100),
    p1 DOUBLE,
    p2 DOUBLE,
    p3 DOUBLE,
    p4 DOUBLE,
    p5 DOUBLE,
    p6 DOUBLE
);

CREATE TABLE IF NOT EXISTS tax_settings (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    iva DOUBLE NOT NULL,
    impuesto_electrico DOUBLE NOT NULL
);

CREATE TABLE IF NOT EXISTS user_data (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
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
    id INTEGER PRIMARY KEY AUTOINCREMENT,
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
