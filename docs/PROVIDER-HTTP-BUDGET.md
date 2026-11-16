# Per-provider HTTP request reservations (issue #19)

The normal CLI/Ktor provider construction wraps its production HTTP client
in `MeteredProviderHttp`. Before *every* recognized provider request it
appends and fsyncs a private `reserved` event under a cross-process file lock.
After a response (including HTTP 429 and 5xx) or transport error, it appends
a `completed` event. If a process crashes between these, the reservation
still consumes its allowance. **This is worst-case network-request accounting,
not proof that the provider invoiced the request.** No retries happen inside
the meter. A manual retry requires another reservation.

## Operator configuration

No provider network request is permitted without an explicit daily cap.
Configure the limits for **each provider you intend to call**:

```sh
export CLAWFARE_HTTP_BUDGET_DIR="$HOME/.clawfare"
export CLAWFARE_HTTP_DAILY_SERPAPI=8
export CLAWFARE_HTTP_MONTHLY_SERPAPI=50
export CLAWFARE_HTTP_DAILY_SEARCHAPI=8
export CLAWFARE_HTTP_MONTHLY_SEARCHAPI=50
export CLAWFARE_HTTP_DAILY_DUFFEL=8
export CLAWFARE_HTTP_DAILY_TRAVELPAYOUTS=4
# A provider-specific zero daily cap explicitly disables that provider.
fare http-budget
fare batch plan ~/travel-search-private.json
```

These variables are **not authorization to place bookings or submit payments**.
You must separately invoke `fare batch run --confirm`, `fare batch watch
--confirm`, `fare fetch --confirm`, a confirmed seller lookup, or
`fare verify-duffel --confirm`. There is no background scheduler.

The default budget directory is `~/.clawfare`. The ledger file is
`provider-http-budget.ndjson` and the lock is
`provider-http-budget.lock`. Both are private local files. File permissions
are set owner-only on POSIX; Windows operators must restrict the directory
with their filesystem ACL. Back up the budget journal **together with**
SQLite and the earlier `batch-budget.ndjson` watch allocations. Do not
copy it between two live machines and then run them independently; use one
shared writable store/lock or a single collector host.

The meter recognizes only provider read/search/account paths, including
SerpApi and SearchAPI base, return and booking-option GETs, Duffel offer-request
POSTs and exact-offer GETs, and Travelpayouts cached search GETs. Any other
path, especially Duffel order/payment endpoints, is rejected before
dispatch. Neither opaque tokens nor URLs, payloads, user plans or credentials
are written into the journal; only provider, category, timestamp, request ID,
HTTP status and a coarse outcome. **Cache hits do not cross the HTTP
boundary and consume no provider HTTP reservation.**

A conservative request is counted against the UTC day and optional UTC
month in which it was reserved. Quota/status checks also reserve a request
even if a provider later considers it free. A missing cap, exceeded cap,
invalid journal line or unavailable lock blocks network dispatch. Results
show only `charge=unknown`; use provider account billing to reconcile
true chargeable usage externally. Cached searches may be served without
spending network quota.

The offline `fare estimate` and browser `/api/estimate` intentionally
do **not** make provider quota/account HTTP calls. `fare quota` remains an
explicit request that will be metered. Existing batch and watch caps
remain additional limits; reaching any stricter cap blocks a request.

## Outstanding #19 integration work

Metering protects the default production provider factory and all routes
that use it. Direct construction of provider classes with a custom HTTP
client is an injection seam for hermetic tests and is not covered automatically.
There is no automatic retry/backoff policy because retry cost and
idempotency differ by provider. Full billable credit reconciliation,
monthly allowance rollover from provider dashboards, timeout-specific
uncertain billing, per-run forecast vs actual/unknown reconciliation in
UI, and distributed multi-host quota coordination remain separate work.
