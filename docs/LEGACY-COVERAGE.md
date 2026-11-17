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

This is a measured starting point, **not completion of issue #23**.
Next: increase coverage of individual existing `Inv*`, `Flight*` and
`PriceCheck*` commands, then remove exemptions one at a time only after
proving the existing 80% gate still passes. No new classes may be exempted.
Unfiltered JVM coverage excludes independent Kotlin/JS and Wasm tests.

Run `./gradlew codeCheck` for the current enforced gate.

Keep the local web server bound to `127.0.0.1` unless explicit
authentication is introduced for remote access.
