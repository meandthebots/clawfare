-- Hosted signed/one-shot search approval. Not an egress authorization by itself.
-- owner_id is the server-derived pseudonym, query_digest is a SHA-256 hash
-- of the exact canonical Kotlin JSON. Never persist emails, search bodies,
-- provider API credentials, booking tokens, or signature material.
CREATE TABLE IF NOT EXISTS hosted_search_consents (
    nonce TEXT PRIMARY KEY
      CHECK (length(nonce) = 32 AND nonce NOT GLOB '*[^a-f0-9]*'),
    owner_id TEXT NOT NULL CHECK (length(owner_id) = 69),
    query_digest TEXT NOT NULL
      CHECK (length(query_digest) = 64 AND query_digest NOT GLOB '*[^a-f0-9]*'),
    limits_json TEXT NOT NULL CHECK (json_valid(limits_json) AND json_type(limits_json) = 'array'),
    created_at TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    state TEXT NOT NULL DEFAULT 'issued' CHECK (state IN ('issued', 'consumed')),
    consumed_at TEXT CHECK (consumed_at IS NULL OR state = 'consumed'),
    CHECK (expires_at > created_at)
);

CREATE INDEX IF NOT EXISTS idx_hosted_search_consents_owner_expiry
    ON hosted_search_consents (owner_id, expires_at, state);
