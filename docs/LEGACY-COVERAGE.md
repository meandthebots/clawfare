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

Next coverage step: obtain the *unfiltered* line/branch coverage report for
the old CLI; record a measured baseline; add tests to increase that baseline,
then remove each explicitly listed class from the exclusions once it meets
the main gate. Do not expand the excluded class list for future commands.
Run `./gradlew codeCheck` for the current enforced gate.

Keep the local web server bound to `127.0.0.1` unless explicit
authentication is introduced for remote access.
