# Read-only Sites integration: real cross-runtime test

The Hosted D1 storage safety tests previously exercised the SQL statements
through Python and Node separately. The Sites integration job now runs the
**actual compiled Kotlin/JVM sample fixture**, **actual Kotlin/JS production
bundle**, and **real SQLite database with the three D1 migrations** through
the complete authenticated Fetch request boundary.

Run from the repository root on JDK 25, Node 22 and Python 3:

```sh
./gradlew :fare-core:jsBrowserProductionWebpack
bash scripts/build-sites-preview.sh build/sites/clawfare-sample.html
node --experimental-sqlite sites-runtime/tests/real-sqlite-integration.mjs \
  build/sites/clawfare-sample.json \
  fare-core/build/kotlin-webpack/js/productionExecutable \
  src/main/resources/web/index.html
```

The end-to-end suite uses Node's experimental built-in `node:sqlite` only
as a **test adapter** exposing D1's prepared-statement methods. It applies the
exact production migration SQL to an in-memory SQLite database with foreign
keys on; there are no stubbed rows and no live provider requests. It:

- Begins with the D1 public fixture table **empty**, then imports the fixed
  Kotlin-generated sample only during the first authenticated GET, with
  atomic conflict-safe SQL and verified readback. Concurrent first requests
  cannot overwrite existing records; signed-out requests do not write.
- Loads compiled Kotlin/JS and calls shared query normalization, leap-day
  flight date planning, paid-call budgeting, and GBP conversion.
- Serves the real existing Kotlin HTML in explicit Sites read-only mode.
- Asserts a public sample is readable only through authenticated API access
  and never gets copied into user-private investigation rows.
- Persists two different tenants with the same investigation slug and checks
  scoped list, details, immutable price history, and cross-tenant isolation.
- Asserts a spoofed email header cannot bypass a failed trusted identity
  callback; GETs require sign-in and all API POSTs are denied.
- Verifies a missing paid-provider cap denies the attempt without creating a
  reservation and verifies that corrupting stored public fixture bytes fails
  closed before a sample response is returned.

The GitHub Actions **Sites read-only full-stack integration** workflow
performs all these checks and uploads only the public sample HTML/JSON.
Neither user documents nor API credentials enter the build artifact.

## Still required before a private ChatGPT Sites preview

The test's injected verified-email callback is intentionally controlled by
the test. A real ChatGPT Sites deployment must supply the trusted platform
identity and verify that caller-controlled headers are stripped, overwritten,
or cannot bypass sign-in. Never copy the test callback into a public worker.

ChatGPT Sites must provision the Site's project and D1 binding and apply
migrations 0001–0003 during a reviewed deployment. If a separate trusted
pre-deployment D1 seed operation is unavailable, bind the fixed Kotlin
`server/fixtures/clawfare-sample.json` as the `bundledPublicSample`
option on the server's Fetch handler. Its first authenticated sample GET
initializes exactly that record with no public import route. Configure the
stable `OWNER_KEY_SECRET` through Site Settings, not repository code. The Site's hosted runtime must load the HTML and
implement the platform's authenticated route ingress, then use
`createReadOnlySitesHandler`. Save a version for review; **do not deploy
or allow paid provider calls until the live identity and D1 integration
tests succeed**.

The Node `node:sqlite` API is not a production dependency and the test
does not exercise Cloudflare D1's native runtime or ChatGPT Sites sign-in
gate. See issue #42 for the remaining end-to-end hosting requirements.
