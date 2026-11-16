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

## Remaining requirements

- Authenticate and scope data to approved identities, including protection
  against cross-origin browser mutation and enumeration.
- Confirm and bind a specific estimated query to a single-use consent token.
- Implement hosted D1 fare history/cache, schema migrations and recovery.
- Provide real Kotlin/JS runtime integration and external-provider test doubles.
- Test the actual D1 binding under concurrent hosted invocations before
  production egress is enabled.
