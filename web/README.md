# Clawfare browser frontend

React 19, TypeScript/TSX, StyleX, Vite 8, Oxlint and Oxfmt.

```sh
cd web
npm install
npm run dev
npm run check
npm run format:check
npm run format  # explicitly reformat source
npm run build
```

The Vite production build is a **single HTML file**, intentionally. The JVM
server, offline HTML exports and the ChatGPT Sites source package all consume
`build/frontend/web/index.html`. The compiled JS/CSS is inlined by
`scripts/inline-web-bundle.mjs` **after** StyleX emits its atomic CSS.
Inlining inside Vite's `generateBundle` phase can omit late StyleX rules.
No CDN or independent static-asset route is required. The export/snapshot
marker and read-only Sites flags remain unchanged.

The React app owns search and filter controls, result tabs, flight and add-on
grids, itinerary and vendor tables, investigation state, saved-search retrieval,
search estimates, local exports, and the detail drawer. `src/App.tsx` is the
stateful entry point; typed transport contracts and the same-origin API client
live in `src/domain.ts` and `src/api.ts`. Filter and query mapping live in
`src/filters.ts`, `src/filterForm.ts`, and `src/searchForm.ts`.
The old imperative DOM controller and its `@ts-nocheck` suppression are gone.

The UI defaults to dark mode on first paint. The header toggle saves an explicit
light/dark preference in `localStorage` under `clawfare-theme`; an early HTML
script restores it before the single-file bundle loads. Theme colours are
centralised as CSS custom properties in `src/legacy.css` and consumed by StyleX
where needed. This is a user preference, independent of OS appearance.

The UI uses StyleX atomic classes. Some pre-existing complex fare-table,
timeline, and mobile interaction selectors remain in `src/legacy.css`; these
are compatibility styles under incremental migration, not a second runtime
controller. Keep React as the only owner of result and control DOM state.

The client supports exact same-origin routes, lazy local session
authentication, and an explicit hosted read-only estimate exception.
`npm run check` enforces TypeScript, **whole-tree Oxlint**, unit tests and
**whole-tree Oxfmt formatting**. Kotlin, D1 and Sites tests still run through
the repository workflows. The compiled browser bundle is static/offline-safe;
hosted identity and all paid-provider authorizations are enforced server-side.
ResultSet validators check the transport envelope; component renderers must
still handle nullable prices and unknown refund evidence conservatively.

## Credential-free guest mode (issue #89)

Open `http://127.0.0.1:5173/?guest=1` after `npm run api:generate && npm run dev`
(or open the self-contained `build/frontend/web/index.html?guest=1` after
`npm run build`). No ChatGPT or Google sign-in, local JVM server, API keys,
D1 database or network connection is required to use the guest UI.

Guest mode renders a **bundled, deliberately synthetic** Florida/Mexico City
fixture with grid cells in all three feasibility states, itineraries, vendor
rows and details. Filters, sorting, tabs, the details panel, theme selection
and notes can be exercised. It provides one built-in investigation in the
saved-investigations control, but no user data or personal history.

The guest client never calls `fetch` (including `/api/session`), never
reads D1 and rejects all API mutations, estimates, tracking, paid search and
exports. The sample contains no booking URLs or live quotes. Vendor notes and
theme preference may be stored in this browser's localStorage, as in the
normal UI; **no data goes to the server**. To test Kotlin-backed estimates or
D1 persistence, use the separately authenticated hosted environment.

The query parameter **does not bypass a hosting platform's authentication**:
an owner-only ChatGPT Site continues to require ChatGPT sign-in at ingress.
For an anonymous test URL, deploy the self-contained static HTML to a
separate public static host; do not change the existing Site's access
controls. The sample is bundled client-side, so never embed private trips in
the guest fixture.

Verification: `npm run check && npm run build`, plus
`node sites-runtime/tests/guest-browser.mjs build/frontend/web/index.html`
from the repository root after installing Playwright in the mobile-grid CI
environment. The browser test aborts and fails on **any** request other than
the static HTML document.
