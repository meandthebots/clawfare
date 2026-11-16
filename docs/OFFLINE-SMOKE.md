# Hermetic cross-feature smoke test (#23)

A separate GitHub Actions `smoke` job runs
`OfflineEndToEndSmokeTest` with **all external flight-provider credentials
unset**. The test injects a synthetic HTTP provider which throws if a second
network call occurs, so fetching provider data cannot escape into a paid
external request. Prices, flight dates, route, carrier, fare and test
booking token are all synthetic and intentionally unrelated to a real
traveller.

The test exercises the complete in-memory workflow:

1. Plan a two-adult business-class LHR→HND synthetic journey and verify an
   unconfirmed search produces only an estimate and **zero HTTP calls**.
2. Confirm a fake SerpApi read, parse into a live-labelled offer and persist
   the normalized itinerary in a temporary SQLite database.
3. Reopen the investigation and export HTML/CSV with no credential or opaque
   booking token leakage.
4. Replay provider payloads with `cacheOnly=true` into a distinct
   investigation without any additional network calls, marking cached
   fares **not bookable**.
5. Expand/plan/run a zero-network batch with an audit trail, confirm
   `cache_only` coverage, and compare whole-party GBP costs.
6. Invoke the real `fare batch report` command against the temporary
   SQLite investigation and check the resulting CSV.
7. Run one explicitly confirmed cache-only watch cycle; verify no fake
   prices are promoted into fresh price alerts.
8. Open saved investigation and sample snapshots through the actual Ktor
   application test host (no external sockets).

The regular CI check still enforces the current Kover 80% threshold. It
also produces the HTML coverage report artifact; a **separate truly
unfiltered legacy coverage report** remains outstanding for issue #23.
No credential, provider response from real travel, or personally identifying
itinerary is committed or uploaded to CI.

Run locally:

```sh
./gradlew test --tests 'com.clawfare.OfflineEndToEndSmokeTest'
./gradlew test koverVerify koverHtmlReport
```

Further work: full provider schema fixture corpus for SearchAPI/Duffel/
Travelpayouts; browser-driver interaction tests; CLI command class coverage
for legacy import, validation and rollback, separate unfiltered branch
coverage, migration failure/backup/restore tests, and reviewer-facing
reconciliation artifacts. See issue #23.
