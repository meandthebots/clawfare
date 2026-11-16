# Private, capped fare batch searches

This feature extends the investigation database; it does not create bookings.
**Do not commit your actual search JSON**: dates, cities and budgets may reveal
private travel plans. Keep it on local disk, with filesystem permissions for
your account only.

## Example (illustrative dates, not a live price quote)

Save as a local file outside the repository, e.g. `~/travel-search-private.json`.

```json
{
  "name": "flight-research",
  "maxLiveCalls": 40,
  "scenarios": [
    {
      "id": "nonstop-europe",
      "positioningGbp": 260,
      "hotelsGbp": 420,
      "groundTransfersGbp": 90,
      "baggageGbp": 0,
      "query": {
        "origins": ["CDG", "FRA", "MUC", "AMS", "CPH"],
        "destinations": ["HND", "NRT"],
        "outboundFrom": "2028-09-12",
        "outboundTo": "2028-09-14",
        "tripType": "one_way",
        "adults": 2,
        "cabin": "business",
        "cabinShortHaul": "economy",
        "strictPerLegCabin": true,
        "strictOperatingCarrier": true,
        "checkedBags": 2,
        "nonstopOnly": true,
        "providers": ["serpapi"],
        "excludedAirlines": ["LO", "AI", "VN"],
        "excludedTransitCountries": ["CN", "HK", "MO"],
        "market": "de",
        "currency": "EUR"
      }
    }
  ]
}
```

Every `positioningGbp`, `hotelsGbp`, `groundTransfersGbp` and
`baggageGbp` input is an explicit **whole-party cost** (including both
travellers). If any cost is omitted, the all-in total remains unknown.
Missing charges are *not* treated as zero.

### Commands

```sh
fare batch plan ~/travel-search-private.json
fare batch run ~/travel-search-private.json
fare batch run ~/travel-search-private.json --confirm
fare batch report ~/travel-search-private.json --out ~/flight-comparison.csv
fare batch coverage ~/travel-search-private.json --out ~/private-coverage.csv
fare batch run ~/travel-search-private.json --resume --confirm
```

The non-confirmed `run` command is dry-run only. An executable run is
refused if the full estimated maximum including return follow-ups exceeds
`maxLiveCalls`; it never silently increases the cap. Confirmed searches
are sequential, with a per-query cap set from the remaining budget.
The default provider response cache can prevent redundant calls.
The audit trail is appended locally at
`~/.clawfare/batch-attempts.ndjson` or `CLAWFARE_BATCH_AUDIT`. Attempt
records include success, provider call upper bounds and observed offer count.

Reports distinguish one-way and return tickets and never assume
partially used return tickets have a proportional refund.
The ranking uses the latest dated GBP FX snapshot or a labelled fallback,
and excludes non-live/mock records from estimated all-in prices.

### Connection-country filtering

Search constraints are enforced server-side before saving offers. Explicit
`excludedTransitAirports` removes specific airport codes, while
`excludedTransitCountries` removes *every connection airport* classified
in those ISO country/territory codes.

For comprehensive airport-to-country data, obtain the public-domain
`airports.csv` from OurAirports at
https://ourairports.com/data/ and set
`CLAWFARE_AIRPORTS_CSV=/private/location/airports.csv`.
Its required columns are `iata_code` and `iso_country`.
If a transit airport's country is unknown, a country-restricted search
**fails closed** and excludes that itinerary. A minimal built-in reference
covers major connection airports, but is deliberately not claimed complete.

Provider restrictions cover both marketing and operating carriers,
including codeshare-operated connections. Explicit filters must be
reviewed for each investigation: airlines can change operating carriers
without notice.

### Cost and coverage caveats

An estimated "live call" is not the number of returned offers: a provider
search may contain hundreds of offers, a truncated subset, or none.
Flight availability and full cash refundability require separate
verification against the actual fare terms. No-show returns are never
treated as cancellable for cash by default.

Existing Google Flights seller lookups can add more quota-spending calls
per selected offer; the batch process does not automatically run those.
No external scheduled job or airline API credentials are created by this
feature.


### Coverage and resume semantics

The offline `fare batch coverage` command uses the complete requested
provider × route × date matrix as the denominator. It distinguishes
`not_searched`, `zero_returned`, `filtered_all`, `eligible`,
`provider_unconfigured`, `provider_failed`, `cache_only`, and
`legacy_unknown`. It uses local audit entries, not inferred availability.
A provider's returned count is **not** global inventory coverage; the
provider may return a truncated result subset or not expose fare brands.

`fare batch run --resume --confirm` skips a query only when an exact query
hash has a completed per-provider observation in the private journal. Missing
or malformed audit journals are not silently treated as complete. This is
**not** a proof of actual billed requests, and the original upper-bound
budget remains conservative; see #19 for durable per-provider billing.
Older journals without provider observations show `legacy_unknown` rather
than falsely displaying zero fares. Only synthetic tests belong in CI.


### Offer-aware price alerts (issue #20)

The one-shot watcher now computes a stable, non-reversible product identity
from the provider, route, dated segments, party, cabin, fare brand and policy
key. Changing fare brand or flight does not generate a false price drop on the
old fare. Component-cost assumptions are independently fingerprinted, and
changes to those assumptions are suppressed as airfare alerts. A conversion
change without a change in the fare's source-currency price is counted as
`suppressedFxOnlyChanges`, not described as a new airline discount. An
expired offer is not eligible for a fresh price alert.

A private NDJSON journal stores deterministic alert IDs, checked before
appending each new event. Corrupt journals fail closed rather than replaying
duplicate alerts. This is an **observation** system: it does not assert fare
availability or a refund to the original payment method, and it does not send
notifications. Some provider data still lacks a stable seller identifier,
so exact airline/OTA product reconciliation and verified refund alerts remain
separate work under issues #12, #13 and #20.
