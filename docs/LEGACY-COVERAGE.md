# Legacy CLI coverage during the fare-search port

The fare-search port integrates the provider web API with the existing investigation
database. New provider, persistence, web API, and fare-command code remain under the
Kover **80%** gate.

The pre-port `Inv*`, `Flight*`, `PriceCheck*`, and `Output` command classes have
historically had substantially less than 80% coverage, despite substantial
coverage in `CommandsTest.kt`. To avoid weakening the gate for **future**
classes, `build.gradle.kts` lists each existing legacy class **explicitly**
rather than excluding wildcard class families.

The review regression suite exercises the migrated integration through
`inv show`, `flight show`, and `flight history`; the pre-existing
`CommandsTest` still covers the other major commands. This is a
transition, **not a claim** that the old CLI reaches 80% coverage today.

## Unfiltered JVM coverage audit

The `./gradlew koverVerify` and `koverHtmlReport` tasks still enforce and
report the original **80%** JVM coverage gate, with only the explicit
pre-port command-class exceptions shown in `build.gradle.kts`.

For visibility into that excluded debt, the **unfiltered JVM report variant**
has its own named task and XML output. The standard total report retains
the original shared Kover exclusions; an explicit empty `variant("jvm")`
filter override produces a separate audit with the historically exempt CLI
classes, without changing the ordinary verification gate.

```sh
./gradlew test koverVerify koverHtmlReport koverXmlReport koverXmlReportJvm
python3 -m unittest discover -s scripts -p 'test_legacy_coverage_audit.py' -v
python3 scripts/audit_legacy_coverage.py \
  --unfiltered build/reports/kover/unfiltered-jvm.xml \
  --filtered build/reports/kover/report.xml \
  --markdown build/reports/kover/legacy-audit.md \
  --json build/reports/kover/legacy-audit.json
```

Both reports derive their counts from the **same instrumented JVM tests**.
The standalone Python audit verifies that at least one configured legacy CLI
exemption is visible in the unfiltered report, calculates separate **line
and branch** percentages for each class, and **explicitly reports any listed
exemptions still present in the enforced filtered XML**. Kover 0.9.1 already
reports several such class names with the original shared filter configuration;
the audit exposes this as a discrepancy rather than claiming that those
classes are absent from the gate. It flags missing classes explicitly, uses
zero-denominator `n/a` rather than inventing 100%, and never interprets
a class-level percentage as proof of a working end-to-end provider journey.
CI uploads the two XML files, deterministic Markdown summary and JSON
metrics as the `legacy-cli-unfiltered-coverage` artifact.

## Retired CLI exemptions (issue #23 follow-up)

The initial unfiltered audit from PR #104 identified two historical exceptions
that already exceeded **80% in both LINE and BRANCH coverage**:

| Command | Baseline lines | Baseline branches |
| --- | ---: | ---: |
| `FlightImportCommand` | 91.2% | 80.8% |
| `FlightPriceCommand` | 86.7% | 81.0% |

Their exact-class entries have been **removed** from Kover's exclusion list.
The exclusion count decreases from 35 to 33. Both are now present in the
ordinary gated XML and counted toward the existing aggregate 80% requirement.
The audit's `RETIRED_CLI_EXEMPTIONS` list additionally enforces a per-class
minimum of **80% LINE and 80% BRANCH** for each retired command and fails if
either class is missing from the gated report, relisted as excluded, or falls
below either threshold. Percentage comparisons use integer counts to avoid
rounding a 79.99% result up to 80%.

When another CLI exception becomes sufficiently covered, remove it from the
Kover exclusions **and** append it to `RETIRED_CLI_EXEMPTIONS`; CI will enforce
the resulting ratchet. These percentages come from synthetic JVM unit tests,
not live-provider API calls.

## Legacy CLI database-import regression suite

`FlightImportDbRegressionTest` exercises `flight import-db` against **two
disposable SQLite databases**. Its synthetic source deliberately resembles an
older export: optional columns such as `flight_source` and price
`source_url` may be absent. The tests cover:

- `--dry-run` and an unmatched `--investigation` filter leaving the target
  unchanged, and missing-source rejection before creating records.
- Selective investigation import with complete flight and **historical price**
  preservation, including EUR amount/currency, market, source and timestamp.
- Re-imports that deduplicate existing price timestamps but add newly observed
  prices, without duplicating flights or investigations.
- `--skip-prices` on new and previously imported flights, a first-import
  flight-price fallback when the source has no price-history rows, and both
  legacy and renamed flight-source column forms.
- Source URLs that fall back to the legacy booking link **only when** a
  per-observation URL is missing.

Run the isolated suite with:

```sh
./gradlew test --tests "com.clawfare.cli.FlightImportDbRegressionTest"
```

No provider endpoints are contacted; every booking URL is under
`example.invalid`. This improves legacy CLI coverage but does not yet verify
transactional rollback for a partially failing `import-db` command, which
remains follow-up work under #23.

This is a measured starting point, **not completion of issue #23**.
Next: increase coverage of individual existing `Inv*`, `Flight*` and
`PriceCheck*` commands, then remove exemptions one at a time only after
proving the existing 80% gate still passes. No new classes may be exempted.
Unfiltered JVM coverage excludes independent Kotlin/JS and Wasm tests.

Run `./gradlew codeCheck` for the current enforced gate.

Keep the local web server bound to `127.0.0.1` unless explicit
authentication is introduced for remote access.
