-- One rate-limit row per authenticated owner, shared across Worker instances.
CREATE TABLE IF NOT EXISTS hosted_export_limits (
    owner_id TEXT PRIMARY KEY CHECK (length(owner_id) BETWEEN 8 AND 128),
    window_start INTEGER NOT NULL,
    request_count INTEGER NOT NULL CHECK (request_count > 0)
);
