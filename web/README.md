# Clawfare browser frontend

React 19, TypeScript/TSX, StyleX, Vite 8, Oxlint and Oxfmt.

```sh
cd web
npm install
npm run dev
npm run check
npm run format
npm run build
```

The Vite production build is a **single HTML file**, intentionally. The JVM
server, offline HTML exports and the ChatGPT Sites source package all consume
`build/frontend/web/index.html`. The compiled JS/CSS is inlined; no CDN or
independent static-asset route is required. The export/snapshot marker and
read-only Sites flags remain unchanged.

The React app currently owns the shell, controls, and panel mount points.
`src/controller.ts` is the extracted, transitional DOM renderer for the
complex fare grid, investigations, vendor list, and search/API flow. It is
TypeScript source but has a temporary `@ts-nocheck` annotation; this migration
does **not** claim that those legacy code paths are already type-safe or
declarative React. Converting them to typed React components is the next phase.

Typed API/client boundaries live in `src/domain.ts` and `src/api.ts`. The
client supports exact same-origin routes, lazy local session authentication,
and an explicit hosted read-only estimate exception. Run `npm run test` for
network-boundary regressions. ResultSet validators intentionally check the
transport envelope; individual renderers must still handle unknowns and
nullable prices safely.
