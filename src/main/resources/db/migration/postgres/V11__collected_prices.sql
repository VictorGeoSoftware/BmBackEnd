-- V11: Create collected_prices table
--
-- Stores the customer's CURRENT electricity prices as entered by a broker on the
-- app's "current conditions" screen, captured at the moment the broker navigates
-- to the price proposals. Used to build a picture of what non-2.0TD customers are
-- currently paying, and with which supplier.
--
-- Deliberately holds NO customer personal data: no CUPS, no holder name, no supply
-- address, and no broker identity. Only the supplier, the tariff, the prices and
-- when they were collected.
--
-- 2.0TD supplies are excluded at collection time (by the app, and defensively by
-- CollectedPricesService), so this table should contain only 3.0TD/3.1TD/etc rows.
--
-- Periods vary by tariff (2.0TD uses P1-P2 power / P1-P3 energy, 3.0TD uses P1-P6
-- for both), so every period column is nullable; unused periods stay NULL and are
-- rendered as an em dash in the dashboard.
--
-- company_name holds the supplier exactly as the broker typed it (free text, there
-- is no source of truth for the incumbent supplier in the bill data).
-- company_name_normalized holds a canonical form (trimmed, lowercase, collapsed
-- whitespace) so that "Iberdrola", "IBERDROLA" and "Iberdrola  S.A." can be grouped
-- in the dashboard without forcing a fixed supplier list on the broker.

CREATE TABLE collected_prices (
    id                      SERIAL PRIMARY KEY,
    company_name            VARCHAR(255)     NOT NULL,
    company_name_normalized VARCHAR(255)     NOT NULL,
    tariff_type             VARCHAR(50)      NOT NULL,
    power_p1                DOUBLE PRECISION,
    power_p2                DOUBLE PRECISION,
    power_p3                DOUBLE PRECISION,
    power_p4                DOUBLE PRECISION,
    power_p5                DOUBLE PRECISION,
    power_p6                DOUBLE PRECISION,
    energy_p1               DOUBLE PRECISION,
    energy_p2               DOUBLE PRECISION,
    energy_p3               DOUBLE PRECISION,
    energy_p4               DOUBLE PRECISION,
    energy_p5               DOUBLE PRECISION,
    energy_p6               DOUBLE PRECISION,
    extra_services          DOUBLE PRECISION,
    collected_at            TIMESTAMPTZ      NOT NULL DEFAULT now()
);

-- Dashboard reads are "most recent first", optionally narrowed by tariff or supplier.
CREATE INDEX collected_prices_collected_at_idx ON collected_prices(collected_at DESC);
CREATE INDEX collected_prices_tariff_type_idx ON collected_prices(tariff_type);
CREATE INDEX collected_prices_company_idx ON collected_prices(company_name_normalized);
