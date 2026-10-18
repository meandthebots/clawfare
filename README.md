# clawfare

Flight price investigation tracker. Investigations, flights, price history, and seller offers live in one SQLite database (`~/.clawfare/data.db`, or `CLAWFARE_DB`).

## Setup

```bash
# Use the project's git hooks
git config core.hooksPath .githooks

# Build
./gradlew build
```

Provider keys are read from the environment (see `.env.example`). They stay on the server: they are not logged, not written into `provider_cache`, and not sent to the browser.

## Fare search

```bash
# How many live calls a search would spend
./gradlew run --args="fare estimate --origin LHR,LGW --dest TPA --depart 2026-12-18:2026-12-21 --nights 8 --bags 1"

# Fetch into an investigation. Without --confirm, a search that needs a live call exits 2 and does not hit the network.
./gradlew run --args="fare fetch tampa-dec --origin LHR --dest TPA --depart 2026-12-19 --return 2026-12-27 --confirm"

# Parse cached responses again, with no provider calls
./gradlew run --args="fare reingest tampa-dec"

# Remaining quota. This hits account endpoints, not a flight search.
./gradlew run --args="fare quota"

# Florida / Mexico sample (26 long-haul, 8 Mexico add-ons, 165 seller rows). No API calls.
./gradlew run --args="fare load-sample"
```

SerpApi and SearchAPI are Google Flights, including `departure_token` returns and `booking_token` seller offers. Travelpayouts rows are cached calendar fares. Duffel runs in test mode and every offer is labelled mock, never a real price. A re-check appends `price_history`.

## Web UI

```bash
./gradlew run --args="serve --port 8080"
```

`serve` seeds the Florida sample if it is not already stored, then reads everything from the database: investigation list, sortable itinerary grid, outbound × return matrix, detail drawer, and the seller comparison (round trip beside two one-ways, bag-inclusive all-in GBP). Filters run in the browser: max stops, max layover, excluded dates, airline and operator, departure window, nonstop, max travel time. Each option has a Google Flights itinerary link and seller links. Prices are GBP.

## Exports

```bash
./gradlew run --args="report json florida-dec-2026"
./gradlew run --args="report top florida-dec-2026 --limit 10"
./gradlew run --args="report html florida-dec-2026 --out florida.html"
./gradlew run --args="report csv florida-dec-2026 --out florida.csv --vendors"
```

`report html` is a self-contained snapshot. `report csv` follows the same filters as the grid (`--max-stops`, `--nonstop`, `--vendors`). `inv` and `flight` commands are unchanged.

## Development

```bash
# Run lint
./gradlew ktlintCheck

# Auto-fix lint issues
./gradlew ktlintFormat

# Run tests with coverage
./gradlew test koverVerify

# View coverage report
./gradlew koverHtmlReport
open build/reports/kover/html/index.html

# All checks (what CI runs)
./gradlew codeCheck
```

## Requirements

- JDK 22+ (CI uses 25)
- 80% test coverage minimum
- ktlint must pass
