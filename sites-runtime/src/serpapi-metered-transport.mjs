import { hostedProvider, HOSTED_PROVIDERS } from "./hosted-providers.mjs";
/**
 * Server-only, dormant SerpApi transport. No public route invokes this module.
 *
 * A trusted caller must supply a server-derived owner and canonical Kotlin
 * search. We verify and consume its signed, single-use D1 authorization
 * before any provider request, then reserve one D1 quota slot for EACH
 * attempt via meteredProviderFetch. Never persist or return opaque vendor
 * tokens, raw responses, credentials or search URLs.
 */
import { D1SearchConsents, SearchConsentError } from "./search-consent.mjs";
import { D1ProviderHttpBudget, meteredProviderFetch } from "./provider-http-budget.mjs";
import { D1ProviderPersistence } from "./provider-persistence.mjs";

const MAX_CALLS = 16;
const MAX_RESPONSE_BYTES = 256 * 1024;
const DEFAULT_TIMEOUT_MS = 15_000;
const IATA = /^[A-Z]{3}$/;
const DATE = /^20[2-9][0-9]-\d\d-\d\d$/;
const CURRENCY = /^[A-Z]{3}$/;
const MARKET = /^[a-z]{2}$/;
const CABINS = Object.freeze({
  economy: "1",
  premium_economy: "2",
  business: "3",
  first: "4",
});

export class SerpApiTransportError extends Error {
  constructor(code) {
    super(code);
    this.name = "SerpApiTransportError";
    this.code = code;
  }
}
function reject(code) {
  throw new SerpApiTransportError(code);
}
function dateCode(input) {
  if (typeof input !== "string" || !DATE.test(input)) reject("invalid_plan");
  const dt = new Date(input + "T00:00:00Z");
  if (!Number.isFinite(dt.getTime()) || dt.toISOString().slice(0, 10) !== input) {
    reject("invalid_plan");
  }
  return input;
}
function boundedPositiveNumber(value, upper) {
  return Number.isSafeInteger(value) && value >= 1 && value <= upper;
}
function validateKotlinPlan(kotlinEngine, canonicalQueryJson, tripType = "one_way") {
  if (!kotlinEngine || typeof kotlinEngine.planSearchJson !== "function" ||
      typeof kotlinEngine.parseGoogleFlightsOffersJson !== "function") {
    reject("kotlin_unavailable");
  }
  if (typeof canonicalQueryJson !== "string" ||
      new TextEncoder().encode(canonicalQueryJson).byteLength > 16 * 1024) {
    reject("invalid_plan");
  }
  let planned;
  try {
    planned = JSON.parse(kotlinEngine.planSearchJson(canonicalQueryJson));
  } catch {
    reject("invalid_plan");
  }
  if (!planned || planned.error || !planned.query ||
      JSON.stringify(planned.query) !== canonicalQueryJson ||
      !Array.isArray(planned.pairs)) {
    reject("invalid_plan");
  }
  const query = planned.query;
  // Never interpret an outbound-only base result as a complete round trip.
  if (query.tripType !== tripType ||
      !["one_way", "round_trip"].includes(tripType) ||
      !Array.isArray(query.providers) || query.providers.length !== 1 ||
      !HOSTED_PROVIDERS[hostedProvider(query)]?.googleFlights || query.confirmed !== false ||
      query.cacheOnly !== false || query.fetchReturns !== (tripType === "round_trip") ||
      !boundedPositiveNumber(query.maxLiveCalls, MAX_CALLS) ||
      !boundedPositiveNumber(query.adults, 9) ||
      !Object.hasOwn(CABINS, query.cabin) ||
      typeof query.nonstopOnly !== "boolean" ||
      typeof query.market !== "string" || !MARKET.test(query.market) ||
      typeof query.currency !== "string" || !CURRENCY.test(query.currency) ||
      !Array.isArray(query.origins) || !Array.isArray(query.destinations) ||
      query.origins.length < 1 || query.destinations.length < 1 ||
      query.origins.length > 8 || query.destinations.length > 8 ||
      query.origins.some(code => typeof code !== "string" || !IATA.test(code)) ||
      query.destinations.some(code => typeof code !== "string" || !IATA.test(code)) ||
      planned.pairs.length < 1 || planned.pairs.length > MAX_CALLS) {
    reject("unsupported_plan");
  }
  for (const pair of planned.pairs) {
    if (!pair) reject("unsupported_plan");
    dateCode(pair.outbound);
    if (tripType === "round_trip") {
      dateCode(pair.returning);
      if (pair.returning <= pair.outbound) reject("invalid_plan");
    } else if (pair.returning != null) reject("unsupported_plan");
  }
  if (tripType === "round_trip" && !boundedPositiveNumber(query.returnChoices, 8)) {
    reject("unsupported_plan");
  }
  const baseCalls = planned.pairs.length * query.origins.length * query.destinations.length;
  const returnCalls = tripType === "round_trip" ? baseCalls * query.returnChoices : 0;
  const calls = baseCalls + returnCalls;
  if (calls > MAX_CALLS || calls > query.maxLiveCalls) reject("request_limit");
  return { query, pairs: planned.pairs, calls, baseCalls, returnCalls };
}
function makeRequestUrl(query, origin, destination, outbound, apiKey, returning = null) {
  const searchApi = hostedProvider(query) === "searchapi";
  const url = new URL(searchApi ? "https://www.searchapi.io/api/v1/search" : "https://serpapi.com/search.json");
  const fields = {
    engine: "google_flights",
    currency: query.currency,
    gl: query.market,
    hl: "en",
    adults: String(query.adults),
    departure_id: origin,
    arrival_id: destination,
    outbound_date: outbound,
    ...(searchApi ? {
      flight_type: returning == null ? "one_way" : "round_trip",
      travel_class: query.cabin === "first" ? "first_class" : query.cabin,
    } : { type: returning == null ? "2" : "1", travel_class: CABINS[query.cabin] }),
  };
  for (const [key, value] of Object.entries(fields)) url.searchParams.set(key, value);
  if (returning != null) url.searchParams.set("return_date", returning);
  if (query.nonstopOnly) url.searchParams.set("stops", searchApi ? "nonstop" : "1");
  url.searchParams.set("api_key", apiKey);
  return url.toString();
}
export async function readProviderJson(response, maxBytes) {
  if (response.status === 429) reject("provider_rate_limited");
  if (response.status >= 300 && response.status < 400) reject("provider_redirect");
  if (response.status === 401 || response.status === 403) reject("provider_auth");
  if (response.status < 200 || response.status > 299) reject("provider_http");
  if (!/\bapplication\/(?:[\w.+-]*\+)?json\b/i.test(
    response.headers.get("content-type") || "")) {
    reject("invalid_response");
  }
  const length = response.headers.get("content-length");
  if (length != null && /^\d+$/.test(length) && Number(length) > maxBytes) {
    reject("response_too_large");
  }
  if (!response.body) reject("invalid_response");
  const reader = response.body.getReader();
  let size = 0;
  const chunks = [];
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > maxBytes) {
        await reader.cancel();
        reject("response_too_large");
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  let raw, data;
  try {
    raw = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
    data = JSON.parse(raw);
  } catch {
    reject("invalid_response");
  }
  if (!data || typeof data !== "object" || Array.isArray(data)) {
    reject("invalid_response");
  }
  return { raw, data };
}

async function boundedResponseJson(response, maxBytes) {
  const { raw, data } = await readProviderJson(response, maxBytes);
  if (Object.hasOwn(data, "error")) reject("provider_http");
  const hasFlights = ["best_flights", "other_flights"].some(
    key => Array.isArray(data[key]));
  if (!hasFlights) reject("invalid_response");
  const providerRows = [data.best_flights, data.other_flights]
    .reduce((sum, group) => sum + (Array.isArray(group) ? group.length : 0), 0);
  if (providerRows > 1000) reject("invalid_response");
  return { raw, providerRows };
}

function parseKotlinOffers(kotlinEngine, raw) {
  let offers;
  try {
    offers = JSON.parse(kotlinEngine.parseGoogleFlightsOffersJson(raw));
  } catch {
    reject("invalid_response");
  }
  if (!Array.isArray(offers) || offers.length > 1000 ||
      offers.some(o => !o || !Array.isArray(o.legs))) {
    reject("invalid_response");
  }
  // Never return the parsed offer itself. It can contain opaque departure
  // and booking tokens, which only a later trusted return-selection phase
  // may consume and must never send to the browser or D1 public snapshots.
  return offers;
}

export async function digestFor(value, cryptoApi) {
  const bytes = await cryptoApi.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(bytes), b => b.toString(16).padStart(2, "0")).join("");
}

export function terminalErrorKind(code) {
  if (code === "provider_timeout") return "timeout";
  if (code === "invalid_response" || code === "response_too_large") return "invalid_response";
  if (code === "provider_transport") return "network";
  if (code === "quota_exhausted") return "quota_exhausted";
  return "provider_error";
}

async function draftsFromOffers(offers, query, kotlinEngine, fetchedAt, cryptoApi) {
  if (typeof kotlinEngine.buildGoogleFareDraftJson !== "function" ||
      typeof kotlinEngine.evaluateOfferEligibilityJson !== "function" || offers.length > 100) {
    reject("invalid_response");
  }
  const drafts = [];
  let eligible = 0;
  for (const offer of offers) {
    const segments = offer.legs.map(l => [l.flightNumber, l.departDate, l.from, l.to]);
    if (segments.some(s => s.some(v => typeof v !== "string" || !v))) reject("invalid_response");
    const hash = await digestFor(JSON.stringify([query.providers[0], segments, offer.fareBrand ?? null]), cryptoApi);
    const id = query.providers[0] + "-" + hash.slice(0, 40);
    let draft, evaluation;
    try {
      draft = JSON.parse(kotlinEngine.buildGoogleFareDraftJson(JSON.stringify({
        id, provider: query.providers[0], query, outbound: offer, fetchedAt,
        servedFromCache: false,
      })));
      evaluation = JSON.parse(kotlinEngine.evaluateOfferEligibilityJson(JSON.stringify({
        query, evidence: { outbound: draft.outbound }, airportCountries: {},
      })));
    } catch { reject("invalid_response"); }
    if (!draft || !draft.outbound || !Array.isArray(draft.outbound.legs) ||
        draft.source !== query.providers[0] || draft.returnMissing || draft.returning ||
        !["live", "cached"].includes(draft.sourceLabel) ||
        typeof evaluation?.eligible !== "boolean") reject("invalid_response");
    if (evaluation.eligible) eligible++;
    // The Kotlin draft is token-free by construction. Never export ParsedOffer.
    drafts.push(draft);
  }
  return { drafts, eligible };
}

/**
 * Return safe coarse observations, NOT a browser ResultSet. No HTTP route may
 * expose this output as fare availability until #121 result assembly exists.
 * Abort/timeout stops future dispatches; a failed or incomplete sweep is NOT
 * a verified empty search. No implicit retries are permitted.
 */
export async function executeConsentedOneWaySerpApi({
  enabled = false, db, ownerId, canonicalQueryJson, consentToken,
  consentSigningSecret, serpApiKey, providerKey = serpApiKey, kotlinEngine,
  fetcher = fetch, clock = () => new Date(),
  cryptoApi = globalThis.crypto, timeoutMs = DEFAULT_TIMEOUT_MS,
  maxResponseBytes = MAX_RESPONSE_BYTES,
  includeDrafts = false, recordAttempts = false, investigationSlug = null,
}) {
  if (enabled !== true) reject("disabled");
  if (typeof providerKey !== "string" || providerKey.length < 1 ||
      providerKey.length > 512 || /\s/.test(providerKey) ||
      typeof consentSigningSecret !== "string" || !db?.prepare) {
    reject("server_configuration");
  }
  if (!boundedPositiveNumber(timeoutMs, 30_000) ||
      !boundedPositiveNumber(maxResponseBytes, MAX_RESPONSE_BYTES)) {
    reject("server_configuration");
  }
  const { query, pairs, calls } = validateKotlinPlan(kotlinEngine, canonicalQueryJson);
  const consents = new D1SearchConsents(db, {
    enabled: true, secret: consentSigningSecret, clock, cryptoApi,
  });
  let approved;
  try {
    approved = await consents.consume({
      ownerId, canonicalQueryJson, token: consentToken,
    });
  } catch (error) {
    if (error instanceof SearchConsentError) reject("consent_" + error.code);
    throw error;
  }
  if (approved.totalCalls !== calls ||
      approved.callLimits.length !== 1 ||
      approved.callLimits[0].provider !== query.providers[0] ||
      approved.callLimits[0].category !== "base_search" ||
      approved.callLimits[0].count !== calls) {
    // Used consent cannot be reused even for a wrong-category authorization.
    reject("consent_call_limit_mismatch");
  }
  const budget = new D1ProviderHttpBudget(db, clock);
  const persistence = recordAttempts ? new D1ProviderPersistence(db, { clock, cryptoApi }) : null;
  const fetchedAt = clock().toISOString();
  const drafts = [];
  const attempts = [];
  let completed = 0;
  let errorCode = null;
  outer:
  for (const pair of pairs) {
    for (const origin of query.origins) {
      for (const destination of query.destinations) {
        if (origin === destination) {
          // Fail closed rather than silently charging fewer routes than approved.
          errorCode = "invalid_plan";
          break outer;
        }
        const url = makeRequestUrl(query, origin, destination, pair.outbound, providerKey);
        const routeDigest = persistence ? await digestFor(
          JSON.stringify([approved.queryDigest, origin, destination, pair.outbound]), cryptoApi,
        ) : null;
        const controller = new AbortController();
        let began = false;
        const timer = setTimeout(() => controller.abort(), timeoutMs);
        try {
          if (persistence) {
            await persistence.beginAttempt({
              ownerId, consentNonce: approved.nonce, queryDigest: approved.queryDigest,
              provider: query.providers[0], routeDigest, investigationSlug,
            });
            began = true;
          }
          const response = await meteredProviderFetch(
            budget, url, { method: "GET", signal: controller.signal }, fetcher,
          );
          const { raw, providerRows } = await boundedResponseJson(response, maxResponseBytes);
          const offers = parseKotlinOffers(kotlinEngine, raw);
          const offersReported = offers.length;
          const built = includeDrafts
            ? await draftsFromOffers(offers, query, kotlinEngine, fetchedAt, cryptoApi)
            : { drafts: [], eligible: 0 };
          const state = offersReported < providerRows ? "partial" : offersReported ? "complete" : "empty";
          if (persistence) await persistence.finishAttempt({
            ownerId, consentNonce: approved.nonce, provider: query.providers[0], routeDigest,
            state, attemptedCalls: 1,
            loadedCalls: 1, cachedCalls: 0, returnedOffers: offersReported,
            eligibleOffers: built.eligible, errorKind: state === "partial" ? "incomplete" : null,
          });
          drafts.push(...built.drafts);
          attempts.push({
            provider: query.providers[0], origin, destination, outboundDate: pair.outbound,
            state, offersReported, providerRows, eligibleOffers: built.eligible,
            sourceLabel: "live",
          });
          completed += 1;
        } catch (error) {
          errorCode = error instanceof SerpApiTransportError
            ? error.code : controller.signal.aborted ? "provider_timeout" : error.code ?? "provider_transport";
          if (began) {
            try {
              await persistence.finishAttempt({
                ownerId, consentNonce: approved.nonce, provider: query.providers[0], routeDigest,
                state: "failed", attemptedCalls: 1, loadedCalls: 0, cachedCalls: 0,
                returnedOffers: 0, eligibleOffers: 0, errorKind: terminalErrorKind(errorCode),
              });
            } catch { /* A crashed D1 terminal write leaves attempted, not false empty. */ }
          }
          attempts.push({
            provider: query.providers[0], origin, destination, outboundDate: pair.outbound,
            state: "failed", offersReported: null, error: errorCode, errorKind: terminalErrorKind(errorCode),
          });
          break outer;
        } finally {
          clearTimeout(timer);
        }
      }
    }
  }
  return {
    state: errorCode == null && !attempts.some(row => row.state === "partial") ? "complete"
      : completed ? "partial" : "failed",
    provider: query.providers[0],
    plannedCalls: calls,
    completedCalls: completed,
    attemptedCalls: attempts.length,
    attempts,
    errorCode,
    // This is NOT an eligibility-filtered or booking-ready result.
    hasResultSet: false,
    // Returned ONLY to the trusted server-side assembler when explicitly requested.
    // Never expose these without the server-verified Kotlin ResultSet boundary.
    ...(includeDrafts ? { drafts, fetchedAt, consentNonce: approved.nonce } : {}),
  };
}


function selectedOutboundOffers(offers, maxChoices) {
  // Prefer lowest *observed* provider party price, then original result order.
  // Never invent a zero price for an unpriced response.
  return offers
    .map((offer, index) => ({ offer, index }))
    .filter(({ offer }) => Array.isArray(offer.legs) && offer.legs.length)
    .sort((a, b) => (a.offer.price ?? Infinity) - (b.offer.price ?? Infinity) ||
      a.index - b.index)
    .slice(0, maxChoices)
    .map(({ offer }) => offer);
}
function validDepartureToken(token) {
  return typeof token === "string" && token.length > 0 && token.length <= 4096 &&
    !/[\x00-\x1f\x7f]/.test(token);
}
async function roundTripDraft(outbound, inbound, query, fetchedAt, returnDateHint,
  kotlinEngine, cryptoApi) {
  if (typeof kotlinEngine.buildGoogleFareDraftJson !== "function" ||
      typeof kotlinEngine.evaluateOfferEligibilityJson !== "function" ||
      !Array.isArray(outbound?.legs) || outbound.legs.length === 0) {
    reject("kotlin_unavailable");
  }
  const legs = [...outbound.legs, ...(inbound?.legs || [])];
  const family = legs.map(leg => [leg.flightNumber, leg.departDate, leg.from, leg.to]);
  if (family.some(segment => segment.some(x => typeof x !== "string" || !x))) {
    reject("invalid_response");
  }
  const fareBrand = inbound?.fareBrand ?? outbound.fareBrand ?? null;
  const id = query.providers[0] + "-" + (await digestFor(
    JSON.stringify([query.providers[0], family, fareBrand]), cryptoApi)).slice(0, 40);
  let draft, eligibility;
  try {
    draft = JSON.parse(kotlinEngine.buildGoogleFareDraftJson(JSON.stringify({
      id, provider: query.providers[0], query, outbound, inbound,
      fetchedAt, servedFromCache: false, returnDateHint,
    })));
    eligibility = JSON.parse(kotlinEngine.evaluateOfferEligibilityJson(JSON.stringify({
      query,
      evidence: {
        outbound: draft.outbound,
        returning: draft.returning,
        returnMissing: draft.returnMissing,
      },
      airportCountries: {},
    })));
  } catch {
    reject("invalid_response");
  }
  if (!draft || draft.source !== query.providers[0] || draft.passengers !== query.adults ||
      draft.priceCurrency !== query.currency || !draft.outbound?.legs ||
      !Array.isArray(draft.outbound.legs) ||
      draft.returnMissing !== (inbound == null) ||
      typeof eligibility?.eligible !== "boolean") {
    reject("invalid_response");
  }
  // Only a verified pair may count as an eligible, priced return.
  return { draft, eligible: inbound != null && eligibility.eligible };
}
async function readFlightOffers(budget, url, fetcher, timeoutMs, maxResponseBytes, engine) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await meteredProviderFetch(
      budget, url, { method: "GET", signal: controller.signal }, fetcher,
    );
    const { raw, providerRows } = await boundedResponseJson(response, maxResponseBytes);
    return { offers: parseKotlinOffers(engine, raw), providerRows };
  } catch (error) {
    if (controller.signal.aborted) reject("provider_timeout");
    throw error;
  } finally {
    clearTimeout(timer);
  }
}
function matchedReturnOffers(offers, origin, destination, returning) {
  return offers.filter(offer => Array.isArray(offer.legs) && offer.legs.length > 0 &&
    offer.legs[0].from === destination &&
    offer.legs.at(-1).to === origin &&
    offer.legs[0].departDate === returning);
}

/**
 * Fully budgeted round-trip SerpApi transport. No public route calls this
 * function unless authenticated execution has explicitly enabled *both*
 * server-side paid-call gates and authenticated, one-use consent.
 *
 * Base results are outbound choices, NOT full round trips. Follow-up requests
 * use only server-private provider departure tokens. A missing/failed return
 * is represented as an incomplete draft, never as a priced return fare.
 */
export async function executeConsentedRoundTripSerpApi({
  enabled = false, db, ownerId, canonicalQueryJson, consentToken,
  consentSigningSecret, serpApiKey, providerKey = serpApiKey, kotlinEngine,
  fetcher = fetch, clock = () => new Date(),
  cryptoApi = globalThis.crypto, timeoutMs = DEFAULT_TIMEOUT_MS,
  maxResponseBytes = MAX_RESPONSE_BYTES,
  includeDrafts = false, recordAttempts = false, investigationSlug = null,
}) {
  if (enabled !== true) reject("disabled");
  if (typeof providerKey !== "string" || providerKey.length < 1 ||
      providerKey.length > 512 || /\s/.test(providerKey) ||
      typeof consentSigningSecret !== "string" || !db?.prepare ||
      typeof kotlinEngine?.assembleHostedRoundTripJson !== "function") {
    reject("server_configuration");
  }
  if (!boundedPositiveNumber(timeoutMs, 30_000) ||
      !boundedPositiveNumber(maxResponseBytes, MAX_RESPONSE_BYTES)) {
    reject("server_configuration");
  }
  const { query, pairs, calls, baseCalls, returnCalls } =
    validateKotlinPlan(kotlinEngine, canonicalQueryJson, "round_trip");
  const consents = new D1SearchConsents(db, {
    enabled: true, secret: consentSigningSecret, clock, cryptoApi,
  });
  let approved;
  try {
    approved = await consents.consume({
      ownerId, canonicalQueryJson, token: consentToken,
    });
  } catch (error) {
    if (error instanceof SearchConsentError) reject("consent_" + error.code);
    throw error;
  }
  if (approved.totalCalls !== calls ||
      approved.callLimits.length !== 2 ||
      !approved.callLimits.some(x =>
        x.provider === query.providers[0] && x.category === "base_search" &&
        x.count === baseCalls) ||
      !approved.callLimits.some(x =>
        x.provider === query.providers[0] && x.category === "return_selection" &&
        x.count === returnCalls)) {
    reject("consent_call_limit_mismatch");
  }

  const budget = new D1ProviderHttpBudget(db, clock);
  const persisted = recordAttempts ? new D1ProviderPersistence(db, { clock, cryptoApi }) : null;
  const fetchedAt = clock().toISOString();
  const drafts = [], attempts = [];
  let completedBase = 0, selectedReturns = 0, loadedReturns = 0, unresolvedReturns = 0;
  let attemptedCalls = 0, errorCode = null;
  outer:
  for (const pair of pairs) {
    for (const origin of query.origins) {
      for (const destination of query.destinations) {
        const routeDigest = persisted ? await digestFor(
          JSON.stringify([approved.queryDigest, origin, destination,
            pair.outbound, pair.returning]), cryptoApi,
        ) : null;
        let began = false, routeAttempted = 0, routeLoaded = 0;
        let returnedOffers = 0, providerRows = null, eligibleOffers = 0, missing = 0, routeError = null;
        let routeReturnsLoaded = 0, selectionLimited = false;
        try {
          if (origin === destination) reject("invalid_plan");
          if (persisted) {
            await persisted.beginAttempt({
              ownerId, consentNonce: approved.nonce, queryDigest: approved.queryDigest,
              provider: query.providers[0], routeDigest, investigationSlug,
            });
            began = true;
          }
          const baseUrl = makeRequestUrl(query, origin, destination, pair.outbound,
            providerKey, pair.returning);
          routeAttempted++; attemptedCalls++;
          const base = await readFlightOffers(
            budget, baseUrl, fetcher, timeoutMs, maxResponseBytes, kotlinEngine,
          );
          routeLoaded++;
          completedBase++;
          providerRows = base.providerRows;
          returnedOffers = base.offers.length;
          const selected = selectedOutboundOffers(base.offers, query.returnChoices);
          selectionLimited = selected.length < returnedOffers;

          for (const outbound of selected) {
            let inbound = null;
            if (validDepartureToken(outbound.departureToken)) {
              const returnUrl = new URL(baseUrl);
              returnUrl.searchParams.set("departure_token", outbound.departureToken);
              routeAttempted++; attemptedCalls++;
              const inboundOptions = await readFlightOffers(
                budget, returnUrl.toString(), fetcher, timeoutMs,
                maxResponseBytes, kotlinEngine,
              );
              routeLoaded++;
              loadedReturns++;
              routeReturnsLoaded++;
              inbound = selectedOutboundOffers(
                matchedReturnOffers(inboundOptions.offers, origin, destination, pair.returning), 1,
              )[0] ?? null;
            }
            selectedReturns++;
            if (inbound == null) { missing++; unresolvedReturns++; }
            const built = await roundTripDraft(outbound, inbound, query,
              fetchedAt, pair.returning, kotlinEngine, cryptoApi);
            if (built.eligible) eligibleOffers++;
            drafts.push(built.draft);
          }
          const state = missing > 0 || returnedOffers < providerRows ? "partial"
            : returnedOffers === 0 ? "empty" : "complete";
          if (persisted) {
            await persisted.finishAttempt({
              ownerId, consentNonce: approved.nonce, provider: query.providers[0], routeDigest,
              state, attemptedCalls: routeAttempted, loadedCalls: routeLoaded,
              cachedCalls: 0, returnedOffers, eligibleOffers,
              errorKind: state === "partial" ? "incomplete" : null,
            });
          }
          attempts.push({
            provider: query.providers[0], origin, destination,
            outboundDate: pair.outbound, returnDate: pair.returning,
            state, providerRows, offersReported: returnedOffers, eligibleOffers, selectionLimited,
            returnLookupsLoaded: routeReturnsLoaded, unresolvedReturns: missing, sourceLabel: "live",
          });
        } catch (error) {
          routeError = error instanceof SerpApiTransportError
            ? error.code : error.code ?? "provider_transport";
          errorCode = routeError;
          if (began) {
            try {
              await persisted.finishAttempt({
                ownerId, consentNonce: approved.nonce, provider: query.providers[0], routeDigest,
                state: routeLoaded > 0 ? "partial" : "failed",
                attemptedCalls: routeAttempted, loadedCalls: routeLoaded,
                cachedCalls: 0, returnedOffers, eligibleOffers,
                errorKind: terminalErrorKind(routeError),
              });
            } catch { /* Retain crash-visible attempted state if D1 completion fails. */ }
          }
          attempts.push({
            provider: query.providers[0], origin, destination,
            outboundDate: pair.outbound, returnDate: pair.returning,
            state: routeLoaded > 0 ? "partial" : "failed",
            offersReported: returnedOffers, error: routeError,
          });
          break outer; // No automatic paid retries or follow-up requests after failure.
        }
      }
    }
  }
  const coverageComplete = errorCode == null && unresolvedReturns === 0 &&
    completedBase === baseCalls && !attempts.some(row => row.state === "partial");
  return {
    state: coverageComplete ? "complete" :
      completedBase > 0 ? "partial" : "failed",
    provider: query.providers[0],
    plannedCalls: calls,
    baseCallsExpected: baseCalls,
    completedCalls: completedBase,
    returnLookupsExpected: returnCalls,
    returnLookupsLoaded: loadedReturns,
    unresolvedReturns,
    attemptedCalls,
    attempts,
    errorCode,
    hasResultSet: false,
    ...(includeDrafts ? { drafts, fetchedAt, consentNonce: approved.nonce } : {}),
  };
}
