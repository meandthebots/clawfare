# Hosted React search confirmation (issue #124)

## Capability and consent flow

The hosted browser's existing "Estimate calls" button can produce a signed
SerpApi quote **only** when the authenticated Worker reports
\`hostedSearch.canExecuteOneWayGbpSerpApi: true\` in GET \`/api/status\`.

This field is derived exclusively from trusted server environment flags, the
presence of the provider key and signing secret, and a transactional D1 binding.
It is never inferred from a URL parameter, cookie, provider card, cached
fixture, or browser-managed flag. Existing read-only and guest modes remain
unchanged, and a normal client \`request("/api/search",...)\` still fails closed.

1. Parse the full search policy from the form and request a zero-provider-cost
   estimate. The browser does not acquire a signed token from this estimate.
2. If server capability permits, send the query to authenticated
   \`POST /api/prepare-search\`. Display the server's actual one-way GBP SerpApi
   upper bound; hold the **exact returned canonicalQuery** and signed consent
   token in transient React state (not localStorage).
3. Editing the form or re-estimating invalidates the quote. A stale async
   response cannot restore an invalidated approval.
4. "Confirm and run search" displays the maximum provider request count,
   explicitly warns of possible charges/unknown remote quota and asks for a
   separate affirmative user confirmation. Cancel leaves the quote unused.
5. Require unexpired approval and synchronously lock execution before starting
   network. Drop the token immediately after confirmation to prevent duplicate
   clicks or automatic billable retries.
6. The client rechecks authenticated \`/api/status\` capability before
   \`POST /api/search\`. The backend independently validates signed owner/query
   consent, one-use D1 state, actual local quota and two independent execution
   switches before any provider HTTP.
7. Render the **complete** Kotlin ResultSet and refresh saved investigations
   only when the backend returns a structurally valid result. Existing sample
   rows are replaced rather than merged. Saved results persist in D1 and can
   be reopened later. If response status is uncertain, advise checking saved
   investigations rather than retrying a potentially billed search.

The initial executable backend intentionally only supports **SerpApi one-way
GBP**, \`fetchReturns=false\`, and \`maxLiveCalls\` 1–16. The UI clears the
return selection when users choose one-way travel. Other provider, round-trip,
non-GBP, booked seller options and cancellation/refund guarantees remain out of
scope. Users should not treat observed fares as proof of best global pricing.

## Release

This PR ships **no changes to the deployed private Site**, credentials or
D1 quota settings. Even when code is deployed, both server-only execution
flags \`SEARCH_EXECUTION_ENABLED\` and \`SEARCH_PAID_CALLS_APPROVED\` default OFF.
Actual paid provider trials require separate explicit owner authorization.
CI uses only local synthetic provider responses; this is not a substitute
for verifying production ChatGPT/Sites ingress ownership and D1 isolation.

Tests cover server capability OFF, valid exact query/nonce handling in the
typed client, no JVM token or identity-header leakage, malformed quote
rejection, blocked generic hosted mutations, and revocation before POST.
