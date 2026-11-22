import { hostedProvider, hostedProviderProblem, HOSTED_PROVIDERS, providerSecret } from "./hosted-providers.mjs";
import { assembleCachedLeads, executeDirectHostedSearch } from "./direct-provider-transport.mjs";
/**
 * Authenticated, server-only SerpApi search execution. This cannot be enabled
 * by a URL, request body, browser flag or cookie: two independent Worker env
 * gates, an owner-bound one-use signed consent, D1 local caps and a verified
 * Kotlin plan are all required before even one paid HTTP request.
 *
 * The resulting browser DTO comes only from compiled Kotlin commonMain.
 * Provider payloads, opaque tokens and credentials never reach snapshots.
 */
import { requireSameOrigin, SearchConsentError } from "./search-consent.mjs";
import { limitedBody } from "./search-estimate.mjs";
import {
  executeConsentedOneWaySerpApi, executeConsentedRoundTripSerpApi, SerpApiTransportError,
} from "./serpapi-metered-transport.mjs";
import { D1Investigations, TRACKED_PREFIX } from "./investigations.mjs";

const HEADERS = {
  "content-type": "application/json; charset=utf-8",
  "cache-control": "private, no-store",
  "x-content-type-options": "nosniff",
  "vary": "Cookie, oai-authenticated-user-email",
};
const SLUG = /^[a-z0-9][a-z0-9_-]{0,99}$/;
function respond(data, status = 200) {
  return new Response(JSON.stringify(data), { status, headers: HEADERS });
}
function deny(message, status) {
  return respond({ ok: false, error: message }, status);
}
function newSlug(cryptoApi) {
  return "search-" + Array.from(cryptoApi.getRandomValues(new Uint8Array(12)),
    byte => byte.toString(16).padStart(2, "0")).join("");
}
function decimal(value) {
  if (typeof value !== "number" || !Number.isFinite(value) || value <= 0 ||
      value > 100000000) return null;
  return value.toFixed(6).replace(/0+$/, "").replace(/\.$/, "");
}
function boundedCanonicalQuery(input, kotlinEngine) {
  if (!input || typeof input !== "object" || Array.isArray(input) ||
      Object.keys(input).sort().join(",") !== "canonicalQuery,consentToken" ||
      typeof input.consentToken !== "string" ||
      input.consentToken.length < 16 || input.consentToken.length > 4096 ||
      !input.canonicalQuery || typeof input.canonicalQuery !== "object" ||
      Array.isArray(input.canonicalQuery)) return null;
  const raw = JSON.stringify(input.canonicalQuery);
  if (new TextEncoder().encode(raw).length > 14 * 1024) return null;
  let planned;
  try { planned = JSON.parse(kotlinEngine.planSearchJson(raw)); } catch { return null; }
  const q = planned?.query;
  if (planned?.error || !q || JSON.stringify(q) !== raw ||
      q.investigation?.startsWith(TRACKED_PREFIX) || hostedProviderProblem(q) ||
      q.confirmed !== false || !Array.isArray(planned.pairs) ||
      !Array.isArray(q.origins) || !Array.isArray(q.destinations) ||
      !q.origins.length || !q.destinations.length || planned.pairs.length < 1 ||
      !Number.isSafeInteger(q.maxLiveCalls) || q.maxLiveCalls < 1 || q.maxLiveCalls > 16 ||
      planned.pairs.length * q.origins.length * q.destinations.length > q.maxLiveCalls) return null;
  return { canonicalQueryJson: raw, query: q, pairs: planned.pairs };
}

function searchCoverage(query, pairs, observed, assembly) {
  const key = (origin, destination, out, back) => JSON.stringify([origin, destination, out, back ?? null]);
  const attempts = new Map(observed.attempts.map(attempt => [
    key(attempt.origin, attempt.destination, attempt.outboundDate, attempt.returnDate), attempt,
  ]));
  const routes = pairs.flatMap(pair => query.origins.flatMap(origin => query.destinations.map(destination => {
    const attempt = attempts.get(key(origin, destination, pair.outbound, pair.returning));
    return {
      provider: observed.provider,
      market: query.market,
      origin,
      destination,
      outboundDate: pair.outbound,
      returnDate: pair.returning ?? null,
      state: attempt?.state ?? "not_attempted",
      sourceMode: attempt?.sourceLabel ?? null,
      providerRows: attempt?.providerRows ?? null,
      parsedOffers: attempt?.offersReported ?? null,
      eligibleOffers: attempt?.eligibleOffers ?? 0,
      returnLookupsLoaded: attempt?.returnLookupsLoaded ?? 0,
      unresolvedReturns: attempt?.unresolvedReturns ?? 0,
      returnSelectionLimited: attempt?.selectionLimited === true,
      errorKind: attempt?.errorKind ?? null,
    };
  })));
  return {
    version: 1, observedAt: observed.fetchedAt, state: observed.state,
    routes, eligibleItineraries: assembly.eligible, excludedByReason: assembly.excludedByReason,
  };
}

/**
 * This function is invoked only AFTER the hosting adapter has verified the
 * ChatGPT identity and derived ownerId from its server-only HMAC secret.
 * Injecting a fake provider Fetch is allowed ONLY at the trusted test boundary.
 */
export async function executeHostedSearchRequest(request, {
  enabled = false, approvedForPaidCalls = false,
  db, ownerId, kotlinEngine, consentSigningSecret, serpApiKey, providerKeys, duffelPriceMode,
  fetcher = fetch, clock = () => new Date(), cryptoApi = globalThis.crypto,
}) {
  if (enabled !== true || approvedForPaidCalls !== true) {
    return deny("Hosted provider execution is disabled.", 405);
  }
  if (!db?.prepare || typeof db.batch !== "function" ||
      typeof kotlinEngine?.assembleHostedOneWayJson !== "function" ||
      typeof kotlinEngine?.assembleHostedRoundTripJson !== "function" ||
      typeof kotlinEngine?.buildGoogleFareDraftJson !== "function" ||
      !cryptoApi?.getRandomValues || !cryptoApi?.subtle ||
      typeof consentSigningSecret !== "string" ||
      new TextEncoder().encode(consentSigningSecret).length < 32) {
    return deny("Hosted search server is not configured.", 503);
  }
  try {
    requireSameOrigin(request);
  } catch (error) {
    if (error instanceof SearchConsentError) return deny("Cross-origin search blocked.", 403);
    throw error;
  }
  if (request.method !== "POST") return deny("POST required.", 405);
  if (!/^application\/json(?:\s*;|$)/i.test(request.headers.get("content-type") || "")) {
    return deny("Content-Type: application/json required.", 415);
  }
  const input = await limitedBody(request);
  if (input.error) return deny(input.error, input.status);
  const parsed = boundedCanonicalQuery(input.parsed, kotlinEngine);
  if (!parsed) return deny("A valid canonical hosted quote is required.", 422);
  const provider = hostedProvider(parsed.query);
  const providerKey = (providerKeys ?? { serpapi: serpApiKey })[provider];
  const mode = provider === "duffel" ? duffelPriceMode : HOSTED_PROVIDERS[provider].priceMode;
  if (!providerSecret(providerKey) || !mode) return deny("Provider credential or access mode unavailable.", 503);

  const investigationSlug = SLUG.test(parsed.query.investigation || "")
    ? parsed.query.investigation : newSlug(cryptoApi);
  let observed;
  try {
    const options = {
      ...parsed, enabled: true, db, ownerId,
      consentToken: input.parsed.consentToken, consentSigningSecret,
      providerKey, mode, kotlinEngine, fetcher, clock, cryptoApi,
      includeDrafts: true, recordAttempts: true, investigationSlug,
    };
    if (HOSTED_PROVIDERS[provider].googleFlights) {
      const transport = parsed.query.tripType === "round_trip"
        ? executeConsentedRoundTripSerpApi : executeConsentedOneWaySerpApi;
      observed = await transport(options);
    } else {
      observed = await executeDirectHostedSearch(options);
    }
  } catch (error) {
    if (error instanceof SerpApiTransportError) {
      const authentication = error.code.startsWith("consent_");
      const unsupported = ["invalid_plan", "unsupported_plan", "request_limit"].includes(error.code);
      return deny(authentication ? "Approval expired, changed or already used." :
        unsupported ? "Unsupported hosted flight search." :
          "Hosted provider search is unavailable.",
      authentication ? 409 : unsupported ? 422 : 503);
    }
    return deny("Hosted search could not be completed.", 503);
  }

  // Dated reference: this initial route quotes ONLY GBP, so no external FX
  // estimate is invented. Full currency rates need audited dated evidence.
  const day = observed.fetchedAt.slice(0, 10);
  let assembly;
  try {
    const assemble = parsed.query.tripType === "round_trip"
      ? kotlinEngine.assembleHostedRoundTripJson : kotlinEngine.assembleHostedOneWayJson;
    const fx = { asOf: day, perGbp: { GBP: 1 } };
    assembly = provider === "travelpayouts" ? assembleCachedLeads(investigationSlug, parsed.query, observed, fx) :
      JSON.parse(assemble(JSON.stringify({
      investigationId: investigationSlug,
      query: parsed.query, fetchedAt: observed.fetchedAt,
      drafts: observed.drafts, fx,
      airportCountries: {},
    })));
  } catch {
    return deny("Kotlin could not construct a verified fare result.", 503);
  }
  const result = assembly?.result;
  if (!result || !Array.isArray(result.itineraries) || result.id !== investigationSlug) {
    return deny("Kotlin returned an invalid fare result.", 503);
  }
  if (provider === "duffel" && mode === "mock") {
    result.suggestedFilters = { ...result.suggestedFilters, hideMock: false };
  }
  result.coverage = searchCoverage(parsed.query, parsed.pairs, observed, assembly);
  if (observed.state !== "complete") {
    result.warnings = [
      ...(result.warnings || []),
      "Search coverage is incomplete: some planned provider routes did not finish.",
    ];
  }
  // Persist only deduped original-currency, per-passenger positive observations.
  // Never save private ParsedOffer, vendor tokens or unverified booking URLs.
  const byId = new Map();
  for (const draft of observed.drafts) {
    const previous = byId.get(draft.id);
    if (!previous || (draft.originalPerPersonPrice ?? Infinity) <
        (previous.originalPerPersonPrice ?? Infinity)) byId.set(draft.id, draft);
  }
  const priceObservations = result.itineraries.flatMap(row => {
    const draft = byId.get(row.id);
    const amountDecimal = decimal(provider === "travelpayouts"
      ? row.priceOriginal ?? row.pricePerPerson : draft?.originalPerPersonPrice);
    if (!amountDecimal) return [];
    return [{
      itineraryId: row.id,
      provider, sourceLabel: row.sourceLabel,
      livePrice: row.livePrice, capturedAt: observed.fetchedAt,
      originalCurrency: row.priceCurrency, amountDecimal,
    }];
  });
  try {
    await new D1Investigations(db, { clock, cryptoApi })
      .saveResultWithPrices(ownerId, result, priceObservations);
  } catch {
    // The result must not be returned as saved when the transaction fails.
    return deny("D1 could not durably save the provider search. No retry is attempted.", 503);
  }
  const returned = observed.attempts.reduce((sum, attempt) =>
    sum + (attempt.offersReported || 0), 0);
  return respond({
    ok: observed.state !== "failed",
    result,
    providerObservations: [{
      provider,
      state: observed.state === "complete" ? "completed" : observed.state,
      returned, eligible: assembly.eligible,
      excludedByReason: assembly.excludedByReason,
      baseCallsExpected: observed.baseCallsExpected ?? observed.plannedCalls,
      baseCallsLoaded: observed.completedCalls,
      returnLookupsExpected: observed.returnLookupsExpected ?? 0,
      returnLookupsLoaded: observed.returnLookupsLoaded ?? 0,
      unresolvedReturns: observed.unresolvedReturns ?? 0,
    }],
    searchAttemptNonce: observed.consentNonce,
    ...(observed.state === "failed" ? {
      error: "Provider failed; this does not establish fare unavailability.",
    } : {}),
  });
}
