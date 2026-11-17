# Clawfare HTTP contract

The authoritative source is [api/openapi.json](../api/openapi.json), an OpenAPI
3.1 JSON document for the Ktor JVM server, React browser, and hosted Sites.

Regenerate and check the generated interfaces and browser client:

    cd web && npm run api:generate && npm run api:check && npm run check

The dependency-free generator at [scripts/generate-openapi.mjs](../scripts/generate-openapi.mjs)
emits the following (all ignored by Git; edit the schema instead):

- web/src/generated/openapi-types.ts: browser DTOs for itineraries, search,
  refund evidence, sellers, and the remaining HTTP payloads.
- web/src/generated/openapi-client.ts: typed API methods with encoded path
  parameters, executed through the existing session/consent-aware transport.
- build/generated/openapi/kotlin/com/clawfare/web/generated/ClawfareApiRoutes.kt:
  typed Kotlin route descriptors plus a Ktor route registration interface and
  its adapter. The JVM application implements the endpoint handlers.

Gradle runs code generation before Kotlin compilation; npm does so before
browser typechecking, tests, and the Vite bundle. Both are exercised in CI.

## Change procedure

1. Edit paths and schemas in api/openapi.json, including x-runtime,
   x-kotlin-request, and x-kotlin-response for each operation.
2. Regenerate. Use the generated GetEndpoints/PostEndpoints for Ktor handlers
   and createGeneratedClient for the browser. Do not duplicate DTOs or paths.
3. Add/update tests for JVM, browser and hosted adapters, including invalid
   authentication and response-shape regressions.
4. Run web npm check/build, Gradle tests, and the hosted runtime test suite.

## Security and implementation boundaries

The schema is a wire contract, not an authorization or trust boundary. Hosted
Sites must obtain identity from trusted platform ingress, never arbitrary
request headers. Only informational, zero-cost POST /api/estimate may be allowed
on hosted Sites. All paid calls, other mutations and exports stay disabled.

Local writes require a loopback session. Live provider calls additionally
require explicit matching consent. JSON stays untrusted until the browser's
structural guards accept it; generated static types do not replace runtime
validation. HTML/CSV exports retain a separate guarded Blob download path.

Local GET /api/searches returns full ResultSet records; hosted Sites returns
smaller SavedInvestigation projections. Both satisfy the documented
SavedInvestigation array shape. See each operation's x-runtime field for route
availability.
