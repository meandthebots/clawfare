/**
 * D1-only persistence for token-free, tenant-private provider summaries and
 * crash-safe per-route outcomes. No HTTP, secrets or user-supplied owner IDs.
 *
 * Client DTOs must NEVER include raw provider responses, departure tokens,
 * booking tokens or vendor account identifiers. This adapter does not grant
 * search consent or authorize egress.
 */
const OWNER = /^[A-Za-z0-9_-]{8,128}$/;
const DIGEST = /^[a-f0-9]{64}$/;
const NONCE = /^[a-f0-9]{32}$/;
const SLUG = /^[a-z0-9][a-z0-9_-]{0,99}$/;
const IATA = /^[A-Z]{3}$/;
const PROVIDERS = new Set(["serpapi", "searchapi", "travelpayouts", "duffel"]);
const STATES = new Set(["complete", "empty", "partial", "failed"]);
const ERRORS = new Set(["network", "timeout", "provider_error", "quota_exhausted",
  "invalid_response", "incomplete", "unknown"]);
const MAX_OFFERS = 100;
const MAX_CACHE_BYTES = 64 * 1024;
const MAX_QUERY_BYTES = 16 * 1024;
const MAX_TTL_SECONDS = 1800;

export const INSERT_ATTEMPT_SQL = [
  "INSERT INTO hosted_provider_search_attempts",
  " (owner_id, consent_nonce, query_digest, provider, route_digest,",
  " investigation_slug, state, started_at)",
  " VALUES (?, ?, ?, ?, ?, ?, 'attempted', ?)",
  " ON CONFLICT(owner_id, consent_nonce, provider, route_digest) DO NOTHING",
  " RETURNING route_digest",
].join("\n");
export const FINISH_ATTEMPT_SQL = [
  "UPDATE hosted_provider_search_attempts SET",
  " state = ?, attempted_calls = ?, loaded_calls = ?, cached_calls = ?,",
  " returned_offers = ?, eligible_offers = ?, error_kind = ?, finished_at = ?",
  " WHERE owner_id = ? AND consent_nonce = ? AND provider = ? AND route_digest = ?",
  " AND state = 'attempted' RETURNING route_digest",
].join("\n");
export const LIST_ATTEMPTS_SQL = [
  "SELECT provider, route_digest, state, attempted_calls, loaded_calls,",
  " cached_calls, returned_offers, eligible_offers, error_kind,",
  " started_at, finished_at FROM hosted_provider_search_attempts",
  " WHERE owner_id = ? AND consent_nonce = ?",
  " ORDER BY started_at DESC, provider, route_digest LIMIT ?",
].join("\n");
export const WRITE_PROVIDER_CACHE_SQL = [
  "INSERT INTO hosted_provider_offer_cache",
  " (owner_id, provider, cache_digest, parser_version, market,",
  " original_currency, outcome, offers_json, observed_at, expires_at)",
  " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
  " ON CONFLICT(owner_id, provider, cache_digest, parser_version) DO UPDATE SET",
  " market = excluded.market, original_currency = excluded.original_currency,",
  " outcome = excluded.outcome, offers_json = excluded.offers_json,",
  " observed_at = excluded.observed_at, expires_at = excluded.expires_at",
  " WHERE hosted_provider_offer_cache.observed_at < excluded.observed_at",
].join("\n");
export const READ_PROVIDER_CACHE_SQL = [
  "SELECT outcome, offers_json, original_currency, market, observed_at, expires_at",
  " FROM hosted_provider_offer_cache",
  " WHERE owner_id = ? AND provider = ? AND cache_digest = ?",
  " AND parser_version = ? AND expires_at > ?",
].join("\n");

function field(value, pattern, label) {
  if (typeof value !== "string" || !pattern.test(value)) {
    throw new TypeError("Invalid " + label);
  }
  return value;
}
function providerId(value) {
  if (!PROVIDERS.has(value)) throw new TypeError("Unsupported provider");
  return value;
}
function integer(value, min, max, label) {
  if (!Number.isSafeInteger(value) || value < min || value > max) {
    throw new TypeError("Invalid " + label);
  }
  return value;
}
function utc(clock) {
  const now = clock();
  if (!(now instanceof Date) || !Number.isFinite(now.getTime())) {
    throw new TypeError("Invalid server clock");
  }
  return now.toISOString();
}
function cleanText(value, label, max = 80, allowEmpty = false) {
  if (typeof value !== "string" || value.length > max ||
      (!allowEmpty && value.trim().length === 0) ||
      /[\x00-\x1f\x7f]/.test(value) ||
      /(?:api[_-]?key|access[_-]?token|departure[_-]?token|booking[_-]?token|bearer\s|https?:\/\/|duffel_(?:test|live)_)/i.test(value)) {
    throw new TypeError("Unsafe provider " + label);
  }
  return value;
}
function optionalText(value, label, max = 80) {
  return value == null ? null : cleanText(value, label, max);
}
function parseOffer(raw) {
  if (!raw || typeof raw !== "object" || Array.isArray(raw) ||
      !Array.isArray(raw.legs) || raw.legs.length < 1 || raw.legs.length > 12) {
    throw new TypeError("Invalid provider offer legs");
  }
  const price = raw.price == null ? null : raw.price;
  if (price !== null && (typeof price !== "number" ||
      !Number.isFinite(price) || price <= 0 || price > 100000000)) {
    throw new TypeError("Invalid original provider price");
  }
  const legs = raw.legs.map(leg => {
    if (!leg || typeof leg !== "object" || Array.isArray(leg)) {
      throw new TypeError("Invalid flight segment");
    }
    return {
      flightNumber: cleanText(leg.flightNumber, "flight number", 20),
      airline: cleanText(leg.airline, "marketing carrier", 80, true),
      airlineCode: cleanText(leg.airlineCode, "airline code", 4, true),
      operatingCarrier: cleanText(leg.operatingCarrier, "operating carrier", 80, true),
      operatingCarrierVerified: leg.operatingCarrierVerified === true,
      from: field(leg.from, IATA, "departure IATA code"),
      to: field(leg.to, IATA, "arrival IATA code"),
      departDate: field(leg.departDate, /^20[2-9][0-9]-\d\d-\d\d$/, "departure date"),
      arriveDate: field(leg.arriveDate, /^20[2-9][0-9]-\d\d-\d\d$/, "arrival date"),
      departTime: field(leg.departTime, /^\d\d:\d\d$/, "departure time"),
      arriveTime: field(leg.arriveTime, /^\d\d:\d\d$/, "arrival time"),
      durationMinutes: leg.durationMinutes == null
        ? null : integer(leg.durationMinutes, 0, 14400, "flight duration"),
      cabin: optionalText(leg.cabin, "cabin", 48),
      aircraft: optionalText(leg.aircraft, "aircraft", 80),
    };
  });
  const layovers = raw.layovers ?? [];
  if (!Array.isArray(layovers) || layovers.length > 11) {
    throw new TypeError("Invalid layover count");
  }
  const sanitizedLayovers = layovers.map(layover => {
    if (!layover || typeof layover !== "object" || Array.isArray(layover)) {
      throw new TypeError("Invalid layover");
    }
    return {
      airport: cleanText(layover.airport, "layover airport", 80, true),
      minutes: integer(layover.minutes, 0, 14400, "layover duration"),
    };
  });
  return {
    price, legs, layovers: sanitizedLayovers,
    durationMinutes: raw.durationMinutes == null
      ? null : integer(raw.durationMinutes, 0, 28800, "itinerary duration"),
    fareBrand: optionalText(raw.fareBrand, "fare brand", 80),
    // Intentionally no departureToken, bookingToken, seller options, URL,
    // credential, raw body or arbitrary provider fields.
  };
}
function parsedOffers(offers) {
  if (!Array.isArray(offers) || offers.length > MAX_OFFERS) {
    throw new TypeError("Expected a bounded collection of parsed offers");
  }
  const clean = offers.map(parseOffer);
  const json = JSON.stringify(clean);
  if (new TextEncoder().encode(json).byteLength > MAX_CACHE_BYTES) {
    throw new RangeError("Provider summary exceeds D1 cache limit");
  }
  return { clean, json };
}
function getKeyParts({ ownerId, provider, cacheDigest, parserVersion }) {
  return [
    field(ownerId, OWNER, "authenticated owner key"),
    providerId(provider),
    field(cacheDigest, DIGEST, "provider cache digest"),
    integer(parserVersion, 1, 100000, "parser revision"),
  ];
}
function parseCanonicalQuery(value) {
  if (typeof value !== "string" ||
      new TextEncoder().encode(value).byteLength > MAX_QUERY_BYTES) {
    throw new TypeError("Invalid canonical Kotlin query");
  }
  let query;
  try { query = JSON.parse(value); } catch { throw new TypeError("Malformed canonical Kotlin query"); }
  if (!query || typeof query !== "object" || Array.isArray(query) ||
      !Array.isArray(query.origins) || !Array.isArray(query.destinations)) {
    throw new TypeError("Expected canonical Kotlin query fields");
  }
  return value;
}

/** Only a deterministic, token-free BASE call can be cached persistently. */
export async function baseCallCacheDigest({
  canonicalQueryJson, provider, params, parserVersion,
}, cryptoApi = globalThis.crypto) {
  const query = parseCanonicalQuery(canonicalQueryJson);
  providerId(provider);
  integer(parserVersion, 1, 100000, "parser revision");
  if (!params || typeof params !== "object" || Array.isArray(params) ||
      Object.keys(params).length > 32) throw new TypeError("Invalid provider call params");
  const fields = Object.entries(params).sort(([a], [b]) => a.localeCompare(b)).map(([key, value]) => {
    if (!/^[a-z_]{1,40}$/.test(key) || /token|key|secret|auth|cookie/i.test(key)) {
      throw new TypeError("Only token-free base search parameters may be cached");
    }
    return [key, cleanText(value, "cache parameter", 100, true)];
  });
  const text = JSON.stringify(["base", parserVersion, provider, query, fields]);
  const hash = await cryptoApi.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(hash)].map(byte => byte.toString(16).padStart(2, "0")).join("");
}

/** This data access layer never performs provider I/O or issues consent. */
export class D1ProviderPersistence {
  constructor(db, { clock = () => new Date(), cryptoApi = globalThis.crypto } = {}) {
    if (!db?.prepare || !cryptoApi?.subtle) throw new TypeError("D1 and WebCrypto required");
    this.db = db;
    this.clock = clock;
  }

  async cacheBaseOffers({
    ownerId, provider, cacheDigest, parserVersion, market, originalCurrency,
    offers, recognizedResponse, cachingPermitted = false, ttlSeconds = 300,
  }) {
    if (cachingPermitted !== true) throw new Error("Provider caching permission not established");
    const parts = getKeyParts({ ownerId, provider, cacheDigest, parserVersion });
    field(market, /^[a-z]{2}$/, "provider market");
    field(originalCurrency, /^[A-Z]{3}$/, "original currency");
    integer(ttlSeconds, 1, MAX_TTL_SECONDS, "provider cache TTL");
    if (recognizedResponse !== true) throw new TypeError("Unrecognized/partial responses cannot be cached");
    const { clean, json } = parsedOffers(offers);
    const stamp = utc(this.clock);
    const expires = new Date(Date.parse(stamp) + ttlSeconds * 1000).toISOString();
    const outcome = clean.length ? "offers" : "verified_empty";
    await this.db.prepare(WRITE_PROVIDER_CACHE_SQL)
      .bind(...parts, market, originalCurrency, outcome, json, stamp, expires).run();
    return { outcome, observedAt: stamp, expiresAt: expires, count: clean.length };
  }

  async readBaseOffers({ ownerId, provider, cacheDigest, parserVersion }) {
    const parts = getKeyParts({ ownerId, provider, cacheDigest, parserVersion });
    const row = await this.db.prepare(READ_PROVIDER_CACHE_SQL)
      .bind(...parts, utc(this.clock)).first();
    if (!row) return null;
    if (row.outcome !== "offers" && row.outcome !== "verified_empty") {
      throw new TypeError("Corrupt provider cache outcome");
    }
    let raw;
    try { raw = JSON.parse(row.offers_json); } catch { throw new TypeError("Corrupt D1 offers JSON"); }
    const { clean } = parsedOffers(raw);
    if ((row.outcome === "offers") !== (clean.length > 0)) {
      throw new TypeError("Corrupt provider offer count");
    }
    field(row.original_currency, /^[A-Z]{3}$/, "stored original currency");
    field(row.market, /^[a-z]{2}$/, "stored market");
    const observed = Date.parse(row.observed_at);
    const expires = Date.parse(row.expires_at);
    if (!Number.isFinite(observed) || !Number.isFinite(expires) ||
        expires <= observed || expires <= Date.parse(utc(this.clock))) {
      return null;
    }
    return {
      sourceLabel: "cached", livePrice: false, outcome: row.outcome,
      offers: clean, observedAt: row.observed_at, expiresAt: row.expires_at,
      originalCurrency: row.original_currency, market: row.market,
    };
  }

  async beginAttempt({
    ownerId, consentNonce, queryDigest, provider, routeDigest, investigationSlug = null,
  }) {
    field(ownerId, OWNER, "authenticated owner key");
    field(consentNonce, NONCE, "consent nonce");
    field(queryDigest, DIGEST, "query digest");
    providerId(provider);
    field(routeDigest, DIGEST, "route digest");
    if (investigationSlug !== null) field(investigationSlug, SLUG, "investigation slug");
    const row = await this.db.prepare(INSERT_ATTEMPT_SQL)
      .bind(ownerId, consentNonce, queryDigest, provider, routeDigest,
        investigationSlug, utc(this.clock)).first();
    if (!row || row.route_digest !== routeDigest) {
      throw new Error("Route attempt already recorded; refusing duplicate provider dispatch");
    }
    return { state: "attempted", consentNonce, routeDigest };
  }

  async finishAttempt({
    ownerId, consentNonce, provider, routeDigest, state,
    attemptedCalls, loadedCalls, cachedCalls, returnedOffers, eligibleOffers,
    errorKind = null,
  }) {
    field(ownerId, OWNER, "authenticated owner key");
    field(consentNonce, NONCE, "consent nonce");
    providerId(provider);
    field(routeDigest, DIGEST, "route digest");
    if (!STATES.has(state)) throw new TypeError("Invalid terminal attempt state");
    integer(attemptedCalls, 0, 16, "attempted calls");
    integer(loadedCalls, 0, attemptedCalls, "loaded calls");
    integer(cachedCalls, 0, 16, "cached calls");
    integer(returnedOffers, 0, 10000, "returned offers");
    integer(eligibleOffers, 0, returnedOffers, "eligible offers");
    if (errorKind !== null && !ERRORS.has(errorKind) ||
        state === "failed" && errorKind === null ||
        (state === "complete" || state === "empty") && errorKind !== null ||
        state === "empty" && returnedOffers !== 0 ||
        state === "complete" && returnedOffers === 0) {
      throw new TypeError("Invalid outcome/evidence combination");
    }
    const row = await this.db.prepare(FINISH_ATTEMPT_SQL)
      .bind(state, attemptedCalls, loadedCalls, cachedCalls, returnedOffers,
        eligibleOffers, errorKind, utc(this.clock),
        ownerId, consentNonce, provider, routeDigest).first();
    if (!row || row.route_digest !== routeDigest) {
      throw new Error("Route was not pending; refusing duplicate or cross-owner completion");
    }
    return { state, returnedOffers, eligibleOffers };
  }

  async listAttempts(ownerId, consentNonce, limit = 50) {
    field(ownerId, OWNER, "authenticated owner key");
    field(consentNonce, NONCE, "consent nonce");
    integer(limit, 1, 100, "attempt pagination limit");
    const response = await this.db.prepare(LIST_ATTEMPTS_SQL)
      .bind(ownerId, consentNonce, limit).all();
    return (response.results || []).map(row => ({
      provider: providerId(row.provider),
      routeDigest: field(row.route_digest, DIGEST, "route digest"),
      state: row.state, attemptedCalls: row.attempted_calls,
      loadedCalls: row.loaded_calls, cachedCalls: row.cached_calls,
      returnedOffers: row.returned_offers, eligibleOffers: row.eligible_offers,
      errorKind: row.error_kind, startedAt: row.started_at, finishedAt: row.finished_at,
    }));
  }
}
