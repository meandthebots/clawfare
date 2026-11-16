// Cloudflare D1/Sites adapter. JVM's equivalent is ProviderHttpBudget.
// No user-facing route calls this module yet: wire only after hosted auth,
// consent and per-tenant isolation are implemented.
const ALLOWED_PROVIDERS = new Set(["serpapi", "searchapi", "duffel", "travelpayouts"]);
const ALLOWED_CATEGORIES = new Set(["base_search", "return_selection", "booking_options", "exact_offer", "quota"]);

// Exactly one SQLite write statement. The count check and INSERT occur within
// the same statement, avoiding the read-then-write oversubscription race.
// A missing cap selects zero rows, so the host fails closed.
export const RESERVE_SQL = [
  "INSERT INTO provider_http_reservations",
  "  (id, provider, category, reserved_at, utc_day, utc_month)",
  "SELECT ?, caps.provider, ?, ?, ?, ?",
  "FROM provider_http_limits AS caps",
  "WHERE caps.provider = ?",
  "  AND (SELECT COUNT(*) FROM provider_http_reservations AS r",
  "       WHERE r.provider = caps.provider AND r.utc_day = ?) < caps.daily_cap",
  "  AND (caps.monthly_cap IS NULL OR",
  "       (SELECT COUNT(*) FROM provider_http_reservations AS r",
  "        WHERE r.provider = caps.provider AND r.utc_month = ?) < caps.monthly_cap)",
  "ON CONFLICT(id) DO NOTHING",
  "RETURNING id",
].join("\n");

const COMPLETE_SQL = [
  "UPDATE provider_http_reservations SET",
  "  phase = 'completed', http_status = ?, outcome = ?",
  "WHERE id = ? AND provider = ? AND category = ? AND phase = 'reserved'",
  "RETURNING id",
].join("\n");

function requireAllowed(provider, category) {
  if (!ALLOWED_PROVIDERS.has(provider) || !ALLOWED_CATEGORIES.has(category)) {
    throw new Error("Unknown provider HTTP request; network denied.");
  }
}

export class D1ProviderHttpBudget {
  constructor(database, clock = () => new Date()) {
    if (!database?.prepare) throw new TypeError("D1 database binding is required");
    this.database = database;
    this.clock = clock;
  }

  async reserve(provider, category, reservationId = crypto.randomUUID()) {
    requireAllowed(provider, category);
    if (typeof reservationId !== "string" || reservationId.length < 1 || reservationId.length > 128) {
      throw new Error("Invalid HTTP reservation identifier.");
    }
    const current = this.clock();
    if (!(current instanceof Date) || !Number.isFinite(current.getTime())) {
      throw new Error("Invalid UTC time; network denied.");
    }
    const timestamp = current.toISOString();
    const day = timestamp.slice(0, 10);
    const month = timestamp.slice(0, 7);
    const row = await this.database
      .prepare(RESERVE_SQL)
      .bind(reservationId, category, timestamp, day, month, provider, day, month)
      .first();
    if (!row || row.id !== reservationId) {
      throw new Error("Provider HTTP cap reached, not configured, or reservation already used. No request sent.");
    }
    return reservationId;
  }

  // A completed call still counts; crashes/unknown outcomes also consume a cap.
  async complete(id, provider, category, status, outcome) {
    requireAllowed(provider, category);
    if (!["response", "rate_limited", "transport_error"].includes(outcome)) {
      throw new Error("Unrecognized coarse HTTP outcome.");
    }
    const code = status == null ? null : status;
    if (code != null && (!Number.isInteger(code) || code < 100 || code > 599)) {
      throw new Error("Invalid HTTP response code.");
    }
    const row = await this.database.prepare(COMPLETE_SQL)
      .bind(code, outcome, id, provider, category).first();
    if (!row || row.id !== id) {
      throw new Error("Missing or already completed provider HTTP reservation.");
    }
  }
}

/**
 * Reject unknown hosts, non-HTTPS, non-standard ports, and every order/payment
 * endpoint *before* reserving or dispatching HTTP. Never log a URL or token.
 */
export function classifyProviderRequest(method, rawUrl) {
  let url;
  try {
    url = new URL(rawUrl);
  } catch {
    return null;
  }
  if (url.protocol !== "https:" || url.username || url.password || (url.port && url.port !== "443")) {
    return null;
  }
  const get = method?.toUpperCase() === "GET";
  const post = method?.toUpperCase() === "POST";
  const host = url.hostname.toLowerCase();
  const path = url.pathname;
  const googleCategory = url.searchParams.has("booking_token")
    ? "booking_options"
    : url.searchParams.has("departure_token") ? "return_selection" : "base_search";

  if (host === "serpapi.com" && get && path === "/account.json") {
    return { provider: "serpapi", category: "quota" };
  }
  if (host === "serpapi.com" && get && (path === "/search" || path === "/search.json")) {
    return { provider: "serpapi", category: googleCategory };
  }
  if (host === "www.searchapi.io" && get && path === "/api/v1/me") {
    return { provider: "searchapi", category: "quota" };
  }
  if (host === "www.searchapi.io" && get && path === "/api/v1/search") {
    return { provider: "searchapi", category: googleCategory };
  }
  if (host === "api.travelpayouts.com" && get && path === "/aviasales/v3/prices_for_dates") {
    return { provider: "travelpayouts", category: "base_search" };
  }
  if (host === "api.duffel.com" && post && path === "/air/offer_requests") {
    return { provider: "duffel", category: "base_search" };
  }
  if (host === "api.duffel.com" && get && path.startsWith("/air/offers/") && path.length > "/air/offers/".length) {
    return { provider: "duffel", category: "exact_offer" };
  }
  return null;
}

/**
 * The only permitted provider egress for the future hosted adapter.
 * Defaults to manual redirects so a 3xx cannot forward credentials or bypass
 * the allowlisted endpoint classification. No automatic retry.
 */
export async function meteredProviderFetch(budget, url, init = {}, fetcher = fetch) {
  const method = (init.method || "GET").toUpperCase();
  const route = classifyProviderRequest(method, url);
  if (!route) throw new Error("Unrecognized provider URL; no network request sent.");
  const id = await budget.reserve(route.provider, route.category);
  let response;
  try {
    response = await fetcher(url, { ...init, method, redirect: "manual" });
  } catch (error) {
    await budget.complete(id, route.provider, route.category, null, "transport_error");
    throw error;
  }
  await budget.complete(
    id, route.provider, route.category, response.status,
    response.status === 429 ? "rate_limited" : "response",
  );
  return response;
}
