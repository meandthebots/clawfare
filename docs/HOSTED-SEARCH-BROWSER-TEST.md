# Browser release gate: hosted real-result lifecycle (issue #135)

This is a **synthetic, zero-provider-egress** Playwright Chromium test of the actual compiled React UI. It complements `sites-runtime/tests/hosted-search-synthetic.mjs`, which tests real compiled Kotlin/JS, signed consent, and D1 via SQLite.

Run via the `Mobile grid interaction` GitHub workflow:

```sh
node sites-runtime/tests/hosted-search-browser.mjs \
  build/frontend/web/index.html build/sites/clawfare-sample.json
```

The browser test intercepts all navigation and API calls at `https://clawfare.test`. External requests, forged identity headers, `/api/session`, and unexpected backend paths fail the test. All prices and calls are synthetic. It checks:

- Disabled server capability: estimate may POST at zero cost; no signed quote or execution request is allowed.
- When the server advertises trip-specific capability: estimate → signed server quote → explicit cost-warning confirmation. Cancel must not execute. Editing any field revokes the prior approval; new consent is required.
- The exact returned canonical query/nonce is submitted, once. The Run button enters a busy/disabled state. A valid complete ResultSet replaces the Kotlin Florida sample without mixing add-ons, sample booking links, or supplier claims.
- The live itinerary table shows original dates, two-adult per-person and party totals, opens details, supports matrix selection and the cash-refund filter (unverified fare is excluded). It must not invent a booking link.
- Saved investigation reopens from authenticated GET, including after reload; no paid query is repeated.
- At narrow/mobile width, real searches without add-on combinations have **tap-friendly itinerary cards** and a working details drawer, rather than an oversized desktop-only data table.

The test does **not** authorize paid calls, prove live SerpApi billing/terms, verify actual ChatGPT Sites identity, or deploy anything. Both SEARCH_EXECUTION_ENABLED and SEARCH_PAID_CALLS_APPROVED remain OFF by default. Real provider trials and private Site deployment require separate explicit owner approval.