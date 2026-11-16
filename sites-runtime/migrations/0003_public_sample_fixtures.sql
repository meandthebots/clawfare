-- This table contains ONLY the bundled, publicly shareable sample data.
-- No user-supplied investigations, provider credentials or booking tokens.
-- Populate from the verified Kotlin ResultSet public fixture, using a
-- trusted server-side bootstrap function. Never expose an HTTP write route.
CREATE TABLE IF NOT EXISTS public_sample_fixtures (
    slug TEXT PRIMARY KEY CHECK (slug = 'florida-dec-2026'),
    result_json TEXT NOT NULL CHECK (json_valid(result_json)),
    content_sha256 TEXT NOT NULL CHECK (
        length(content_sha256) = 64 AND content_sha256 NOT GLOB '*[^0-9a-f]*'
    ),
    imported_at TEXT NOT NULL
);
