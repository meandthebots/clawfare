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

## Remaining requirements

- Authenticate and scope data to approved identities, including protection
  against cross-origin browser mutation and enumeration.
- Confirm and bind a specific estimated query to a single-use consent token.
- Implement hosted D1 fare history/cache, schema migrations and recovery.
- Provide real Kotlin/JS runtime integration and external-provider test doubles.
- Test the actual D1 binding under concurrent hosted invocations before
  production egress is enabled.
