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
- `~/.clawfare/batch-alerts.ndjson`: new and material price-drop notices for
  recently retrieved live (not mock/cached) quotes with **complete all-in
  costs**. Alerts explicitly say fare/refundability is *unverified*.
- `~/.clawfare/data.db.backup-*.sqlite`: migration snapshots, also
  sensitive. Back up database and logs to private durable storage.

An ordinary GitHub Actions runner has an ephemeral filesystem. Do **not**
set up a scheduled runner without a secure external persistent state store,
encrypted secrets, concurrency controls and a spend cap shared across runs.
A repository workflow alone cannot guarantee those properties.

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
