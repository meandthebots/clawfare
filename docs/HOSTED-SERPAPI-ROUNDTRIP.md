# Hosted SerpApi round-trip return selection — gated integration

This extends the *synthetic-tested* hosted SerpApi search path from one-way
to **GBP round trips with verified return selections**. It is stacked after
PRs #146–#149 and is not deployed or enabled in the private Site.

## Bound execution

The compiled Kotlin plan must have \`tripType=round_trip\`,
\`fetchReturns=true\`, a valid outbound/return date combination,
\`providers=["serpapi"]\`, an explicit 1–16 \`maxLiveCalls\` cap, and a
bounded 1–8 \`returnChoices\` limit. The signed HMAC consent enumerates both
\`base_search\` and \`return_selection\` upper bounds (base routes plus up to
\`returnChoices\` for each route). All requests are counted against the same
global D1 daily/monthly provider limits, atomically reserved **before** HTTP.

The SerpApi base request uses \`type=1\` and \`return_date\`. The hosted
transport only uses provider departure tokens internally for one authorized
\`return_selection\` request at a time. Tokens/URLs/credentials are not sent
to the browser, saved snapshot, price history, attempt ledger, or logs.

Each route's D1 \`attempted\` record is inserted before the base request and
closed only once with accurate base/return attempt counts, returned offers,
eligible verified round trips and completeness status. Any interrupted or
unavailable return selection is reported as incomplete, never zero global
availability or a bookable quoted round trip. No provider retry is automatic.

The same shared Kotlin/JS fare parser and \`GoogleFareDraft\` produce
\`assembleHostedRoundTripResult\` input. It verifies matched return legs and
requested dates, applies hard eligibility to both directions, uses the
**return-selection full-trip party price** (not a sum of outbound + return
quoted totals), divides by the number of adults once, and dedupes identical
flight-family products. No false GBP FX, booking/seller URLs, verified cash
refund rights or baggage charges are introduced.

## Browser and deployment gates

The browser checks the authenticated Worker's \`hostedSearch\` capability
matching the chosen trip type. Quote preparation, the exact bound query and
one-use signed token, expiry, explicit cost warning and human confirmation
still apply. Both \`SEARCH_EXECUTION_ENABLED\` and
\`SEARCH_PAID_CALLS_APPROVED\` remain **OFF by default**. So does preparation
unless separately enabled. Presence of \`SERPAPI_API_KEY\` alone cannot
authorize paid Fetch. D1 caps must be configured explicitly by an owner.

The locally compiled Kotlin/JS + real SQLite integration test uses only
synthetic SerpApi JSON, including a base response and separate return response,
verifies independent reservations before HTTP, 2-adult pricing, saved results,
cross-owner isolation, replay rejection, and an outbound with no departure
token producing **zero priced round-trip fares and partial coverage**.

**No live credentials, cap configuration, actual provider calls, or Site
deployment are performed by this PR.** Real Sites-attested identity, live D1
isolation and provider account readiness still require a separate operator
preflight and explicit owner approval before activating the route.
