import { limitedBody } from "./search-estimate.mjs";
import { requireSameOrigin, SearchConsentError } from "./search-consent.mjs";

const EXPORT_VERSION = "clawfare-saved-v1";
const MAX_SELECTION = 200;
const MAX_REPORT_BYTES = 512 * 1024;
const MAX_EXPORTS_PER_MINUTE = 10;
const ITINERARY_ID = /^[A-Za-z0-9._:-]{1,128}$/;
const SOURCE_URLS = Object.freeze({
  serpapi: "https://serpapi.com/google-flights-api",
  searchapi: "https://www.searchapi.io/docs/google-flights-api",
  travelpayouts: "https://www.travelpayouts.com/",
  duffel: "https://duffel.com/",
});
const RESERVE_EXPORT = `
  INSERT INTO hosted_export_limits (owner_id, window_start, request_count)
  VALUES (?, ?, 1)
  ON CONFLICT (owner_id) DO UPDATE SET
    window_start = excluded.window_start,
    request_count = CASE WHEN window_start < excluded.window_start THEN 1 ELSE request_count + 1 END
  WHERE window_start < excluded.window_start
     OR (window_start = excluded.window_start AND request_count < ?)
  RETURNING request_count
`;

function jsonError(error, status, headers = {}) {
  return Response.json({ error }, {
    status,
    headers: { "cache-control": "private, no-store", "x-content-type-options": "nosniff", ...headers },
  });
}

function validSelection(body) {
  return Object.keys(body).length === 2 &&
    (body.kind === "itineraries" || body.kind === "seller_options") &&
    Array.isArray(body.itineraryIds) && body.itineraryIds.length <= MAX_SELECTION &&
    body.itineraryIds.every(id => typeof id === "string" && ITINERARY_ID.test(id)) &&
    new Set(body.itineraryIds).size === body.itineraryIds.length;
}

const fields = [
  "export_version", "row_kind", "search_title", "search_observed_at", "origins_requested",
  "destinations_requested", "outbound_from", "outbound_to", "return_from", "return_to", "trip_type",
  "market", "passengers", "requested_checked_bags", "requested_cabin", "requested_short_haul_cabin",
  "strict_cabin", "strict_operating_carrier", "nonstop_only", "excluded_airlines",
  "excluded_transit_airports", "excluded_transit_countries", "requested_providers",
  "coverage_state", "planned_routes", "completed_routes", "eligible_itineraries", "fx_date",
  "provider", "source_url", "source_mode", "quote_observed_at", "origin", "destination", "outbound_date",
  "return_date", "carriers", "fare_brand", "seller", "original_currency", "original_price_per_person",
  "original_price_party_total", "gbp_price_per_person", "gbp_price_party_total", "gbp_per_original_unit", "baggage",
  "refund_evidence", "limitations",
];

function number(value) {
  return Number.isFinite(value) && value >= 0 ? value : "";
}

function data(result, fare, seller) {
  const q = result.query ?? {};
  const provider = seller?.provider || fare?.source || "";
  const currency = seller?.currency || fare?.priceCurrency || "";
  const amount = seller?.priceTotal;
  const gbpPrice = seller ? "" : number(fare?.pricePerPerson);
  const fxRate = result.fx?.perGbp?.[currency];
  const passengers = seller?.passengers ?? fare?.passengers ?? result.passengers;
  const originalUnit = number(fare?.priceOriginal ?? (currency === "GBP" ? gbpPrice : null));
  const route = [fare?.outbound, fare?.returning].flatMap(dir => dir?.legs || []);
  const carriers = [...new Set(route.map(leg => leg.operatingCarrier || leg.airline).filter(Boolean))];
  return {
    export_version: EXPORT_VERSION,
    row_kind: seller ? "seller" : fare ? "itinerary" : "search",
    search_title: result.title,
    search_observed_at: result.fetchedAt,
    origins_requested: q.origins?.join(" "),
    destinations_requested: q.destinations?.join(" "),
    outbound_from: q.outboundFrom,
    outbound_to: q.outboundTo,
    return_from: q.returnFrom,
    return_to: q.returnTo,
    trip_type: q.tripType,
    market: seller?.market || (q.providers?.[0] === "duffel" ? "provider-defined" : q.market),
    passengers,
    requested_checked_bags: result.checkedBags,
    requested_cabin: q.cabin,
    requested_short_haul_cabin: q.cabinShortHaul,
    strict_cabin: q.strictPerLegCabin,
    strict_operating_carrier: q.strictOperatingCarrier,
    nonstop_only: q.nonstopOnly,
    excluded_airlines: q.excludedAirlines?.join(" "),
    excluded_transit_airports: q.excludedTransitAirports?.join(" "),
    excluded_transit_countries: q.excludedTransitCountries?.join(" "),
    requested_providers: q.providers?.join(" "),
    coverage_state: result.coverage?.state,
    planned_routes: result.coverage?.routes.length,
    completed_routes: result.coverage?.routes.filter(route => ["empty", "complete"].includes(route.state)).length,
    eligible_itineraries: result.coverage?.eligibleItineraries,
    fx_date: seller || gbpPrice === "" ? "" : result.fx?.asOf,
    provider,
    source_url: Object.hasOwn(SOURCE_URLS, provider) ? SOURCE_URLS[provider] : "",
    source_mode: seller ? "Seller observation" : fare?.sourceLabel,
    quote_observed_at: seller?.observedAt || fare?.fetchedAt,
    origin: fare?.origin,
    destination: fare?.destination,
    outbound_date: fare?.outboundDate,
    return_date: fare?.returnDate,
    carriers: carriers.join(" / ") || fare?.airlineSummary,
    fare_brand: seller?.fareBrand || fare?.fareBrand,
    seller: seller?.seller,
    original_currency: currency,
    original_price_per_person: seller ? "" : originalUnit,
    original_price_party_total: seller ? number(amount)
      : originalUnit === "" ? "" : number(Number((originalUnit * passengers).toFixed(2))),
    gbp_price_per_person: gbpPrice,
    gbp_price_party_total: gbpPrice === "" ? "" : number(Number((gbpPrice * passengers).toFixed(2))),
    gbp_per_original_unit: seller || gbpPrice === "" || !(fxRate > 0) ? "" : 1 / fxRate,
    baggage: seller?.bags || "Allowance and fees unverified",
    refund_evidence: "Verify cash refund terms with the seller",
    limitations: [
      "Saved observations; availability and fees can change.",
      result.warnings?.length ? "The saved search reported warnings or incomplete coverage." : "",
      result.coverage ? "Coverage is limited to the planned provider searches." : "Route coverage was not saved.",
      fare?.returnMissing ? "Return quote missing." : "",
      seller?.splitTickets ? "Separate tickets." : "",
    ].filter(Boolean).join(" "),
  };
}

function csvValue(value) {
  const text = String(value ?? "");
  const safe = typeof value === "string" && (/^[\t\r\n]/.test(text) || /^[\s\uFEFF]*[=+\-@]/u.test(text))
    ? "'" + text : text;
  return '"' + safe.replaceAll('"', '""') + '"';
}

function escapeHtml(value) {
  return String(value ?? "").replace(/[&<>"']/g, char => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
  })[char]);
}

function asCsv(rows) {
  return [fields.join(","), ...rows.map(row => fields.map(field => csvValue(row[field])).join(","))].join("\r\n") + "\r\n";
}

function asHtml(result, rows, kind) {
  const title = escapeHtml(result.title);
  const label = kind === "seller_options" ? "seller offers" : "itineraries";
  const rowFieldStart = fields.indexOf("provider");
  const terms = (row, keys) => "<dl>" + keys.filter(field => row[field] != null && row[field] !== "")
    .map(field => {
      const value = field === "source_url"
        ? `<a href="${escapeHtml(row.source_url)}" rel="noreferrer">Provider documentation</a>`
        : escapeHtml(row[field]);
      return `<dt>${field.replaceAll("_", " ")}</dt><dd>${value}</dd>`;
    }).join("") + "</dl>";
  return `<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">` +
    `<meta name="clawfare-export-version" content="${EXPORT_VERSION}"><title>${title}</title>` +
    "<style>body{font:16px system-ui;margin:2rem auto;padding:0 1rem;max-width:56rem}dt{font-weight:600}dd{margin:0 0 .5em}article{border-top:1px solid #aaa}</style>" +
    `<h1>${title}</h1><p>${rows.length} selected ${label}. Saved observations; availability and fees can change.</p>` +
    `<h2>Search criteria</h2>${terms(data(result), fields.slice(3, rowFieldStart))}` +
    rows.map(row => `<article><h2>${escapeHtml(row.origin)} → ${escapeHtml(row.destination)} · ${escapeHtml(row.outbound_date)}</h2>` +
      terms(row, ["market", "passengers", "fx_date", ...fields.slice(rowFieldStart)]) + "</article>").join("") +
    "</html>";
}

export async function exportSavedSearch(request, { db, store, ownerId, slug, format }) {
  try {
    requireSameOrigin(request);
  } catch (error) {
    if (error instanceof SearchConsentError) return jsonError("Same-origin exports required.", 403);
    throw error;
  }
  if (!/^application\/json(?:\s*;|$)/i.test(request.headers.get("content-type") || "")) {
    return jsonError("Content-Type: application/json required.", 415);
  }
  const input = await limitedBody(request);
  if (input.error) return jsonError("Invalid export request body.", input.status);
  if (!validSelection(input.parsed)) return jsonError("Provide a valid list of selected itinerary IDs.", 400);
  const windowStart = Math.floor(Date.now() / 60_000);
  let reservation;
  let result;
  try {
    reservation = await db.prepare(RESERVE_EXPORT).bind(ownerId, windowStart, MAX_EXPORTS_PER_MINUTE).first();
    if (reservation) result = await store.loadSnapshot(ownerId, slug);
  } catch {
    return jsonError("Saved export storage is unavailable.", 503);
  }
  if (!reservation) return jsonError("Export limit reached. Try again in a minute.", 429, { "retry-after": "60" });
  if (!result?.query || result.scenarioLabel) return jsonError("Saved search not found.", 404);

  const { kind, itineraryIds } = input.parsed;
  const indexed = new Map(result.itineraries.map(fare => [fare.id, fare]));
  if (itineraryIds.some(id => !indexed.has(id))) return jsonError("Selection does not match the saved search.", 400);
  const rows = kind === "seller_options"
    ? (result.sellerOptions || []).filter(seller => itineraryIds.includes(seller.itineraryId))
      .map(seller => data(result, indexed.get(seller.itineraryId), seller))
    : itineraryIds.map(id => data(result, indexed.get(id)));
  if (rows.length > MAX_SELECTION) return jsonError("Reduce the number of offers in the export.", 413);
  const report = format === "csv" ? asCsv(rows.length ? rows : [data(result)]) : asHtml(result, rows, kind);
  if (new TextEncoder().encode(report).length > MAX_REPORT_BYTES) return jsonError("The export is too large.", 413);
  return new Response(report, {
    headers: {
      "content-type": format === "csv" ? "text/csv; charset=utf-8" : "text/html; charset=utf-8",
      "content-disposition": `attachment; filename="clawfare-${slug}.${format}"`,
      "cache-control": "private, no-store",
      "content-security-policy": "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'; sandbox",
      "x-content-type-options": "nosniff",
    },
  });
}
