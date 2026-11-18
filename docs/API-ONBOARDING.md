# Provider credentials and one-shot collection

Clawfare searches flight offers, stores quote snapshots in its **local SQLite
investigation database**, and can compare thousands of combinations without
ticketing, payments or passenger identity collection.

## Account setup (manual, outside GitHub)

1. Obtain a SerpApi account and API key from https://serpapi.com/. Set
   `SERPAPI_API_KEY` in the environment of the **local process**, not in source
   code. Use `fare quota` to inspect available quota without a flight search.
2. Optionally set `SEARCHAPI_API_KEY` for a second Google Flights search source,
   https://www.searchapi.io/. It has separate usage limits and account terms.
3. Optionally set `TRAVELPAYOUTS_API_TOKEN` for cached discovery. These fares
   are not live bookable quotes.
4. Create a **Duffel test token** at https://duffel.com/, typically prefixed
   `duffel_test_`. Test-mode prices are always labelled mocks.
5. For **real Duffel inventory**, arrange live activation with Duffel, then
   configure a `duffel_live_` token and explicitly set
   `DUFFEL_LIVE_ENABLED=true`. Live offer searches can spend quota. The code
   does not create orders or charge money. The API's zero-penalty cancellation
   field **does not prove** original-payment cash reimbursement or absence
   of servicing fees. Independently confirm the actual fare terms.

Keep secrets outside Git, Shell history, logs and CI artifacts. Prefer a
private OS secret store or read-only `.env` sourced by your shell. Never commit
actual travel configuration, credentials, responses or the SQLite database.

## Before any paid calls

Set your own private batch config outside the repository following
[batch search](BATCH-SEARCH.md). Use small route/date matrices initially.

```sh
fare quota
fare batch plan /private/travel-search.json
fare batch run /private/travel-search.json
fare batch run /private/travel-search.json --confirm
fare batch report /private/travel-search.json --out /private/offers.csv
```

These dry-run commands do not call fare-search APIs. A confirmed run
never proceeds if the configured **maximum possible live calls** (including
possible return expansions) exceeds `maxLiveCalls`.

A watch cycle also reserves its worst-case possible requests in a durable
`~/.clawfare/batch-budget.ndjson` journal **before** any flight API call.
The default cumulative UTC-day budget is `maxLiveCalls`: a full-size watch
run can execute once per day. To allow multiple bounded runs, set an explicit
 `maxDailyLiveCalls` in the private JSON, for example `20`. A minimal
private config excerpt is:
```json
{
  "maxLiveCalls": 8,
  "maxDailyLiveCalls": 20,
  "minQuotaReserve": 5,
  "requireNumericQuota": true
}
```
All cycles on
the same machine and local data directory share the same journal and lock.
If a cycle crashes after the reservation, those calls remain reserved until
the next UTC day to protect against accidental overspend. If the journal is
corrupt or missing entries, restore it from private backup rather than
discarding past reservations. Removing the ledger resets the safeguard.

For additional protection, set `minQuotaReserve` (for instance 10) in the
batch JSON. Set `requireNumericQuota=true` to **fail closed** whenever a
configured provider cannot report a numeric remaining quota. This works
best with Google search APIs; some providers do not report numerical quota.

## One-shot recurring check

```sh
fare batch watch /private/travel-search.json
fare batch watch /private/travel-search.json --confirm --drop-gbp 150
```

One confirmed invocation runs **once**, under the same cap and quota
preflight checks. It uses a local advisory lock to prevent simultaneous
Clawfare watch cycles on the same machine. On error/rate-limit signals,
a batch stops early rather than aggressively retrying paid requests.

An external system such as your laptop's cron or task scheduler may invoke
that command periodically *after you explicitly enable it*.
For example, on a private Linux/macOS machine with a persistent encrypted
home directory:

```cron
0 7 * * * /private/path/to/clawfare fare batch watch /private/travel-search.json --confirm --drop-gbp 150
```

The example assumes all required provider credentials are supplied by the
execution environment. The file and script paths are placeholders.
No background job, email integration or hosted service is activated by
checking in this code.

### Durable local data

- `~/.clawfare/data.db`: investigations, offer price history and cached
  provider responses. Treat it as sensitive. It contains unencrypted
  departure/booking tokens. Do **not** commit or upload it.
- `~/.clawfare/batch-attempts.ndjson`: append-only, one record per attempted
  route/date search, with a hash of the normalized query, provider names,
  timestamp, maximum call reservation and status. No API secrets.
- `~/.clawfare/batch-budget.ndjson`: UTC-day maximum-call reservations,
  synced to disk before any watch flight-search request. Protect and back up
  this file with the database.
- `~/.clawfare/batch-alerts.ndjson`: new and material price-drop notices for
  recently retrieved live (not mock/cached) quotes with **complete all-in
  costs**. Alerts explicitly say fare/refundability is *unverified*.
- `~/.clawfare/data.db.backup-*.sqlite`: migration snapshots, also
  sensitive. Back up database and logs to private durable storage.

An ordinary GitHub Actions runner has an ephemeral filesystem. Do **not**
set up a scheduled runner without a secure external persistent state store,
encrypted secrets, concurrency controls and a spend cap shared across runs.
A repository workflow alone cannot guarantee those properties.

## Local browser access and DNS-rebinding boundary

The JVM web UI binds to `127.0.0.1`, not a public network interface. Every
HTTP request (including the initial HTML, sample, status, quota, saved results,
and exports) requires a literal `Host` of `localhost` or `127.0.0.1`, with an
optional port. Do not trust a hostname that merely *resolves* to loopback:
an attacker-controlled DNS name can rebind to a local IP and otherwise read
private data through the browser's same-origin policy. Proxy-supplied
`X-Forwarded-Host` does not override this check.

Mutation routes additionally require a same-origin local session token,
JSON content type, and an origin match when browsers provide an `Origin`
header. Paid searches require an explicit estimate-bound, short-lived,
single-use confirmation token; local search requests are capped and
serialized. Inspecting provider account quotas from the local UI also requires
the same session header (without a JSON request body), so an unrelated
webpage cannot cause external quota-account requests using a simple
cross-origin GET. These inspections are throttled to one per 30 seconds
per server instance; repeats return HTTP 429 with `Retry-After`. The
browser sends the session header automatically for the **Check quota**
control; no change is needed for ordinary local use.

Seller lookups reserve a worst-case **seven provider requests per effective
market** (outbound/return/booking-option lookups). The server applies the same
normalization as execution before checking the web call cap: empty or blank
market lists use the default UK market, and case-insensitive duplicates count
only once. Cache-only seller lookups are exempt from the paid call cap, but
still allow at most two distinct markets. A zero-call budget rejects every
live seller lookup, including an empty market list, before granting consent.

For the localhost JVM web server, confirmed live flight searches and seller
lookups also share a **process-local 64-network-call hourly limit** by default.
Set `CLAWFARE_WEB_MAX_HOURLY_NETWORK_CALLS` to an explicit integer between
0 and 1000 to adjust it (0 disables all live web calls). After matching the
estimate/consent token, the web server reserves the entire worst-case call
count before dispatch; requests exceeding the remaining allowance return
HTTP 429 and `Retry-After`. Cache-only searches, seller cache reads, and
unconfirmed estimates do not consume the hourly allowance. The allowance is
atomic across simultaneous web requests and does not refund attempts that fail
or return partial results. It resets one hour after the first reservation in
a window. A server restart resets this **supplementary** limit; the durable
provider HTTP journal and its operator-defined daily/monthly limits remain
the cross-process and crash-safe controls (see #19).

The JVM API **streams and bounds actual JSON bytes** before
deserializing or invoking business logic: query, vendor and tracking requests
are limited to 64 KiB, while HTML/CSV export requests allow 2 MiB. Requests
over the route cap return HTTP 413 regardless of whether the client supplies
a Content-Length header. Malformed JSON returns a generic HTTP 400 without
echoing untrusted input; unexpected errors return generic HTTP 500 and do
not include exception messages or provider payloads. Avoid relying on
error-response strings for client diagnostics.

These protections stop remote web pages from silently using a victim's
browser to read investigations or spend provider quota.

**Not in the trust boundary:** other local processes, privileged browser
extensions and other users of the same OS account. Do not expose the JVM
server through a reverse proxy, tunnel, port forward or public interface:
the local token endpoint is not remote-user authentication. The ChatGPT
Sites deployment uses a separate attested-identity and D1 security model.

## Limits and expectations

API search results are not airline-issued refundable tickets. Never assume
a stale price, test-mode quote, expired Duffel offer or unknown refund
method is cash refundable to the original card. Use the fare's actual
conditions immediately before booking. Duffel's per-offer GET recheck
can update its price, but **never guarantees availability** at ticketing.

For restricted transit regions, install the public-domain
OurAirports `airports.csv` locally and set `CLAWFARE_AIRPORTS_CSV`;
unknown intermediate airports fail closed.

Provider onboarding is not complete until the user independently creates
and securely configures accounts/tokens. No genuine collection occurs
merely because the CLI and tests exist.
