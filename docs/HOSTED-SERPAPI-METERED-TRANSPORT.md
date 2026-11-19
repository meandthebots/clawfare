# Hosted SerpApi metered transport — inactive implementation

`src/serpapi-metered-transport.mjs` implements the first **server-only,
unmounted** provider transport. Its top-level `enabled` option defaults to
`false`, and no browser, API or Worker handler imports or calls it. In
particular, `POST /api/search` remains HTTP 405, regardless of this source
module's presence.

A future trusted HTTP orchestrator may pass a verified opaque owner key, the
**exact** canonical Kotlin/JS search JSON, a signed short-lived single-use
consent, the server-held SerpApi credential and D1. The function:

1. Replans the query with compiled Kotlin/JS, checks byte-for-byte equality
   with the approved canonical JSON, and refuses round trips, return tokens,
   multiple providers, cache-only, unknown cabins or any one-way matrix
   requiring more than 16 requests. This first slice does not pretend that
   an outbound result is an independently verified round-trip offer.
2. Atomically consumes the owner/query-bound consent nonce in D1. The
   consent must authorize exactly the projected `serpapi/base_search` count,
   with no other categories, before any request is sent.
3. Sequentially constructs a fixed, allowlisted SerpApi Google Flights GET
   URL from typed Kotlin fields and the server's secret. Each request is
   atomically reserved against actual D1 daily/monthly provider caps by
   `D1ProviderHttpBudget` **before** Fetch. Network errors, aborts and
   malformed responses do not refund quota; no implicit retries occur.
4. Rejects 3xx manual redirects, HTTP 401/403/429/5xx, malformed JSON and
   responses exceeding 256 KiB. Each attempt has a bounded 15-second default
   timeout and an absolute 30-second configured maximum.
5. Invokes the compiled Kotlin Google Flights parser but returns only
   token-free coarse diagnostics: route, completion state, returned offer
   count, completed/attempted call counts and a controlled error code. It
   does **not** expose parsed provider tokens, a public ResultSet, booking
   availability or airline refundability. A partial batch is not 'no fares'.

The transport is intentionally **not** a complete user search. Before any
owner-approved live rollout, compose it with the durable attempt ledger and
snapshots (#123), Kotlin eligibility/ResultSet construction (#121), the
prepared-quote and execution API (#122), the explicitly confirmed frontend
flow (#124), trusted Site auth verification (#63), and synthetic release
gates (#135). Until then, do not enable any paid egress or give the browser
the SerpApi credential. Run offline tests with
`node --test sites-runtime/tests/serpapi-metered-transport.test.mjs`.

**Packaging note:** This module is not yet in the read-only Sites handoff bundle because no public route uses it. Add it to the trusted Worker package when the consent-to-execution orchestration is integrated and its end-to-end tests pass. This avoids inadvertently enabling a dormant paid provider transport in the existing deployed Site.
