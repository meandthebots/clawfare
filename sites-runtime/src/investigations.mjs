// Hosted persistence boundary. Caller MUST derive ownerId from verified
// authentication, not from a user-supplied header, query string, or JSON body.
// No HTTP routes are exposed from this module.
const OWNER = /^[A-Za-z0-9_-]{8,128}$/;
const SLUG = /^[a-z0-9][a-z0-9_-]{0,99}$/;
const ITINERARY = /^[A-Za-z0-9._:-]{1,128}$/;
const PROVIDER = /^[a-z0-9_-]{1,64}$/;
const CURRENCY = /^[A-Z]{3}$/;
const DECIMAL = /^(?:0|[1-9][0-9]*)(?:\.[0-9]{1,6})?$/;
const LABELS = new Set(["live", "cached", "mock", "sample", "manual"]);
export const TRACKED_PREFIX = "tracked-";
const MAX_TRACKABLE_AGE_MS = 2 * 60 * 60 * 1000;
const MAX_SNAPSHOT_BYTES = 512 * 1024;

export const UPSERT_INVESTIGATION_SQL = [
  "INSERT INTO investigation_snapshots",
  "  (owner_id, slug, result_json, title, fetched_at, updated_at)",
  "VALUES (?, ?, ?, ?, ?, ?)",
  "ON CONFLICT(owner_id, slug) DO UPDATE SET",
  "  result_json = excluded.result_json,",
  "  title = excluded.title,",
  "  fetched_at = excluded.fetched_at,",
  "  updated_at = excluded.updated_at",
].join("\n");

const INSERT_TRACKED_SNAPSHOT_SQL = [
  "INSERT INTO investigation_snapshots",
  "  (owner_id, slug, result_json, title, fetched_at, updated_at)",
  "VALUES (?, ?, ?, ?, ?, ?)",
  "ON CONFLICT(owner_id, slug) DO UPDATE SET updated_at = excluded.updated_at",
].join("\n");

export const GET_INVESTIGATION_SQL =
  "SELECT result_json FROM investigation_snapshots WHERE owner_id = ? AND slug = ?";

export const LIST_INVESTIGATIONS_SQL = [
  "SELECT slug, title, fetched_at, updated_at",
  "FROM investigation_snapshots WHERE owner_id = ?",
  "ORDER BY updated_at DESC, slug LIMIT ?",
].join("\n");

export const INSERT_PRICE_OBSERVATION_SQL = [
  "INSERT INTO fare_price_observations",
  "  (owner_id, investigation_slug, observation_id, itinerary_id, provider,",
  "   source_label, live_price, captured_at, original_currency, amount_decimal)",
  "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
  "ON CONFLICT(owner_id, investigation_slug, observation_id) DO NOTHING",
].join("\n");

export const LIST_PRICE_OBSERVATIONS_SQL = [
  "SELECT observation_id, itinerary_id, provider, source_label, live_price,",
  "  captured_at, original_currency, amount_decimal",
  "FROM fare_price_observations",
  "WHERE owner_id = ? AND investigation_slug = ? AND itinerary_id = ?",
  "ORDER BY captured_at DESC, observation_id LIMIT ?",
].join("\n");

function assertKey(value, regex, label) {
  if (typeof value !== "string" || !regex.test(value)) {
    throw new TypeError("Invalid " + label);
  }
  return value;
}

function boundedLimit(limit, maximum = 50) {
  if (!Number.isInteger(limit) || limit < 1 || limit > maximum) {
    throw new RangeError("Invalid pagination limit");
  }
  return limit;
}

function utcTimestamp(value) {
  if (typeof value !== "string" ||
      !/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,3})?Z$/.test(value) ||
      !Number.isFinite(Date.parse(value))) {
    throw new TypeError("Invalid UTC capture timestamp");
  }
  return value;
}

function nowIso(clock) {
  const now = clock();
  if (!(now instanceof Date) || !Number.isFinite(now.getTime())) {
    throw new TypeError("Invalid server UTC clock");
  }
  return now.toISOString();
}

/**
 * ResultSet JSON is produced by the shared Kotlin serializer. The adapter
 * enforces shape/size and disallows the StoredResult wrapper containing opaque
 * provider booking tokens. This is not a replacement for Kotlin validation.
 */
function checkedSnapshot(result) {
  if (!result || typeof result !== "object" || Array.isArray(result) ||
      typeof result.id !== "string" || !SLUG.test(result.id) ||
      typeof result.title !== "string" || result.title.length > 200 ||
      !Array.isArray(result.itineraries) ||
      Object.prototype.hasOwnProperty.call(result, "tokens")) {
    throw new TypeError("Expected a token-free Kotlin ResultSet");
  }
  const payload = JSON.stringify(result);
  // A ResultSet may contain booking URLs, but it must never carry private
  // provider response tokens (even in nested objects or URL query params).
  if (/["'](?:departureToken|bookingToken|apiKey|accessToken|tokens)["']\s*:|(?:api[_-]?key|access[_-]?token|departure[_-]?token|booking[_-]?token)=/i.test(payload)) {
    throw new TypeError("Provider tokens are forbidden in saved result snapshots");
  }
  if (!payload || new TextEncoder().encode(payload).length > MAX_SNAPSHOT_BYTES) {
    throw new RangeError("Investigation snapshot exceeds hosting limit");
  }
  return payload;
}

async function stableObservationId(fields, cryptoApi) {
  const canonical = JSON.stringify(fields);
  const digest = await cryptoApi.subtle.digest("SHA-256", new TextEncoder().encode(canonical));
  return Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, "0")).join("");
}

async function priceObservationParams(ownerId, slug, observation, cryptoApi) {
  assertKey(ownerId, OWNER, "authenticated owner ID");
  assertKey(slug, SLUG, "investigation slug");
  if (!observation || typeof observation !== "object" || Array.isArray(observation)) {
    throw new TypeError("Invalid price observation");
  }
  const itineraryId = assertKey(observation.itineraryId, ITINERARY, "itinerary ID");
  const provider = assertKey(observation.provider, PROVIDER, "provider");
  const label = observation.sourceLabel;
  if (!LABELS.has(label) || typeof observation.livePrice !== "boolean" ||
      (label !== "live" && observation.livePrice)) {
    throw new TypeError("Invalid fare provenance");
  }
  const capturedAt = utcTimestamp(observation.capturedAt);
  const currency = assertKey(observation.originalCurrency, CURRENCY, "original currency");
  const amount = observation.amountDecimal;
  if (typeof amount !== "string" || !DECIMAL.test(amount) ||
      !Number.isFinite(Number(amount)) || Number(amount) <= 0) {
    throw new TypeError("Invalid original fare amount");
  }
  const fields = [ownerId, slug, itineraryId, provider, label,
    observation.livePrice, capturedAt, currency, amount];
  const id = await stableObservationId(fields, cryptoApi);
  return [ownerId, slug, id, itineraryId, provider, label, observation.livePrice ? 1 : 0,
    capturedAt, currency, amount];
}

export class D1Investigations {
  constructor(database, { clock = () => new Date(), cryptoApi = globalThis.crypto } = {}) {
    if (!database?.prepare) throw new TypeError("D1 database binding required");
    this.db = database;
    this.clock = clock;
    this.crypto = cryptoApi;
  }

  async saveSnapshot(ownerId, result) {
    assertKey(ownerId, OWNER, "authenticated owner ID");
    const payload = checkedSnapshot(result);
    if (result.id.startsWith(TRACKED_PREFIX)) throw new TypeError("Reserved investigation ID");
    const updatedAt = nowIso(this.clock);
    const fetchedAt = result.fetchedAt == null ? null : utcTimestamp(result.fetchedAt);
    await this.db.prepare(UPSERT_INVESTIGATION_SQL)
      .bind(ownerId, result.id, payload, result.title, fetchedAt, updatedAt)
      .run();
    return result.id;
  }

  async loadSnapshot(ownerId, slug) {
    assertKey(ownerId, OWNER, "authenticated owner ID");
    assertKey(slug, SLUG, "investigation slug");
    const row = await this.db.prepare(GET_INVESTIGATION_SQL).bind(ownerId, slug).first();
    return row ? JSON.parse(row.result_json) : null;
  }

  async listSnapshots(ownerId, limit = 50) {
    assertKey(ownerId, OWNER, "authenticated owner ID");
    boundedLimit(limit);
    const result = await this.db.prepare(LIST_INVESTIGATIONS_SQL).bind(ownerId, limit).all();
    return result.results || [];
  }

  /**
   * Save a Kotlin ResultSet and its original-currency price observations as
   * one D1 transaction. A failure cannot leave an orphaned partial history.
   * Repeated observations have stable IDs and are inserted once.
   */
  async saveResultWithPrices(ownerId, result, observations = []) {
    if (typeof result?.id === "string" && result.id.startsWith(TRACKED_PREFIX)) throw new TypeError("Reserved investigation ID");
    return this.#saveResultWithPrices(ownerId, result, observations, UPSERT_INVESTIGATION_SQL);
  }

  async #saveResultWithPrices(ownerId, result, observations, snapshotSql) {
    assertKey(ownerId, OWNER, "authenticated owner ID");
    const payload = checkedSnapshot(result);
    if (!Array.isArray(observations) || observations.length > 200) {
      throw new RangeError("Invalid bounded price observation batch");
    }
    if (typeof this.db.batch !== "function") {
      throw new TypeError("D1 transactional batch required to save result with prices");
    }
    const updatedAt = nowIso(this.clock);
    const fetchedAt = result.fetchedAt == null ? null : utcTimestamp(result.fetchedAt);
    // Fully parse the entire input before assembling or executing writes.
    const priceRows = [];
    for (const observation of observations) {
      priceRows.push(await priceObservationParams(ownerId, result.id, observation, this.crypto));
    }
    const statements = [
      this.db.prepare(snapshotSql)
        .bind(ownerId, result.id, payload, result.title, fetchedAt, updatedAt),
      ...priceRows.map(row => this.db.prepare(INSERT_PRICE_OBSERVATION_SQL).bind(...row)),
    ];
    await this.db.batch(statements);
    return { id: result.id, observationIds: priceRows.map(row => row[2]) };
  }

  async trackFare(ownerId, sourceId, itineraryId, tripSlug) {
    assertKey(itineraryId, ITINERARY, "itinerary ID");
    assertKey(tripSlug, SLUG, "trip slug");
    const source = await this.loadSnapshot(ownerId, sourceId);
    const fare = source?.itineraries.find(row => row.id === itineraryId);
    if (!fare || !source.query || source.scenarioLabel === "florida" || sourceId.startsWith(TRACKED_PREFIX)) {
      return { status: 404, error: "Saved search itinerary not found." };
    }
    const observed = Date.parse(fare.fetchedAt);
    const now = this.clock().getTime();
    const age = now - observed;
    const expiry = Date.parse(fare.refundEvidence?.expiresAt);
    if (fare.sourceLabel !== "live" || fare.livePrice !== true || fare.returnMissing ||
        !Number.isFinite(age) || age < 0 || age > MAX_TRACKABLE_AGE_MS ||
        (fare.refundEvidence != null && !(expiry > now))) {
      return { status: 409, error: "Save a complete, recent live fare from a new search." };
    }
    const observations = await this.priceHistory(ownerId, sourceId, itineraryId);
    const quote = observations.find(row => Date.parse(row.captured_at) === observed &&
      row.live_price === 1 && row.source_label === "live" &&
      row.provider === fare.source && row.original_currency === fare.priceCurrency);
    if (!quote) return { status: 409, error: "The original provider price was not saved." };

    // Flight-family IDs incorporate segments and fare brand. Partition further
    // by the commercial and flight details that must agree across searches.
    const identity = [
      tripSlug, itineraryId, quote.provider, source.query.market, quote.original_currency,
      fare.passengers, source.checkedBags, fare.cabinKey, fare.farePolicyKey,
      fare.outbound, fare.returning, source.query.tripType,
    ];
    const slug = TRACKED_PREFIX + (await stableObservationId(identity, this.crypto)).slice(0, 40);
    await this.#saveResultWithPrices(ownerId, {
      id: slug,
      title: `${tripSlug} · ${fare.origin} → ${fare.destination} · ${fare.outboundDate}`,
      subtitle: `${fare.origin} → ${fare.destination} · ${fare.outboundDate}`,
      fetchedAt: fare.fetchedAt,
      currency: source.currency,
      checkedBags: source.checkedBags,
      passengers: fare.passengers,
      itineraries: [{ ...fare, prevPrice: null }],
      addons: [], cells: [], vendors: [], sellerOptions: [],
      fx: source.fx,
      query: source.query,
      warnings: ["Tracking preserves the original fare snapshot. Check price history for later saved observations."],
    }, [{
      itineraryId,
      provider: quote.provider,
      sourceLabel: quote.source_label,
      livePrice: true,
      capturedAt: quote.captured_at,
      originalCurrency: quote.original_currency,
      amountDecimal: quote.amount_decimal,
    }], INSERT_TRACKED_SNAPSHOT_SQL);
    return { slug };
  }

  async recordPrice(ownerId, slug, observation) {
    const params = await priceObservationParams(ownerId, slug, observation, this.crypto);
    await this.db.prepare(INSERT_PRICE_OBSERVATION_SQL).bind(...params).run();
    return params[2];
  }

  async priceHistory(ownerId, slug, itineraryId, limit = 50) {
    assertKey(ownerId, OWNER, "authenticated owner ID");
    assertKey(slug, SLUG, "investigation slug");
    assertKey(itineraryId, ITINERARY, "itinerary ID");
    boundedLimit(limit);
    const rows = await this.db.prepare(LIST_PRICE_OBSERVATIONS_SQL)
      .bind(ownerId, slug, itineraryId, limit).all();
    return rows.results || [];
  }
}
