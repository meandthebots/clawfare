# Credential-free, offline guest preview (issue #89)

This is a standalone copy of the **actual compiled React/TypeScript/StyleX UI**,
with a synthetic flight investigation and an in-memory API simulator. It can be
opened in a browser without ChatGPT login, Google credentials, a JVM server, D1,
or real airline-provider credentials.

## Build and use

```sh
cd web
npm ci
npm run guest:build
# Open ../build/guest-preview.html directly in your browser.
```

The output is **`build/guest-preview.html`**; it is not
`build/frontend/web/index.html`. The latter remains the unchanged,
self-contained production HTML consumed by Ktor, exported reports and the
private Sites handoff. This command does **not deploy or publish anything**.

The yellow guest banner explicitly labels all flights, dates, sellers and
prices as fictional. The sample covers a direct flight, a connecting flight,
two add-ons, all three feasibility outcomes (works/catch/no), vendor notes,
filters, responsive details, search estimates, query confirmation, saved
investigations and CSV/HTML exports. All booking links are absent. Verified
cash-refund and live-price flags are false.

### State and safety

- **No login**: a fake session token exists only inside this offline page to
  exercise the same React/API-client code paths, not to authenticate anywhere.
- **Zero network egress**: the guest-only fetch implementation never delegates
  to browser/native fetch. A guest HTML Content Security Policy denies network
  connections, remote assets and form submissions.
- **Zero real searches or spending**: estimates return one **synthetic** cached
  result; paid provider calls, seller fetches, D1 writes, and bookings never run.
- **Ephemeral investigations**: simulated searches and single-use estimate
  approvals are kept only in that page's memory, and disappear on reload.
  Existing browser preferences such as theme and vendor notes may still use
  the browser's normal localStorage, just as in the production UI.
- **No access-control change**: the existing owner-only Sites worker, its
  identity gate, D1 tenant isolation, and the JVM Ktor session requirements
  remain intact. The guest file is separate from the private Site. This PR
  does not make the Site accessible to signed-out users.

The guest fixture is maintained in `scripts/guest-fixture.mjs`, the fetch
simulator in `scripts/guest-runtime.js`, and the offline HTML compiler in
`scripts/build-guest-preview.mjs`. The standalone Node test suite covers
synthetic-only data, denied network requests, estimate/confirm replay
protection, in-memory persistence, exports and self-contained output:

```sh
npm run guest:test
```

`npm run test` also runs that suite, so the frontend CI checks it.
A production build deliberately never includes the guest simulator: it is
injected only after compilation into a **different** output file.
