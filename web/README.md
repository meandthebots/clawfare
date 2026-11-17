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
`build/frontend/web/index.html`. The compiled JS/CSS is inlined; no CDN or
independent static-asset route is required. The export/snapshot marker and
read-only Sites flags remain unchanged.

The React app owns search and filter controls, result tabs, flight and add-on
grids, itinerary and vendor tables, investigation state, saved-search retrieval,
search estimates, local exports, and the detail drawer. `src/App.tsx` is the
stateful entry point; typed transport contracts and the same-origin API client
live in `src/domain.ts` and `src/api.ts`. Filter and query mapping live in
`src/filters.ts`, `src/filterForm.ts`, and `src/searchForm.ts`.
The old imperative DOM controller and its `@ts-nocheck` suppression are gone.

The UI defaults to dark mode on first paint. The header toggle saves an explicit\nlight/dark preference in `localStorage` under `clawfare-theme`; an early HTML\nscript restores it before the single-file bundle loads. Theme colours are\ncentralised as CSS custom properties in `src/legacy.css` and consumed by StyleX\nwhere needed. This is a user preference, independent of OS appearance.\n\nThe UI uses StyleX atomic classes. Some pre-existing complex fare-table,
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
