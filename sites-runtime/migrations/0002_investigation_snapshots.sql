-- Hosted investigation snapshot and immutable price provenance.
-- User/tenant identity MUST come from verified server-side authentication,
-- never from a query parameter or user-supplied request header.
CREATE TABLE IF NOT EXISTS investigation_snapshots (
    owner_id TEXT NOT NULL CHECK (length(owner_id) BETWEEN 8 AND 128),
    slug TEXT NOT NULL CHECK (length(slug) BETWEEN 1 AND 100),
    result_json TEXT NOT NULL CHECK (json_valid(result_json)),
    title TEXT NOT NULL,
    fetched_at TEXT,
    updated_at TEXT NOT NULL,
    PRIMARY KEY (owner_id, slug)
);

CREATE INDEX IF NOT EXISTS idx_investigation_snapshots_owner_updated
    ON investigation_snapshots (owner_id, updated_at DESC, slug);

-- Every immutable price sample retains original currency and source status.
-- No raw response body, access token, unredacted booking token or card data
-- is stored here. Observations of mock/cache results cannot masquerade as live.
CREATE TABLE IF NOT EXISTS fare_price_observations (
    owner_id TEXT NOT NULL,
    investigation_slug TEXT NOT NULL,
    observation_id TEXT NOT NULL CHECK (length(observation_id) = 64),
    itinerary_id TEXT NOT NULL CHECK (length(itinerary_id) BETWEEN 1 AND 128),
    provider TEXT NOT NULL CHECK (length(provider) BETWEEN 1 AND 64),
    source_label TEXT NOT NULL CHECK (source_label IN ('live','cached','mock','sample','manual')),
    live_price INTEGER NOT NULL CHECK (live_price IN (0, 1)),
    captured_at TEXT NOT NULL,
    original_currency TEXT NOT NULL CHECK (length(original_currency) = 3),
    amount_decimal TEXT NOT NULL,
    PRIMARY KEY (owner_id, investigation_slug, observation_id),
    FOREIGN KEY (owner_id, investigation_slug)
        REFERENCES investigation_snapshots (owner_id, slug) ON DELETE CASCADE,
    CHECK (source_label = 'live' OR live_price = 0)
);

CREATE INDEX IF NOT EXISTS idx_fare_price_observations_itinerary
    ON fare_price_observations (
        owner_id, investigation_slug, itinerary_id, captured_at DESC, observation_id
    );
