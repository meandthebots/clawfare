# Clawfare — ChatGPT Sites private-preview source handoff

This directory is intentionally a **source handoff package**, not a live
deployment. It contains everything needed to adapt the existing Clawfare
**read-only** browser UI and backend to a compatible ChatGPT Sites runtime.
It contains **no provider credentials**, private trip searches, or fabricated
Sites project ID.

## Package contents

- `public/index.html` — the existing Kotlin/JVM web UI with the explicit
  hosted-read-only mode enabled. It talks to `/api/sample` and `/api/searches`
  without attempting localhost `/api/session`.
- `server/src/*.mjs` — Fetch API wrapper, identity-bound read-only routes,
  tenant-isolated investigation/price-history store, public sample bootstrap,
  provider HTTP quota safety module, and compiled Kotlin/JS loader.
- `server/kotlin/fare-core.cjs` — **actual Kotlin/JS** production bundle,
  exposing normalized queries, search-date planning, provider call-budget
  projection and FX. Do not replace it with handwritten fare math.
- `server/src/search-estimate.mjs` — authenticated, input-bounded,
  **no-cost** Kotlin/JS request planning and provider-call estimate. This
  permits only `POST /api/estimate`, performs no provider HTTP requests,
  does not persist searches, reserve quota or issue approval tokens. All
  counts describe hypothetical network calls **if providers are enabled in
  a future reviewed release**, not presently configured/charged calls.
- `server/worker.mjs` — a self-contained **ESM Worker factory**, bundled
  with a pinned esbuild release. It embeds the original HTML and fixed Kotlin
  public fixture and loads the genuine Kotlin/JS bridge. The host imports
  `createTrustedWorker({ getVerifiedEmail })` and delegates Fetch requests
  to that returned object. There is **deliberately no default public fetch
  export**, no implicit identity header trust and no fabricated Sites runtime
  configuration. `server/worker-source.mjs` is the readable assembly input.
- `server/fixtures/clawfare-sample.json` — the canonical Kotlin-generated
  sample ResultSet, approved for public demonstration, **not** private user data.
- `server/migrations/*.sql` — D1 schema for disabled-by-default paid provider
  caps, user-private investigations/price history, and the public sample.
- `manifest.json` — deterministic names, SHA-256 checksums and sizes of every
  packaged file.

The signed-in **Check storage** control requests `GET /api/storage`. The
response reads the actual D1 `public_sample_fixtures` row, validates its
content digest and returns `sampleStored`, `firstImportedAt` and SHA-256.
It never writes to D1, queries user investigations or reveals credentials.
After loading the sample, compare `firstImportedAt` before/after page reload
and a new browser session. An unchanged timestamp is evidence that the
public sample row persisted in the configured D1 binding. This check does
**not** prove that private investigations persist or that account isolation
is enforced; those need separate owner-approved hosted tests.

## Integration required in ChatGPT Sites / Work

1. Create a new **private** Site from the package. Do not invent or commit
   `.openai/hosting.json` or `project_id`; let Sites provision them.
2. Create the Site's `DB` D1 binding. Apply the packaged D1 migrations in order through `0006`; verify foreign keys and table creation on the
   hosted binding. These are **Sites D1 data**, not your personal Cloudflare
   account's D1 database.
3. Configure a stable, randomly generated server-only `OWNER_KEY_SECRET`
   of at least 32 bytes through Sites Settings. Keep the value out of prompts,
   Git history, static assets, and the browser. Do not rotate it without
   migrating pseudonymous owner keys.
4. Implement the **trusted Sites gateway integration** around the
   prepared `server/worker.mjs`. It exports `createTrustedWorker({
   getVerifiedEmail })` and **does not export a ready-to-publish default
   Worker handler**. Provide the platform's verified identity callback and
   only then attach the returned `fetch(request, env, context)` handler to
   Sites ingress, with `env.DB` and `env.OWNER_KEY_SECRET` bindings.
   The `getVerifiedEmail` function must use an identity attested by Sites
   ingress. Do **not** trust an arbitrary user-provided
   `oai-authenticated-user-email` header on a publicly reachable Worker.
   Before enabling site access, verify that Sites sign-in controls the header
   and that visitors cannot spoof the identity or bypass the gate.
   If the installed Sites runtime cannot expose a trusted callback, **keep
   the Site undeployed and report the blocker**; never add a raw-header
   fallback.
5. Sites may apply D1 migrations **only when a version is deployed**.
   If there is no trusted pre-deployment D1 seed action, supply the canonical
   Kotlin `server/fixtures/clawfare-sample.json` to
   `createReadOnlySitesHandler` as the **server-only**
   `bundledPublicSample` option. On the first *authenticated*
   `GET /api/sample` after migration, the handler validates the fixed
   fixture and inserts it with `INSERT ... ON CONFLICT DO NOTHING`.
   It checks integrity on readback and never overwrites a previous version
   or repairs a corrupt record. It is never sourced from HTTP input.
   A trusted admin bootstrap using `bootstrapPublicSample` remains available
   if Sites exposes such an action, but is no longer required for the sample.
   Neither path exposes a public import route.
6. Serve the packaged `public/index.html` and route all `/api/*` requests
   through the trusted `server/worker.mjs` Fetch factory. Route native
   `/signin-with-chatgpt` and `/signout-with-chatgpt` to Sites **before**
   its 404 fallback. Deny all unsupported requests by default. The Worker
   factory does not make outbound provider calls or expose public writes.
7. **Save a version for review without deploying**. Verify its build
   logs, bundled fixture, Kotlin UI, code compatibility, and disabled paid
   routes locally. Do not claim real hosted D1/auth/browser validation for an
   undeployed version; the hosting platform cannot yet serve the site.
8. After explicit owner approval, deploy **owner-only**, then verify the
   real Sites sign-in gate, native D1 schema/first-request sample seed, UI
   behavior and cross-tenant data access before expanding access. Publishing
   can mutate D1, and restoring old code does not restore the database.
   Do not enable paid search.

## Mandatory checks before accepting the site

- A signed-out visitor cannot load `/api/sample`, `/api/searches`,
  `/api/results/{id}` or private price history.
- A user cannot forge a signed-in identity by supplying request headers.
- Two distinct signed-in users with the same investigation slug cannot see
  each other's records.
- `POST /api/estimate` must accept same-origin, bounded JSON only, call the compiled Kotlin
  plan and budget exports, and return potential call counts with
  `canRun:false`. It never contacts paid providers or issues consent tokens.
- Saved HTML and CSV exports use `POST /api/results/{id}/export.{html|csv}` and
  require the signed-in owner and a same-origin request. They read only the
  selected stored fares and share a D1 limit of ten exports per owner per
  minute. Migration `0006` installs the rate-limit table.
- The JVM client-payload export routes and vendor calls return HTTP 405.
  Hosted provider search remains gated by explicit server configuration.
- No provider HTTP requests, credentials or order/payment requests are
  reachable in this build; provider caps remain zero/unconfigured.
- Kotlin sample prices are explicitly labelled **sample/saved**, not live.
- Migration/seed retries are safe and do not duplicate the fixture. The
  first authenticated sample GET is permitted to perform only the fixed,
  idempotent public-fixture initialization; corrupt or mismatched existing
  records must fail closed and require explicit operator migration.
- On Android and desktop, open the sample, apply filters, switch grid/table
  views and inspect details. Saved searches persist across reloads.
- Verify a generated saved version against its Git commit and inspect D1
  data before promoting it. Site version rollback does **not** roll back D1.

## Known status of owner-only version 1 (16 November 2026)

The Work session saved version 1 without deploying. The source/hashes and
offline SQLite simulation passed, but Sites did **not** expose pre-deploy D1
provisioning/seed actions. The actual sign-in forwarding, spoof resistance,
hosted D1 persistence, cross-user isolation and browser flows remain
unverified. Do not mark version 1 deployment-ready merely because tests
passed locally.

Use a newer Git commit/source artifact that includes the automatic
server-side sample bootstrap, save version 2 for review, and keep it
undeployed until the owner explicitly approves a restricted deployment.
Only the hosted version can validate the real platform gateway and D1.

## Local proof available before Sites provisioning

From the source repository, with JDK 25 and Node.js 22:

```sh
./gradlew :fare-core:jsBrowserProductionWebpack
bash scripts/build-sites-preview.sh build/sites/clawfare-sample.html
node --experimental-sqlite sites-runtime/tests/real-sqlite-integration.mjs \
  build/sites/clawfare-sample.json \
  fare-core/build/kotlin-webpack/js/productionExecutable \
  build/frontend/web/index.html
node scripts/package-sites-readonly.mjs build/sites-readonly-package
node scripts/verify-sites-readonly-package.mjs build/sites-readonly-package
```

The integration tests use real SQLite (not D1 native hosting) and injected
test identity, and therefore cannot certify the actual Sites gateway. The
GitHub CI preview/source artifact is **not a deployed URL**.

## Existing version 1 and Work environment limitation (16 November 2026)

Version 1 remains **owner-only and undeployed**. The subsequent Work attempt
could not update or save version 2 because its Site execution environment was
unavailable. That is a Work runtime/access blocker, **not** a Kotlin build
failure. The source package now includes a verified bundled ESM Worker
factory so that Work does not need to assemble Kotlin/JS, UI, fixture or D1
adapters from scratch. It still must attach a trusted Sites identity callback
and provision/verify live D1 during an explicitly authorized owner-only
deployment. Continue in the **existing Clawfare Site**, not a new project.

Do not claim that version 2 was saved until the Sites editor confirms it.
Avoid repeated creation of new Sites to work around missing access.

## Suggested ChatGPT Work / @Sites request

> Create a private ChatGPT Site from the current
> `meandthebots/clawfare` repository using the
> `clawfare-sites-readonly-source` build artifact and its
> `SITES-HANDOFF.md`. Confirm Sites runtime compatibility before saving.
> Provision D1 binding `DB`, apply all packaged migrations, seed the canonical
> Kotlin public sample from a trusted setup action, and implement a
> server-verified Sign in with ChatGPT identity callback.
> Retain the original HTML UI and Kotlin/JS engine. Use the authenticated,
> zero-egress estimate and saved export routes. Keep paid provider execution
> disabled during preview review.
> Run signed-in, signed-out, identity spoofing, tenant-isolation and mobile
> browser tests. **Save a version only; do not deploy or publish it.**

See repository issue #42 for the follow-on authenticated estimate/consent and
real provider-search work. Production publishing is a separate decision.

## Release verification

Use the artifact from the exact release commit. The package manifest records
the source Git SHA, Kotlin/JS and OpenAPI SHA-256 digests, and the hashes of the
ordered D1 migrations. Package generation checks the generated OpenAPI
bindings. A dirty build records that fact alongside its SHA.

After an approved private update, use **Check storage** to compare the
authenticated `/api/storage` response with the package manifest. Verify the
deployed source SHA and Kotlin digest match. The D1 check reports required
tables present or missing. Verify migration application history separately for
changes to existing tables and indexes. Reload and compare the sample row
timestamp and digest. Verify isolation with the actual Sites identity boundary
as described in #63.

Before requesting approval for a paid smoke test, verify current provider
account terms and cost, keep server credentials in Site secrets, document
the query and cap its provider requests in D1, and agree on how to turn off
`SEARCH_EXECUTION_ENABLED` and `SEARCH_PAID_CALLS_APPROVED`. Keep the
previous private package available for rollback and keep D1 migrations
forward-only. The browser and simulated D1 suites use synthetic provider
responses. Production deployment and paid calls require separate owner approval.

## Hosted flight providers

Each hosted search selects one provider. Quotes reserve separate D1 allowances
for each configured provider, and the signed approval lists the planned
request count. The runtime uses these server-side credentials.

| Provider | Site secret | Supported results |
| --- | --- | --- |
| SerpApi | `SERPAPI_API_KEY` | Google Flights one-way and round-trip fare observations |
| SearchAPI | `SEARCHAPI_API_KEY` | Google Flights one-way and round-trip fare observations |
| Travelpayouts | `TRAVELPAYOUTS_API_TOKEN` | Cached calendar leads for one adult. Exact cabin, segments, operating carrier and baggage are unverified. |
| Duffel | `DUFFEL_ACCESS_TOKEN` | Test offers with a test token. Live offers require a live token and `DUFFEL_LIVE_ENABLED=true`. Each route uses one offer-request call and one call to list the first page. |

Configure D1 caps for each provider you enable. All providers share the
existing preparation and execution switches. Duffel approvals are bound to
sandbox or live mode, and expired offers cannot be treated as current fares.
Provider credentials, booking and pagination tokens stay server-side.
