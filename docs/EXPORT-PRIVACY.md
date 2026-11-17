# Export privacy and token lifecycle (issue #25)

## Portable reports vs private diagnostics

`report html`, `report csv` and the local Ktor browser's HTML/CSV
downloads now default to a **token-safe portable profile**. It preserves flight
date, route, fare and party-size information, but removes:

- Provider booking / deep-link URLs (including token-bearing URLs) from
  itineraries, add-ons and vendors.
- Opaque fare-policy identifiers and exact seller/offer IDs, private seller
  refund proof and embedded evidence URLs. The export intentionally does not
  assert verified cash refundability after proof is removed.
- Saved search query metadata, free-form itinerary and seller notes, editorial
  notes, provider warnings and seller product keys / booking affordances.
  A fixed warning explains which fields were excluded.

All report paths share the same typed `ResultSet` projection before
serialization, so HTML, itinerary CSV, seller CSV and seller-option CSV
must not silently diverge. In-browser data remains unchanged in its
local DB. The browser does not offer a private-diagnostic export profile.

### Explicit local-only diagnostics

Use `clawfare report html <slug> --out <file> --private-diagnostic` or
`clawfare report csv <slug> --out <file> --private-diagnostic` only when a
private forensic export requires full booking links, free-form annotations
or exact refund evidence. The CLI warns to stderr.

`clawfare report json <slug>` is a **re-importable full diagnostic format**,
not an anonymized report. It requires `--private-diagnostic` before anything
is printed and warns to stderr; previous unguarded raw JSON export callers
need to opt in deliberately. This preserves JSON's lossless import contract
instead of inventing fake share links that pass neither validation nor
re-ingest.

### What a portable report **does not** protect

It is **not anonymous**: trip dates, route combinations, party size, prices,
title, seller names, and derived source labels remain. Never upload reports
of real travel plans to public GitHub issues or CI artifacts. Arbitrary
free-form fields or untrusted source names cannot be proven secret-free
merely by redacting known token keys. Use synthetic data for tests and public
examples, and keep private reports on an access-controlled disk.

The derived portable report is not an authenticated source of live offers.
Verify fares and refund terms against current provider/seller evidence
before purchasing. Missing exact refund evidence must remain **unverified**.

## Persistence boundaries and remaining retention work

- `flight_provider_tokens` retains departure and booking tokens in plaintext
  local SQLite for private replay. These are not API credentials but can reveal
  search context. The token-safe export does **not** delete them.
- `provider_cache.body` can contain supplier-originating data. Cache writes
  redact configured credentials; `fare prune-provider-cache` is preview-only
  unless `--confirm`, and does not remove normalized historical price data.
- Historical SQLite/WAL files and `*.backup-*.sqlite` may retain older
  versions of sensitive records even after a cache purge; do not treat SQL
  deletion as secure erase.
- Keep SQLite, journal files, working directories and backups private; never
  check them into Git or publish them as CI artifacts. An offline encrypted
  backup/restore workflow with WAL consistency and tested old-version recovery
  remains open under #25, as do non-cache token retention policy and supplier
  licensing/redistribution obligations.

## Tests

`ExportPrivacyTest` uses **synthetic** booking tokens in a ResultSet. It
checks all four portable formats redact known opaque fields and that a
private-diagnostic override explicitly preserves them, while leaving the
original in-memory result unchanged. CI still exercises the offline
snapshot/report path. No live credentials, network requests or real travel
data are needed.
