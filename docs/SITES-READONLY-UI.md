# Existing Clawfare UI in hosted read-only mode

The original JVM Ktor UI always requires a loopback-only session token. An authenticated ChatGPT Site must instead use the trusted identity gateway. The existing HTML supports an explicit `window.__CLAWFARE_HOSTED_READ_ONLY__` flag injected only by `sites-runtime/src/hosted-page.mjs` into the exact Kotlin HTML template.

Hosted mode skips the localhost-only `/api/session` token exchange and removes `X-Clawfare-Session` from GETs. It retains saved-investigation browsing and local filtering but disables estimate, paid search and export controls and rejects POSTs through its client helper. The JVM localhost server remains unchanged by default.

The flag is **display/client behavior only**. Server-side D1 GET endpoints still enforce identity and tenant scope; mutation endpoints continue returning HTTP 405. The host must never infer this mode from a hostname, and it must not treat the client flag as authentication or authorization.
