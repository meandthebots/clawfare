-- Tenant-private, token-free provider summaries. This is not a cache of raw
-- vendor response bodies. A provider adapter may write only when contract/
-- provider caching permissions were separately established.
CREATE TABLE IF NOT EXISTS hosted_provider_offer_cache (
    owner_id TEXT NOT NULL CHECK (length(owner_id) BETWEEN 8 AND 128),
    provider TEXT NOT NULL CHECK (provider IN ('serpapi','searchapi','travelpayouts','duffel')),
    cache_digest TEXT NOT NULL CHECK (length(cache_digest) = 64 AND cache_digest NOT GLOB '*[^a-f0-9]*'),
    parser_version INTEGER NOT NULL CHECK (parser_version BETWEEN 1 AND 100000),
    market TEXT NOT NULL CHECK (length(market) = 2),
    original_currency TEXT NOT NULL CHECK (length(original_currency) = 3),
    outcome TEXT NOT NULL CHECK (outcome IN ('offers','verified_empty')),
    offers_json TEXT NOT NULL CHECK (json_valid(offers_json) AND json_type(offers_json) = 'array'),
    observed_at TEXT NOT NULL,
    expires_at TEXT NOT NULL CHECK (expires_at > observed_at),
    PRIMARY KEY (owner_id, provider, cache_digest, parser_version)
);

CREATE INDEX IF NOT EXISTS idx_hosted_provider_offer_cache_expiry
    ON hosted_provider_offer_cache (expires_at, owner_id);

-- A record is inserted BEFORE dispatch. A Worker crash leaves 'attempted',
-- never a false 'empty'. Exactly one terminal outcome may replace 'attempted'.
-- The identity and digest are derived by the authenticated server, never UI.
CREATE TABLE IF NOT EXISTS hosted_provider_search_attempts (
    owner_id TEXT NOT NULL CHECK (length(owner_id) BETWEEN 8 AND 128),
    consent_nonce TEXT NOT NULL CHECK (length(consent_nonce) = 32 AND consent_nonce NOT GLOB '*[^a-f0-9]*'),
    query_digest TEXT NOT NULL CHECK (length(query_digest) = 64 AND query_digest NOT GLOB '*[^a-f0-9]*'),
    provider TEXT NOT NULL CHECK (provider IN ('serpapi','searchapi','travelpayouts','duffel')),
    route_digest TEXT NOT NULL CHECK (length(route_digest) = 64 AND route_digest NOT GLOB '*[^a-f0-9]*'),
    investigation_slug TEXT CHECK (investigation_slug IS NULL OR length(investigation_slug) BETWEEN 1 AND 100),
    state TEXT NOT NULL CHECK (state IN ('attempted','complete','empty','partial','failed')),
    attempted_calls INTEGER NOT NULL DEFAULT 0 CHECK (attempted_calls BETWEEN 0 AND 16),
    loaded_calls INTEGER NOT NULL DEFAULT 0 CHECK (loaded_calls BETWEEN 0 AND attempted_calls),
    cached_calls INTEGER NOT NULL DEFAULT 0 CHECK (cached_calls BETWEEN 0 AND 16),
    returned_offers INTEGER NOT NULL DEFAULT 0 CHECK (returned_offers BETWEEN 0 AND 10000),
    eligible_offers INTEGER NOT NULL DEFAULT 0 CHECK (eligible_offers BETWEEN 0 AND returned_offers),
    error_kind TEXT CHECK (error_kind IS NULL OR error_kind IN
        ('network','timeout','provider_error','quota_exhausted','invalid_response','incomplete','unknown')),
    started_at TEXT NOT NULL,
    finished_at TEXT,
    PRIMARY KEY (owner_id, consent_nonce, provider, route_digest),
    CHECK ((state = 'attempted' AND finished_at IS NULL) OR
           (state != 'attempted' AND finished_at IS NOT NULL)),
    CHECK (state != 'empty' OR (returned_offers = 0 AND error_kind IS NULL)),
    CHECK (state != 'complete' OR error_kind IS NULL),
    CHECK (state != 'failed' OR error_kind IS NOT NULL)
);

CREATE INDEX IF NOT EXISTS idx_hosted_provider_attempts_owner_nonce
    ON hosted_provider_search_attempts (owner_id, consent_nonce, started_at DESC);
