# Schema Migration: Prices to price_history

## Goal
Move prices from `flights` table to `price_history` table to support:
1. Multiple price observations per flight
2. Different sources (agents) recording prices
3. Proper sync between distributed databases

## Current Schema

### flights table
```sql
-- Has price columns:
price_amount DOUBLE PRECISION NOT NULL
price_currency TEXT NOT NULL
price_market TEXT NOT NULL
price_checked_at TEXT NOT NULL
```

### price_history table
```sql
id INTEGER PRIMARY KEY AUTOINCREMENT
flight_id TEXT NOT NULL
amount DOUBLE PRECISION NOT NULL
currency TEXT NOT NULL
checked_at TEXT NOT NULL
source TEXT DEFAULT 'kraftwerker'  -- Already added
```

## Target Schema

### flights table
```sql
-- Remove: price_amount, price_currency, price_checked_at
-- Keep: price_market (it's about the ticket, not observation)
-- Keep: stale (for marking flights needing refresh)
```

### price_history table
```sql
id INTEGER PRIMARY KEY AUTOINCREMENT
flight_id TEXT NOT NULL
amount DOUBLE PRECISION NOT NULL
currency TEXT NOT NULL
checked_at TEXT NOT NULL
source TEXT NOT NULL  -- Who recorded this price
price_market TEXT NOT NULL  -- Which market (UK, US, etc.)
```

## Code Changes Required

### 1. Database Layer (db/)
- [x] Tables.kt - Remove price columns from Flights object (already done previously)
- [ ] Queries.kt:
  - FlightDto: Remove priceAmount, priceCurrency, priceCheckedAt
  - Add FlightWithPrice: Wrapper combining FlightDto + latest price
  - FlightQueries: Add listWithPrices(), getWithPrice() 
  - Remove updatePrice(), updatePriceCheckedAt()
  - PriceHistoryDto: Add priceMarket field
  - PriceHistoryQueries: Update create() to include priceMarket

### 2. CLI Commands (cli/Commands.kt)
Most commands use FlightDto and expect price fields. Need to:
- FlightListCommand: Use listWithPrices() instead of listByInvestigation()
- FlightShowCommand: Use getWithPrice() instead of getById()
- FlightPriceCommand: Add to price_history instead of updating flight
- FlightRefreshCommand: Record new prices to price_history
- FlightValidateCommand: Use FlightWithPrice
- FlightDedupeCommand: Use FlightWithPrice for price comparisons
- FlightExportTasksCommand: Use FlightWithPrice
- FlightImportResultsCommand: Create price_history entries
- FlightMatchCommand: Use FlightWithPrice

### 3. Output (cli/Output.kt)
- formatFlight(): Accept FlightWithPrice
- formatFlightTable(): Accept List<FlightWithPrice>

### 4. Export/Import (export/, importer/)
- JsonExporter: Export flights + price_history separately
- JsonImporter: Create price_history entries on import
- TopNGenerator: Use FlightWithPrice

## Migration Steps

1. **Add source column to price_history** (if not exists)
2. **Migrate existing prices**: INSERT INTO price_history from flights
3. **Update Kotlin code** (all files above)
4. **Drop price columns** from flights table
5. **Test thoroughly**

## Sync Protocol

With this schema, sync becomes:
```json
{
  "flights": [/* static flight data */],
  "priceHistory": [
    {"flightId": "abc", "amount": 911, "checkedAt": "...", "source": "agent-a"},
    {"flightId": "abc", "amount": 945, "checkedAt": "...", "source": "agent-b"}
  ]
}
```

Merge rules:
- Flights: upsert by ID (static data)
- Price history: INSERT all, dedupe by (flight_id, checked_at, source)

## Backup and offline recovery

**Do not back up an active SQLite WAL database using `cp data.db` alone.**
Committed rows may still live in `data.db-wal`, so copying only the main
file can produce a stale or incomplete backup.

Clawfare automatically creates a **transaction-consistent** `VACUUM INTO`
snapshot before a schema-version upgrade. The snapshot is created inside a
random private directory (mode **0700**) next to the source database; the
completed SQLite file is verified with `PRAGMA integrity_check`, set to
**0600**, flushed and then published to
`data.db.backup-<epoch>-<random>.sqlite` without replacing prior backups.
Temporary staging files are removed even when snapshot generation fails;
migration aborts rather than proceeding without a verified recovery point.
A snapshot includes committed WAL changes but not uncommitted transactions.

This mechanism currently requires a POSIX filesystem supporting private
permissions and hard links. On unsupported filesystems, migrations fail
closed instead of leaving potentially world-readable snapshots; a Windows
ACL-based equivalent is future work. Existing database, backup and journal
paths should reside in a restricted user-owned directory. These files are
**not encrypted** and their deletion is not secure erasure.

### Recovery rehearsal (offline)

1. Stop Clawfare and every other process writing the database, including
   scheduled collectors. Never replace an open SQLite database.
2. Identify the desired `*.backup-*.sqlite` file; preserve it unchanged.
   Check `PRAGMA integrity_check` returns `ok` and inspect the stored
   `PRAGMA user_version` and investigation/price rows before restoring.
3. Make a separate, private copy of the **current** database together with
   its `-wal` and `-shm` sidecars (if present). Do not discard those files
   or assume a plain copy of an active database represents a complete backup.
4. Copy the verified snapshot to a new recovery file with owner-only
   permissions (for POSIX, `install -m 0600 <backup> <recovery.db>`) and test
   opening it with SQLite. It should open independently of the original WAL.
5. After validation, move the original database and its sidecars aside as a
   matched recovery set, install the verified snapshot at the intended
   database path, then start Clawfare. An older schema will undergo migration
   again, creating a **new** backup. Check investigations, price history and
   error logs before considering recovery complete.

There is **no automatic live restore** command and no complete
disaster-recovery guarantee across operating systems. An opt-in,
AES-GCM encrypted, WAL-consistent SQLite-only archive and
restore-into-a-**new-file** workflow is available with `clawfare db backup`
and `clawfare db restore`; see [ENCRYPTED-RECOVERY.md](ENCRYPTED-RECOVERY.md).
It does not include budget/alert/audit journals or replace existing databases.
Store off-host copies securely; restoring a prior snapshot intentionally
loses writes committed after its capture.

## Fare search columns (additive)

`ClawfareDatabase.runMigrations()` adds these with `ALTER TABLE ... ADD COLUMN` when `PRAGMA table_info` does not already list them. Existing investigations and flights keep working. The `source_url` backfill from `flights.share_link` runs only when that column exists.

### investigations
| Column | Purpose |
| --- | --- |
| origins, destinations | Extra airports. `origin` / `destination` stay the primary 3-letter codes. |
| checked_bags, passengers, currency | Search defaults. Currency display is GBP. |
| nights, scenario | Trip length, and `florida` for the sample feasibility grid. |
| query_json | The search that was run, so `fare reingest` can parse the cache again. |
| presentation_json | Title, notes, suggested filters, and warnings for the web UI. |

### flights
| Column | Purpose |
| --- | --- |
| checked_bags, passengers, cabin_class | Copied onto the itinerary. Per-leg cabin stays on each leg. |
| source_label, live_price | `sample`, `live`, `cached`, or `mock`. Duffel is always mock. |
| display_id | Stable id shown in the grid (`11`, `H`, provider id). The primary key is still the share-link hash. |
| planned_return_date | Return date when the return legs were not fetched. Those rows are stored as one-way and tagged `return-missing`. |
| links_json | Google Flights exact-itinerary link plus airline and seller links. `share_link` stays a `/booking?tfs=` URL so validation still rejects a search page. |

Legs may include `operating_carrier` and `operating_carrier_code`. A segment may include `layovers` when the provider's connection time cannot be derived from the clock times.

### seller_offers
One row per seller quote on an itinerary: fare, bag fee, round trip, the two one-ways, all-in GBP, and a trust/notes field. Replaced as a set when a comparison is saved.

### provider_cache
Raw provider responses, keyed by provider and a hash of the request with credentials removed. This is a cache for re-ingest, not the fare record. Bodies are redacted before insert.

### flight_provider_tokens
`departure_token` and `booking_token` for a later return or booking-options call, without spending the search again.


---

## Additional fare-search migration safeguards

The database now uses `PRAGMA user_version = 2` after successful schema
initialization. Version 2 adds the nullable `flights.refund_evidence_json`
column, preserving supplier cancellation terms without rewriting old flight
records. Existing version-1 databases receive an online recovery snapshot
*before* the additive migration. Versions 0 and 1 are both supported. Prior to upgrading an existing version-0 database, Clawfare
acquires a process-wide advisory lock file (`<database>.migration.lock`)
and creates a crash-consistent SQLite `VACUUM INTO` snapshot named
`<database>.backup-<epoch>.sqlite`. Backups are never overwritten. If a
migration fails, the backup remains available for manual restoration.

The lock coordinates Clawfare processes using this code; it does not control
unrelated SQLite writers that ignore the lock file. The database should not
be shared on an unreliable/network-mounted filesystem. Back up the DB and
its backups separately from the repository. Migration changes remain
additive, and PRAGMA version is advanced only after migration succeeds.

### Sensitive provider data

`flight_provider_tokens` contains Google Flights departure/booking tokens
in **plaintext SQLite**, and `provider_cache.body` may also contain
provider-originating tokens and itinerary details. These are not API keys,
but possession of them could reveal searches or support replay. The app
does not publish them from its normal result endpoints. Restrict local
filesystem access to the account running Clawfare; do not commit the SQLite
DB, cache or backup files, and do not upload them to public CI artifacts.
API credentials are stripped/redacted on cache writes, but do not assume
that raw response snapshots are free of travel-related private data.

### Offer freshness and FX

Live provider responses expire after two hours for the purpose of a new
search. `--cache-only` and re-ingestion still allow historical quotes
without new API calls; those observations are not bookable quotes.
Travelpayouts monthly calendar responses remain labelled cached.

The embedded `Fx` snapshot was observed **14 October 2026**. Cross-currency
Top-N reports declare that reference date and skip currencies without a
known conversion. It is indicative, not a current exchange rate. A dated
external FX update mechanism should replace this fallback before spending
significant money on international comparison searches.
