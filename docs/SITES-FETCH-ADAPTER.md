# Read-only Sites Fetch integration

`sites-runtime/src/site-handler.mjs` exports `createReadOnlySitesHandler`, combining the real Kotlin web UI template with the authenticated, tenant-scoped D1 GET API. It serves GET/HEAD root HTML with no-store and privacy headers, delegates `/api/*` to the trusted read-only backend, and returns `null` for native Sites sign-in/sign-out and other non-API routes to allow the hosting platform to handle them.

This is **not** a public Worker entrypoint or an already deployed Site. A compatible Sites starter must supply an actual D1 binding, the verified identity callback from Sites ingress, a stable server-only secret of at least 32 bytes, and the Kotlin HTML asset; it must also handle `null` with the Sites native sign-in route, static asset response or 404. Do not implement the identity callback using client-provided headers on a public Worker that can bypass Sites ingress.

Node integration tests use real Request/Response objects and the Kotlin HTML template. No paid providers are accessed; live-search/POST paths remain disabled until a separate authenticated, consent-bound and quota-reserved implementation is ready.
