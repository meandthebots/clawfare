# Hosted SerpApi execution gate (issues #121, #122, #123, #135)

The backend now supports an **authenticated, strictly one-way GBP** SerpApi
execution route. It is **not automatically enabled or deployed**.

## Safety and activation contract

The real Worker still requires all of the following, controlled on the
**server only**, before it can dispatch any provider HTTP:

- Authenticated ChatGPT/Sites ingress (verified platform callback; never headers
  from a visitor), a private OWNER_KEY_SECRET, and D1 migrations 0001–0005.
- SEARCH_PREPARE_ENABLED=true to issue quotes (no provider calls).
- SEARCH_EXECUTION_ENABLED=true **and** SEARCH_PAID_CALLS_APPROVED=true; both
  must be independently set. They default to false, even when a credential exists.
- Private SEARCH_CONSENT_SIGNING_SECRET of at least 32 bytes and SERPAPI_API_KEY.
- Explicit positive SerpApi daily/monthly local D1 cap. No configured cap
  means no provider egress. Quote checks are advisory; each HTTP request is
  reserved atomically before Fetch.
- A same-origin POST /api/prepare-search providing one-way, GBP, SerpApi only,
  fetchReturns=false, maxLiveCalls between 1 and 16. The server returns the
  **exact Kotlin canonical query** and an HMAC-signed, five-minute single-use
  token; the user must explicitly approve the bounded request set.
- A same-origin POST /api/search body of
  {"canonicalQuery":<the exact returned query>,"consentToken":"<signed token>"}.
  One changed field or replay is rejected. Only the authenticated server
  controls the owner, capabilities, provider credential and bounded Fetch.

No application HTTP route accepts raw provider URLs, unverified booking tokens,
browser-provided foreign exchange rates, user email as owner key, or a simple
"confirmed":true bypass. There is no automatic provider retry.

**DO NOT activate these flags or deploy to the existing private Site without
separate owner approval** after verifying the public Sites platform identity,
D1 persistence and permissions, budget alarm/kill switch, and a tiny paid
provider smoke test. The synthetic CI test uses a stubbed HTTP provider and
does not prove live provider account status.

## Result/provenance behaviour

Only token-free, parsed fare drafts go to the shared Kotlin/JS assembler. It
applies the same carrier, transit, operating-carrier and cabin eligibility
rules as the JVM. The result is a normal browser ResultSet shape, saved with
atomic original-currency price observations under the verified owner ID.
Every consented route has its D1 attempt record inserted before network
dispatch; incomplete/failed coverage is never labelled a verified absence of
flights. GET /api/attempts/{nonce} reads these records for that owner only.

This release intentionally supports **GBP one-way fares only**. It creates no
seller URLs, refund proofs, return-leg prices, or unverified FX rates. Provider
response tokens, including booking/departure tokens, are never returned or
persisted. Round trips, multi-provider comparisons, true booking URLs, the
hosted React confirmation/grid flow, and server-driven price caching remain
future integration work. The Site UI can still be estimate-only.

## Test gate

The CI workflow builds the actual Kotlin/JS production bundle and runs
\`sites-runtime/tests/hosted-search-synthetic.mjs\` against real in-memory
SQLite migrations and fake SerpApi HTTP. It verifies:

- All flags OFF by default, quote with zero egress, signed one-time consent,
  owner binding, and 2-adult one-way fares.
- D1 route-attempt and provider cap reservation **before** provider HTTP.
- JVM/Kotlin/JS common parser → draft → eligibility → ResultSet.
- Atomic D1 snapshot and original fare price; reopen/history, cross-owner
  isolation, token redaction, replay rejection, no unintended repeat billing.

This CI cannot verify the real Sites identity callback or live D1 binding.
