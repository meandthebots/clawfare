# Kotlin Multiplatform / ChatGPT Sites deployment

## Status

The JVM CLI and Ktor web UI remain the production implementation. The \`:fare-core\` module is compiled from the **same Kotlin source** for JVM, JavaScript (Node), and WebAssembly/JavaScript (Node). It currently contains serializable search/flight DTOs, JSON configuration/redaction, carrier/cabin classification, and pure fare calculations. The JVM application depends on that module.

**This is not yet a deployed ChatGPT Site.** No hosted execution path or D1 persistence layer is claimed. Do not publish the existing localhost server or accept public search POSTs without authentication and quota enforcement.

## Build and test

\`\`\`sh
./gradlew compileKotlin test
./gradlew :fare-core:jvmTest :fare-core:jsNodeTest :fare-core:wasmJsNodeTest
./gradlew :fare-core:compileKotlinJs :fare-core:compileKotlinWasmJs
\`\`\`

The existing JVM CLI is unchanged:

\`\`\`sh
./gradlew run --args="serve --port 8080"
\`\`\`

Kotlin/JS and Kotlin/Wasm compile from \`commonMain\`; there is no alternative hand-written TypeScript implementation of these rules.

## Target architecture

- **\`fare-core/commonMain\`**: pure model, validation, currency, fare math, itinerary, query normalization/estimation as dependencies become multiplatform.
- **JVM application**: existing Ktor, JDBC/Exposed SQLite, CLI, provider HTTP clients, local cache, and filesystem import/export.
- **Hosted JS API** (future): HTTP request-handler adapter around the same shared policies, JavaScript \`fetch\` for upstream providers, D1 for persistent fare history and quota reservations, platform secret bindings, and authorization for mutating operations.
- **Browser Wasm** (optional): pure analysis, fare filters, matrix computation. The existing HTML UI is usable without Wasm. Wasm never gets provider secrets, authorization tokens, or authority to initiate charged upstream calls.

## Non-negotiable deployment invariants

1. **Provider credentials are server-only**. No provider secrets may be included in shared DTOs or WebAssembly/browser bundles.
2. **No paid provider requests without explicit consent**. A dry-run estimate and server-validated, short-lived confirmation must precede charged calls, including expanded return lookups and seller queries.
3. **Crash-safe quota accounting**. Reserve per-provider call budgets in durable storage before dispatch; on restart, never reclaim an ambiguous in-flight reservation as unused.
4. **Privacy and origin protections**. Existing \`LocalAccess\` only secures loopback. Hosted write endpoints require real authentication and anti-CSRF protections; publishing \`/api/session\` unchanged is unsafe.
5. **Preserve provenance**. Each saved fare must keep provider, live/cached/mock classification, timestamp, original currency, and warnings. Cached and mock prices must never be described as live.
6. **Parity tests**. Reuse Kotlin regression fixtures, compare serialized JSON with JVM snapshots, and exercise the deployed API (including failure and budget-exhaustion cases) before routing production traffic.
7. **Persistent storage**. A local SQLite file is not durable on a stateless hosted runtime. D1 schema must preserve existing search history, observations, seller offers, and quota ledger semantics.

## Remaining integration steps

- Extract the dependency-injected, pure portions of \`Itineraries.kt\`, \`Money.kt\`, and \`SearchService.kt\`; keep \`java.time\`, \`java.net.http\`, and Exposed/JDBC in platform adapters.
- Add Kotlin/JS hosted entrypoint and a typed bridge to the platform's Request/Response, D1 bindings, and secrets. Verify generated Kotlin/JS output runs in the actual Sites runtime; standard Node APIs are not presumed available.
- Implement durable D1 migrations, atomic call reservations, persistent cache and fare store, and authenticated mutation routes.
- Start with a private sample-data Site, add live searching only after consent, quota and persistence integration tests pass.
- Evaluate browser Wasm only against a measured need; it is not required for a Sites deployment.

References: https://kotlinlang.org/docs/web-overview.html and https://kotlinlang.org/docs/wasm-overview.html.
