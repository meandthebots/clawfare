# Development Notes

## Build Commands

| Command | Time | Output | Use for |
|---------|------|--------|---------|
| `./gradlew compileKotlin` | ~5s | Just checks compilation | Quick syntax check |
| `./gradlew test` | ~10s | Runs tests | Verification |
| `./gradlew installDist` | ~8s | `build/install/clawfare/bin/clawfare` | Fast iteration (JVM) |
| `./gradlew nativeCompile` | ~2min | `build/native/nativeCompile/clawfare` | Distribution binary |

## Workflow
1. Make changes
2. `./gradlew installDist` → test immediately
3. Reply to user
4. `./gradlew nativeCompile &` in background for the distributable binary

## React frontend

The browser UI lives in `web/` (TypeScript/TSX, React, StyleX, Vite).
Run `cd web && npm install && npm run dev` to work on the UI; the dev server
proxies `/api` to a local Clawfare server at port 8787 (configure it separately).
Run `npm run check`, `npm run format`, or `npm run build` in `web/`.
Gradle `processResources` automatically builds a *self-contained* HTML at
`build/frontend/web/index.html` and includes it as JVM resource
`web/index.html`. This is also the HTML bundled into private Sites and
portable report exports. No external JS/CSS URLs or third-party CDNs are required.

All browser UI state now lives in typed React controls and hooks; the former
`web/src/controller.ts` has been removed. React owns the desktop/mobile grid,
itinerary/matrix/vendor tables, summaries, and details. The same-origin
`web/src/api.ts` client distinguishes JVM loopback sessions from Sites' trusted
identity gateway, blocks paid/POST routes when hosted, and permits only the
explicit zero-cost estimate exception. `npm run check` includes TypeScript,
whole-tree Oxlint, tests and Oxfmt checks. Run `npm run format` before pushing
source changes. A shrinking `web/src/legacy.css` still supports complex table,
timeline and responsive selectors alongside compiled StyleX styling.
Do not deploy updates to the existing private Site merely by merging a UI PR.
