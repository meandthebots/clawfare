-- D1/SQLite reservation ledger for hosted provider requests.
-- No secrets, request URLs, flight parameters, or user data are stored here.
-- IMPORTANT: Configure explicit caps through a trusted admin-only migration.
-- A missing cap means no provider request can be reserved.
CREATE TABLE IF NOT EXISTS provider_http_limits (
    provider TEXT PRIMARY KEY
        CHECK (provider IN ('serpapi', 'searchapi', 'duffel', 'travelpayouts')),
    daily_cap INTEGER NOT NULL CHECK (daily_cap >= 0),
    monthly_cap INTEGER CHECK (monthly_cap IS NULL OR monthly_cap >= 0)
);

CREATE TABLE IF NOT EXISTS provider_http_reservations (
    id TEXT PRIMARY KEY CHECK (length(id) > 0),
    provider TEXT NOT NULL REFERENCES provider_http_limits(provider),
    category TEXT NOT NULL
        CHECK (category IN ('base_search', 'return_selection', 'booking_options', 'exact_offer', 'quota')),
    reserved_at TEXT NOT NULL,
    utc_day TEXT NOT NULL CHECK (length(utc_day) = 10),
    utc_month TEXT NOT NULL CHECK (length(utc_month) = 7),
    phase TEXT NOT NULL DEFAULT 'reserved' CHECK (phase IN ('reserved', 'completed')),
    http_status INTEGER CHECK (http_status IS NULL OR http_status BETWEEN 100 AND 599),
    outcome TEXT NOT NULL DEFAULT 'unknown'
        CHECK (outcome IN ('unknown', 'response', 'rate_limited', 'transport_error'))
);

CREATE INDEX IF NOT EXISTS idx_provider_http_reservations_daily
    ON provider_http_reservations (provider, utc_day);
CREATE INDEX IF NOT EXISTS idx_provider_http_reservations_monthly
    ON provider_http_reservations (provider, utc_month);
