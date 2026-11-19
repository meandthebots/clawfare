/**
 * First deliberately narrow hosted search preparation boundary.
 *
 * Authentication is performed by the trusted Sites ingress before calling
 * this method. This module sends NO provider HTTP, and issues only one-use
 * consent for a bounded, SerpApi-only Kotlin-normalized search. Preparing is
 * not executing; /api/search remains hard-disabled by another release switch.
 */
import { D1SearchConsents, SearchConsentError, requireSameOrigin } from "./search-consent.mjs";
import { limitedBody, requestBoundaryProblem } from "./search-estimate.mjs";

const MAX_CALLS = 16;
const PROVIDER = "serpapi";
const JSON_HEADERS = {
  "content-type": "application/json; charset=utf-8",
  "cache-control": "private, no-store",
  "x-content-type-options": "nosniff",
  "vary": "Cookie, oai-authenticated-user-email",
};
function respond(value, status = 200) {
  return new Response(JSON.stringify(value), { status, headers: JSON_HEADERS });
}
function invalid(message, status = 400) {
  return respond({ error: message }, status);
}
function parseKotlinPlan(kotlinEngine, input) {
  if (!kotlinEngine || typeof kotlinEngine.planSearchJson !== "function") {
    throw new Error("Compiled Kotlin search planner required");
  }
  let plan;
  try {
    plan = JSON.parse(kotlinEngine.planSearchJson(JSON.stringify(input)));
  } catch {
    return { error: "Unable to normalize the flight search using Kotlin.", status: 400 };
  }
  if (!plan || typeof plan !== "object" || Array.isArray(plan) ||
      !plan.query || typeof plan.query !== "object" || !Array.isArray(plan.pairs)) {
    return { error: "Invalid compiled Kotlin flight plan.", status: 500 };
  }
  if (plan.error) return { error: String(plan.error), status: 400 };
  const query = plan.query;
  if (!Array.isArray(query.providers) || query.providers.length !== 1 ||
      query.providers[0] !== PROVIDER) {
    return { error: "Hosted search preparation currently supports SerpApi alone.", status: 422 };
  }
  if (query.confirmed !== false || query.cacheOnly !== false ||
      !Array.isArray(query.origins) || !Array.isArray(query.destinations) ||
      !Number.isSafeInteger(query.returnChoices) ||
      query.returnChoices < 1 || query.returnChoices > 8 ||
      typeof query.fetchReturns !== "boolean" ||
      !["round_trip", "one_way"].includes(query.tripType)) {
    return { error: "Invalid or unsupported canonical Kotlin search plan.", status: 400 };
  }
  if (!Number.isSafeInteger(query.maxLiveCalls) || query.maxLiveCalls < 1 ||
      query.maxLiveCalls > MAX_CALLS) {
    return { error: "Set an explicit hard cap of 1–16 provider requests.", status: 422 };
  }
  if (!plan.pairs.length || plan.pairs.length > 500 ||
      !query.origins.length || query.origins.length > 8 ||
      !query.destinations.length || query.destinations.length > 8) {
    return { error: "No valid bounded provider date pairs or airport routes.", status: 422 };
  }
  for (const pair of plan.pairs) {
    if (!pair || typeof pair.outbound !== "string" ||
        !/^20[2-9][0-9]-\d\d-\d\d$/.test(pair.outbound) ||
        !(pair.returning == null ||
          typeof pair.returning === "string" && /^20[2-9][0-9]-\d\d-\d\d$/.test(pair.returning))) {
      return { error: "Kotlin provided an invalid route date pair.", status: 400 };
    }
    if (query.tripType === "round_trip" && !pair.returning ||
        query.tripType === "one_way" && pair.returning) {
      return { error: "Kotlin returned contradictory one-way/round-trip dates.", status: 400 };
    }
  }
  const base = plan.pairs.length * query.origins.length * query.destinations.length;
  const returnUpper = query.tripType === "round_trip" && query.fetchReturns
    ? base * query.returnChoices : 0;
  const total = base + returnUpper;
  if (total > MAX_CALLS || total > query.maxLiveCalls) {
    return { error: "Potential SerpApi requests exceed the hard 16-call or query cap.", status: 422 };
  }
  const callLimits = [{ provider: PROVIDER, category: "base_search", count: base }];
  if (returnUpper) {
    callLimits.push({ provider: PROVIDER, category: "return_selection", count: returnUpper });
  }
  return { query, canonicalQueryJson: JSON.stringify(query),
    base, returnUpper, total, callLimits };
}

export async function prepareSerpApiSearch(request, {
  db, ownerId, kotlinEngine, consentSigningSecret,
  providerKeyPresent = false, enabled = false, executionAvailable = false, clock = () => new Date(),
  cryptoApi = globalThis.crypto,
}) {
  // Feature flag is server-derived and OFF by default; it is *not* a browser
  // query flag. Never issue approval without a verified server owner.
  if (enabled !== true) return invalid("Hosted paid search preparation disabled.", 405);
  try {
    requireSameOrigin(request);
  } catch (error) {
    if (error instanceof SearchConsentError) return invalid(error.message, 403);
    throw error;
  }
  if (request.method !== "POST") return invalid("POST required.", 405);
  if (!/^application\/json(?:\s*;|$)/i.test(request.headers.get("content-type") || "")) {
    return invalid("Content-Type: application/json required.", 415);
  }
  const input = await limitedBody(request);
  if (input.error) return invalid(input.error, input.status);
  const invalidBoundary = requestBoundaryProblem(input.parsed);
  if (invalidBoundary) return invalid(invalidBoundary, 400);
  // Reject a client claim of prior authorization. A signed server nonce is
  // issued only AFTER the canonical plan is evaluated and D1 caps are checked.
  if (input.parsed.confirmed === true) return invalid("Confirmed flag cannot grant hosted approval.");
  const plan = parseKotlinPlan(kotlinEngine, input.parsed);
  if (plan.error) return invalid(plan.error, plan.status);
  if (executionAvailable && (plan.query.tripType !== "one_way" ||
      plan.query.currency !== "GBP" || plan.query.fetchReturns !== false)) {
    return invalid("Hosted execution currently supports one-way GBP SerpApi fares only.", 422);
  }
  if (!providerKeyPresent || !db?.prepare) {
    return invalid("SerpApi credential or hosted D1 storage unavailable.", 503);
  }
  let quote;
  try {
    const consents = new D1SearchConsents(db, {
      secret: consentSigningSecret, enabled: true,
      credentialPresence: { serpapi: true }, clock, cryptoApi,
    });
    quote = await consents.issue({
      ownerId, canonicalQueryJson: plan.canonicalQueryJson,
      callLimits: plan.callLimits,
    });
  } catch (error) {
    if (error instanceof SearchConsentError) {
      return invalid(error.code === "quota_denied"
        ? "Local SerpApi budget unavailable or insufficient for entire query."
        : error.message, error.code === "quota_denied" ? 429 : 400);
    }
    // Do not leak D1 SQL details, secrets or provider identifiers in errors.
    return invalid("Hosted search approval service unavailable.", 503);
  }
  return respond({
    canonicalQuery: plan.query,
    consentToken: quote.token,
    expiresAt: quote.expiresAt,
    providers: [PROVIDER],
    baseCallsUpperBound: plan.base,
    returnLookupsUpperBound: plan.returnUpper,
    bookingOptionCallsUpperBound: 0,
    quotaCallsUpperBound: 0,
    totalCallsUpperBound: quote.totalCalls,
    cost: {
      vendorBillingKnown: false,
      message: "Each attempted provider request may consume quota; account charges and remaining remote quota are unknown.",
    },
    canExecuteHere: executionAvailable === true,
    message: executionAvailable
      ? "Explicit confirmation and this signed one-use consent are required before provider requests."
      : "Prepared only. The hosted execution route is still disabled.",
  });
}
