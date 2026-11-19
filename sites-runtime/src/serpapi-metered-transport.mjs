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
function validateKotlinPlan(kotlinEngine, canonicalQueryJson) {
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
  // Strictly one-way until shared, verified return-selection assembly exists.
  // Never issue an outbound-only result masquerading as a round-trip fare.
  if (query.tripType !== "one_way" ||
      !Array.isArray(query.providers) || query.providers.length !== 1 ||
      query.providers[0] !== "serpapi" || query.confirmed !== false ||
      query.cacheOnly !== false || query.fetchReturns !== false ||
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
    if (!pair || pair.returning != null) reject("unsupported_plan");
    dateCode(pair.outbound);
  }
  const calls = planned.pairs.length * query.origins.length * query.destinations.length;
  if (calls > MAX_CALLS || calls > query.maxLiveCalls) reject("request_limit");
  return { query, pairs: planned.pairs, calls };
}
function makeRequestUrl(query, origin, destination, outbound, apiKey) {
  const url = new URL("https://serpapi.com/search.json");
  const fields = {
    engine: "google_flights",
    currency: query.currency,
    gl: query.market,
    hl: "en",
    adults: String(query.adults),
    departure_id: origin,
    arrival_id: destination,
    outbound_date: outbound,
    type: "2",
    travel_class: CABINS[query.cabin],
  };
  for (const [key, value] of Object.entries(fields)) url.searchParams.set(key, value);
  if (query.nonstopOnly) url.searchParams.set("stops", "1");
  url.searchParams.set("api_key", apiKey);
  return url.toString();
}
async function boundedResponseJson(response, maxBytes) {
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
  if (Object.hasOwn(data, "error")) reject("provider_http");
  const hasFlights = ["best_flights", "other_flights"].some(
    key => Array.isArray(data[key]));
  if (!hasFlights) reject("invalid_response");
  return raw;
}

function countKotlinOffers(kotlinEngine, raw) {
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
  return offers.length;
}

/**
 * Return safe coarse observations, NOT a browser ResultSet. No HTTP route may
 * expose this output as fare availability until #121 result assembly exists.
 * Abort/timeout stops future dispatches; a failed or incomplete sweep is NOT
 * a verified empty search. No implicit retries are permitted.
 */
export async function executeConsentedOneWaySerpApi({
  enabled = false, db, ownerId, canonicalQueryJson, consentToken,
  consentSigningSecret, serpApiKey, kotlinEngine,
  fetcher = fetch, clock = () => new Date(),
  cryptoApi = globalThis.crypto, timeoutMs = DEFAULT_TIMEOUT_MS,
  maxResponseBytes = MAX_RESPONSE_BYTES,
}) {
  if (enabled !== true) reject("disabled");
  if (typeof serpApiKey !== "string" || serpApiKey.length < 1 ||
      serpApiKey.length > 512 || /\s/.test(serpApiKey) ||
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
      approved.callLimits[0].provider !== "serpapi" ||
      approved.callLimits[0].category !== "base_search" ||
      approved.callLimits[0].count !== calls) {
    // Used consent cannot be reused even for a wrong-category authorization.
    reject("consent_call_limit_mismatch");
  }
  const budget = new D1ProviderHttpBudget(db, clock);
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
        const url = makeRequestUrl(query, origin, destination, pair.outbound, serpApiKey);
        const controller = new AbortController();
        const timer = setTimeout(() => controller.abort(), timeoutMs);
        try {
          const response = await meteredProviderFetch(
            budget, url, { method: "GET", signal: controller.signal }, fetcher,
          );
          const raw = await boundedResponseJson(response, maxResponseBytes);
          const offersReported = countKotlinOffers(kotlinEngine, raw);
          attempts.push({
            provider: "serpapi", origin, destination, outboundDate: pair.outbound,
            state: "complete", offersReported, sourceLabel: "live",
          });
          completed += 1;
        } catch (error) {
          errorCode = error instanceof SerpApiTransportError
            ? error.code : controller.signal.aborted ? "provider_timeout" : "provider_transport";
          attempts.push({
            provider: "serpapi", origin, destination, outboundDate: pair.outbound,
            state: "failed", offersReported: null, error: errorCode,
          });
          break outer;
        } finally {
          clearTimeout(timer);
        }
      }
    }
  }
  return {
    state: errorCode == null ? "complete" : completed ? "partial" : "failed",
    provider: "serpapi",
    plannedCalls: calls,
    completedCalls: completed,
    attemptedCalls: attempts.length,
    attempts,
    errorCode,
    // This is NOT an eligibility-filtered or booking-ready result.
    hasResultSet: false,
  };
}
