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
  projection and FX. The hosting build may need to bundle this CommonJS module
  for its ESM Worker runtime; do not replace it with handwritten fare math.
- `server/fixtures/clawfare-sample.json` — the canonical Kotlin-generated
  sample ResultSet, approved for public demonstration, **not** private user data.
- `server/migrations/*.sql` — D1 schema for disabled-by-default paid provider
  caps, user-private investigations/price history, and the public sample.
- `manifest.json` — deterministic names, SHA-256 checksums and sizes of every
  packaged file.

## Integration required in ChatGPT Sites / Work

1. Create a new **private** Site from the package. Do not invent or commit
   `.openai/hosting.json` or `project_id`; let Sites provision them.
2. Create the Site's `DB` D1 binding. Apply migrations `0001`, `0002`,
   and `0003` in order; verify foreign keys and table creation on the
   hosted binding. These are **Sites D1 data**, not your personal Cloudflare
   account's D1 database.
3. Configure a stable, randomly generated server-only `OWNER_KEY_SECRET`
   of at least 32 bytes through Sites Settings. Keep the value out of prompts,
   Git history, static assets, and the browser. Do not rotate it without
   migrating pseudonymous owner keys.
4. Create a **trusted Sites gateway integration** that invokes
   `createReadOnlySitesHandler({ templateHtml, db: env.DB, getVerifiedEmail,
   ownerKeySecret: env.OWNER_KEY_SECRET, cryptoApi: globalThis.crypto })`.
   The `getVerifiedEmail` function must use an identity attested by Sites
   ingress. Do **not** trust an arbitrary user-provided
   `oai-authenticated-user-email` header on a publicly reachable Worker.
   Before enabling site access, verify that Sites sign-in controls the header
   and that visitors cannot spoof the identity or bypass the gate.
5. In a trusted setup/migration step only, import
   `server/fixtures/clawfare-sample.json` using `bootstrapPublicSample`
   after running the migrations. Do not expose an HTTP import endpoint.
6. Serve the packaged `public/index.html` and route all `/api/*` requests
   through the authenticated Fetch handler. Route native
   `/signin-with-chatgpt` and `/signout-with-chatgpt` to Sites.
   Deny all unsupported requests by default.
7. **Save a version for review without deploying**. Check its build logs,
   code compatibility, D1 schema, sample data integrity, signed-in sample
   display, grid/filter interactions, and user-private saved searches.
8. Only after tests pass, deploy privately and confirm sharing is restricted
   to the owner (and any workspace admins). Do not enable paid search.

## Mandatory checks before accepting the site

- A signed-out visitor cannot load `/api/sample`, `/api/searches`,
  `/api/results/{id}` or private price history.
- A user cannot forge a signed-in identity by supplying request headers.
- Two distinct signed-in users with the same investigation slug cannot see
  each other's records.
- `POST /api/search`, `POST /api/estimate`, vendor/track mutations and
  HTML/CSV POST exports return HTTP 405. This is **server-enforced**, not
  merely hidden buttons.
- No provider HTTP requests, credentials or order/payment requests are
  reachable in this build; provider caps remain zero/unconfigured.
- Kotlin sample prices are explicitly labelled **sample/saved**, not live.
- Migration/seed retries are safe and do not duplicate the fixture.
- On Android and desktop, open the sample, apply filters, switch grid/table
  views and inspect details. Saved searches persist across reloads.
- Verify a generated saved version against its Git commit and inspect D1
  data before promoting it. Site version rollback does **not** roll back D1.

## Local proof available before Sites provisioning

From the source repository, with JDK 25 and Node.js 22:

```sh
./gradlew :fare-core:jsBrowserProductionWebpack
bash scripts/build-sites-preview.sh build/sites/clawfare-sample.html
node --experimental-sqlite sites-runtime/tests/real-sqlite-integration.mjs \
  build/sites/clawfare-sample.json \
  fare-core/build/kotlin-webpack/js/productionExecutable \
  src/main/resources/web/index.html
node scripts/package-sites-readonly.mjs build/sites-readonly-package
node scripts/verify-sites-readonly-package.mjs build/sites-readonly-package
```

The integration tests use real SQLite (not D1 native hosting) and injected
test identity, and therefore cannot certify the actual Sites gateway. The
GitHub CI preview/source artifact is **not a deployed URL**.

## Suggested ChatGPT Work / @Sites request

> Create a private ChatGPT Site from the current
> `meandthebots/clawfare` repository using the
> `clawfare-sites-readonly-source` build artifact and its
> `SITES-HANDOFF.md`. Confirm Sites runtime compatibility before saving.
> Provision D1 binding `DB`, apply the three migrations, seed the canonical
> Kotlin public sample from a trusted setup action, and implement a
> server-verified Sign in with ChatGPT identity callback.
> Retain the original HTML UI and Kotlin/JS engine. Keep live search,
> estimates that could spend quota, and all POST operations disabled.
> Run signed-in, signed-out, identity spoofing, tenant-isolation and mobile
> browser tests. **Save a version only; do not deploy or publish it.**

See repository issue #42 for the follow-on authenticated estimate/consent and
real provider-search work. Production publishing is a separate decision.
