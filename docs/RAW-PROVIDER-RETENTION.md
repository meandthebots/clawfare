# Raw provider response retention and local file hygiene (#25)

Raw provider HTTP bodies and booking/departure tokens can expose searches,
dates, fare products and seller details. They are not necessarily account
credentials, but are still private. Treat raw payloads as **untrusted**.

The normalized flights, seller-price observations, fare-rule evidence and
price history are kept separately in SQLite; deleting an old
`provider_cache` entry must not silently erase those normalized records.

## Offline retention

`fare prune-provider-cache --days 90` previews the number of raw SQLite
provider-response entries with an observation timestamp older than 90
UTC days. **No deletion occurs without `--confirm`.** Then run:

```sh
fare prune-provider-cache --days 90
fare prune-provider-cache --days 90 --confirm
```

The command does not make API calls, does not book anything, does not
destroy normalized prices and does not delete private investigation
configuration or budgeting/alert journals. You can choose 1–3650 days.
Do not assume this removes stale tokens from a browser export, a
versioned database backup, filesystem snapshots or a SQLite WAL: a
database backup/retention program must cover those separately.

The optional programmatic `DiskCache.pruneOlderThan(cutoff, confirm)`
applies the same dry-run-by-default strategy to JSON files in immediate
per-provider cache folders. Corrupt files and files with unknown dates
are **not automatically deleted**. An operator should review and
manually quarantine those. Do not pass an arbitrary untrusted cache
directory to this operation.

## Private and atomic disk writes

Fresh `DiskCache` entries are written to a same-directory temporary file,
fsynced, and atomically renamed when the filesystem supports it. A
fallback rename is used only when atomic moves are unavailable; the
source temporary file is cleaned up. Files and cache directories are
restricted to owner read/write (directories additionally owner execute)
on POSIX filesystems. Windows environments need an owner-only ACL
configured separately. Pre-existing raw cache files are permission-
hardened when read; historic backup copies are not altered.

The command intentionally does **not** wipe or truncate the billing
reservation ledger: that could allow paid requests to bypass daily/monthly
limits after recovery. Private caches must never be committed to Git,
uploaded to public CI, or attached to public issues. Review provider
data-retention licences and privacy terms before extending storage.

## Remaining scope

- Expiring opaque provider tokens saved separately in `FlightProviderTokens`
  without erasing useful flight history.
- Secure export profiles: redacted default vs explicitly authorized local
  diagnostic export, with token scanning.
- WAL checkpoint / SQLite secure-delete policy, encrypted backup and
  restore verification, and complete retention for abandoned private
  investigations and exported reports.
- Provider-specific licensing and deletion requests.

No network access, paid provider request or live personal data was
used to test this functionality.
