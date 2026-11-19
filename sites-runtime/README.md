# Hosted provider-call reservation foundation

Clawfare's JVM implementation already uses `ProviderHttpBudget` plus
`MeteredProviderHttp`. This directory provides the **independent D1 adapter**
needed when Kotlin/JS runs in a ChatGPT Sites/Workers-style hosted runtime.
Its provider/URL allowlist mirrors the JVM HTTP boundary.

**This does not create any public HTTP endpoint or deploy the site.** The
entrypoint, authentication, privacy scoping, estimate/consent confirmation,
and Kotlin/JS provider HTTP integration in issue #42 remain prerequisites.

## Durable D1 setup

Apply `migrations/0001_provider_http_budget.sql` to the Site's D1 binding.
Then set provider caps through a **trusted administrative action** (never
through an unauthenticated browser request), for example:

```sql
INSERT INTO provider_http_limits(provider, daily_cap, monthly_cap)
VALUES ('serpapi', 8, 50)
ON CONFLICT(provider) DO UPDATE SET
    daily_cap = excluded.daily_cap,
    monthly_cap = excluded.monthly_cap;
```

Each provider you intend to use needs an explicit cap. A missing row or a
zero daily cap prevents a new reservation. No defaults grant paid API access.
These sample numbers are examples, **not recommended or provider-issued
allowances**. Caps are UTC-day/UTC-month counters, not proof of billing.

The adapter uses one parameterized `INSERT ... SELECT ... WHERE COUNT < cap
RETURNING id` per request. SQLite serializes these write statements, so
concurrent callers cannot both pass based on a stale read-then-write count.
Each reservation persists **before** network dispatch. Completing it never
refunds the cap; an aborted, crashed, timed-out or 429 request remains counted.
The database stores no HTTP URL, opaque booking token, secret, request body,
search query or customer identity.

The `meteredProviderFetch` entrypoint permits only the read/search/account
provider paths used by the JVM implementation, rejects order/payment paths,
uses HTTPS and forces `redirect: "manual"` to avoid forwarding credentials
through redirects. It performs no internal retries.

## Wiring to Kotlin/JS

The Kotlin shared core `ClawfareJs` provides normalized search plans and
an advisory request-budget projection. A future Sites request handler should:

1. Authenticate the user and enforce request origin/session policy.
2. Normalize and validate the request with Kotlin/JS shared logic.
3. Estimate upper-bound provider calls using the shared projection, then
   issue a short-lived, request-bound consent token **without any paid call**.
4. On explicit confirmation, validate the same normalized query and consent.
5. Invoke `meteredProviderFetch` for each allowed provider request. Do not
   expose a second unmetered `fetch` path for provider endpoints.
6. Persist resulting fares and provenance in D1; keep API keys in server
   secrets only and use prepared queries throughout.

The reservation layer is **not itself an authentication or consent layer**.
Do not treat availability of a D1 binding as permission to spend.

## Local tests (no API calls)

```sh
node --test sites-runtime/tests/provider-http-budget.test.mjs
python3 -m unittest discover -s sites-runtime/tests -p 'test_*.py' -v
```

The Python suite extracts the exact production reservation SQL from the JS
adapter, runs it against SQLite with the migration, tests cap rollover and
simultaneous writers. Node tests exercise the D1 binding interface via a fake,
the provider URL allowlist, and reserve-before-egress semantics. Hosted D1
integration tests remain required before enabling actual network requests.

## Tenant-scoped investigation and fare-history store

Migration `migrations/0002_investigation_snapshots.sql` adds a **separate,
minimal hosted representation** of a saved `ResultSet` and an immutable
price-observation ledger. This is not a 1:1 migration of the JVM Exposed
`Investigations`/`Flights`/`PriceHistory` schema, and does not yet
migrate existing local SQLite database records.

The `D1Investigations` adapter in `src/investigations.mjs` supports:

- `saveSnapshot(ownerId, result)`: upsert Kotlin `ResultSet` JSON, bounded
  to 512 KiB, never `StoredResult` with provider tokens.
- `loadSnapshot(ownerId, slug)` and `listSnapshots(ownerId, limit)`:
  prepared SQL with required tenant scoping, deterministic ordered listing.
- `recordPrice(ownerId, slug, observation)`: immutable, SHA-256-keyed
  idempotent price observation preserving provider, original currency,
  amount as a decimal string, captured timestamp, and live/cached/mock
  provenance.
- `priceHistory(ownerId, slug, itineraryId, limit)`: ordered tenant-
  and investigation-scoped history. The corresponding parent snapshot must
  already exist; SQLite foreign keys prevent orphan observations.

**Security contract:** `ownerId` is a stable opaque subject identifier
from trusted server-side authentication. Never derive it from an arbitrary
HTTP header, path or request body. The adapter validates key formats but
**does not authenticate callers itself**; no hosted HTTP routes are enabled.
The server must gate reads and writes before calling these methods.

The D1 migration uses composite `(owner_id, slug)` keys and composite
foreign keys to prevent cross-tenant joins. No raw provider responses,
opaque booking tokens, API credentials, payment data, or provider URLs
are stored in the observation ledger. The snapshot JSON can include
traveller search details and should be treated as private user data;
do not import public untrusted JSON without Kotlin model validation and
a server-side authentication boundary. Logs must not echo snapshots.

Run tests with:

```sh
node --test sites-runtime/tests/*.test.mjs
python3 -m unittest discover -s sites-runtime/tests -p 'test_*.py' -v
```

Before production: implement identity binding, scope-aware route tests,
D1 native binding tests, secure data migration/import, storage retention
and deletion, consistent fare updates, and a Kotlin/JS request handler.
This module is not a deployed Site.

## Authenticated read-only HTTP adapter

`src/read-only-api.mjs` contains a **library**, not a deployed
Cloudflare Worker or Sites entrypoint. Once Sites provisions a trusted
authentication gateway, call `handleReadOnlyApi(request, options)`
from the platform's route adapter with:

- `db`: the D1 binding;
- `getVerifiedEmail(request)`: a trusted platform identity function, never
  an unchecked caller-supplied email or ordinary request header;
- `ownerKeySecret`: a private, random, stable server-side secret at least
  32 bytes long; rotation requires migrating the pseudonymous owner keys;
- `cryptoApi` (optional): the platform's WebCrypto API.

Sites documents `oai-authenticated-user-email` as a forwarded identity header
for signed-in visitors **when Sites controls request ingress**. A standalone
Worker or a public proxy that accepts the same header unverified must never
be treated as authenticated. This repository does not introduce that
unchecked ingress path. A runtime integration test must prove that callers
cannot spoof identity before the handler is exposed.

The handler returns `null` for non-API paths so the Sites frontend can
be served separately. Supported GET routes are `/api/status`,
`/api/quota`, `/api/searches`, `/api/results/{slug}`,
`/api/sample` (only after a verified account sample import),
and `/api/history/{slug}/{itinerary}`. The original browser UI's
remaining mutation routes (estimate, paid search, seller search, tracking,
export POSTs) deliberately return HTTP 405 and **cannot spend quota**.
Response headers disable caching of personal data; every query is scoped
to an HMAC-derived owner key rather than storing raw user email addresses.

Integration with the Kotlin/JS compiled business logic remains required
before enabling new searches. The UI's existing static sample preview
remains the immediate no-backend demonstration.

Reference: https://learn.chatgpt.com/docs/sites/data-and-connections

## Shared read-only demo fixture

Migration `0003_public_sample_fixtures.sql` stores one publicly shareable
sample separately from personal investigations. A trusted bootstrap process
calls `bootstrapPublicSample(DB, kotlinResultSet)` using **only** the
`clawfare-sample.json` artifact generated from the JVM Kotlin
`report sample-json` command. There is deliberately no network import
or public mutation endpoint.

The bootstrap validates the exact expected fixture ID, nonempty sample
itineraries, non-live pricing, absence of provider-token and secret fields,
and the 512 KiB payload limit. It records a SHA-256 digest. Reads validate
the digest again and refuse to serve corrupted/tampered data. The existing
authenticated GET `/api/sample` uses this public fixture table, rather
than writing a copy to every visitor's personal saved investigations.
Private investigation listings/history remain owner-scoped.

Validate the actual Kotlin-generated artifact:

```bash
bash scripts/build-sites-preview.sh build/sites/clawfare-sample.html
node scripts/validate-kotlin-public-sample.mjs build/sites/clawfare-sample.json
```

This is not yet an automated Sites deployment or provisioning job: only a
trusted Sites runtime bootstrapper may receive the D1 binding and call
`bootstrapPublicSample`. No operator account IDs, provider keys, bearer
tokens or private search results belong in public sample records.

## D1 public sample initialization after deployment

ChatGPT Sites may apply D1 migrations only during deployment, without a
separate hosted pre-deploy seed action. The Fetch adapter now optionally
accepts `bundledPublicSample`: the **server-side, checked-in Kotlin public
fixture** from `server/fixtures/clawfare-sample.json`. If provided, the
adapter validates and copies it at handler construction. On the first
*authenticated* `GET /api/sample`, it checks the hosted D1 fixture table
and, if empty, performs one bounded, idempotent
`INSERT ... ON CONFLICT DO NOTHING`; then reads back and validates the
SHA-256 integrity and exact bundled sample. This runs after migrations are
available and does **not** require an admin import endpoint.

It never inserts a private investigation, accepts a sample from HTTP input,
or makes provider requests. An unauthenticated request cannot initiate the
seed. Concurrent first reads are safe; the code must never overwrite
existing fixture data or silently replace a corrupt/older version. On a
mismatch, it fails closed and requires an explicit reviewed migration.

If `bundledPublicSample` is absent, the previous behavior remains: GET
returns a 404 until a trusted admin explicitly imports the sample. The
legacy `bootstrapPublicSample` administrative API is still available.
The hosting adapter must supply the bundled file as a server-only import,
not from the client request, and the actual Sites sign-in gate must be
verified after an owner-only deployment. A saved, undeployed Site version
cannot demonstrate live D1 or identity security.

## Hosted estimate-only planning (zero provider egress)

The authenticated Fetch API can optionally expose `POST /api/estimate`
when the prebuilt Kotlin/JS `fare-core` bridge is supplied to
`createReadOnlySitesHandler`. The bundled Sites Worker factory supplies
this bridge by default. The original HTML enables **Estimate calls** but
keeps **Run search** and all other mutations disabled in hosted mode.

The estimator enforces JSON MIME type and same-origin requests, caps
request bytes and date/airport fanout *before* invoking Kotlin, then uses
the actual Kotlin `planSearchJson` and `projectBudgetJson` exports for
flight dates and upper-bound provider request accounting. It does not
invoke provider HTTP, query credentials or quota, write D1 records,
issue consent tokens or expose any write endpoint. The response is
explicitly hypothetical (`canRun:false`), with all provider quotas marked
unconfigured. `cacheOnly` cannot magically return live fares because
the hosted provider response cache is not implemented.

**Only POST /api/estimate is permitted.** POST /api/search and
vendor/track/export actions still return HTTP 405. Do not enable paid
search until the separate durable quote/consent, provider-adapter,
per-tenant rate limit and actual Sites identity tests are complete.

## Remaining requirements

- Authenticate and scope data to approved identities, including protection
  against cross-origin browser mutation and enumeration.
- Confirm and bind a specific estimated query to a single-use consent token.
- Implement hosted D1 fare history/cache, schema migrations and recovery.
- Provide real Kotlin/JS runtime integration and external-provider test doubles.
- Test the actual D1 binding under concurrent hosted invocations before
  production egress is enabled.

## Dormant signed search-consent foundation (issue #67)

Migration `0004_search_consents.sql` and `src/search-consent.mjs` introduce a
**non-exposed, disabled-by-default** D1-backed approval capability, used only
by a future provider search route:

1. The trusted Sites gateway authenticates the viewer and derives the opaque
   `acct_<sha256>` owner key. The compiled Kotlin planner produces the exact
   canonical unconfirmed query JSON and a fully bounded call plan.
2. When explicitly enabled in a future server integration, `issue` verifies
   that the server has a provider credential, that D1 daily/monthly caps can
   cover **every planned call** and that the whole plan is no more than 16
   provider requests. This does **not** reserve billing quota or contact a
   provider. The signing key is a distinct server-only secret (not the owner
   derivation secret); never send it to a browser.
3. A signed consent token is bound to the canonical query's SHA-256 digest,
   opaque owner, exact provider/category call bounds, policy version, expiry
   (at most five minutes) and random nonce. Only the nonce and metadata are
   saved in D1; no raw flight query, fare tokens or credentials.
4. On confirmation the authenticated, same-origin application boundary
   validates the signature, exact query, policy, owner and expiry and performs
   **one atomic conditional D1 UPDATE ... RETURNING** to consume the nonce
   before any HTTP is attempted. Replays, double clicks and Worker restarts
   cannot authorize another search. A failed call is never silently retried
   with a reused approval.
5. **Before every network request**, the search executor must independently
   cross-check the per-query category/count limits and use
   `D1ProviderHttpBudget.reserve`. An approved quote is not permission to
   exceed D1 caps, redirect to a new provider host or issue seller/order
   calls. Persisting a quote does not debit a provider quota.

**Not yet implemented:** No hosted API route calls this module. The Worker
still hard-disables `/api/search`, and this package does not allow a browser
to issue or consume a quote. Future #67/#68/#121/#122 work must integrate the
compiled Kotlin plan, authenticated prepare route, explicit user confirmation,
actual per-request limit accounting and the synthetic end-to-end test before
enabling the egress switch. Never deploy a live-provider secret, enable paid
calls or treat a successful preview build as permission to spend. Existing
Site identity and D1 isolation must still be checked under #63.

Regression tests:
`node --test sites-runtime/tests/search-consent.test.mjs`
and `python3 -m unittest discover -s sites-runtime/tests -p 'test_*.py'`.

## Shared provider fare eligibility (issue #121)

The compiled Kotlin/JS host now exports
`evaluateOfferEligibilityJson` from `fare-core/commonMain`.
The JVM `OfferEligibility`, `TransitRules` and airline display filters reuse
the same pure algorithm for observed operating-carrier verification,
carrier-code/alias matching, per-leg cabin classes, disconnected segments
and transit exclusions.

The bridge accepts `EligibilityEvaluationRequest` containing a typed
`SearchQuery`, observed outbound/return `Direction` segments and an
optional **trusted** `airportCountries` reference. Unlike the JVM, the
hosted Worker has no OurAirports CSV loader. A future authenticated executor
must provide a versioned and reviewed country reference or leave
`airportCountries` empty. Empty/missing coverage with country exclusions
fails closed (`unknown_transit_country`), **not** an inferred eligible fare.

`EligibilityEvaluation` returns only `eligible` and a stable exclusion
reason; no credentials, booking tokens or refundability claims are involved.
Until the full Kotlin ResultSet assembler and hosted executor are wired,
this remains an internal, no-egress bridge. Cross-target tests in
`PortableEligibilityTest` exercise country exclusions, airline aliases,
unknown operating carriers and strict business cabin requirements.
