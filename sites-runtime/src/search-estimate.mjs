/**
 * Zero-egress search estimate for the hosted read-only Site.
 *
 * Route planning, calendar calculations, and provider call upper bounds are
 * delegated exclusively to the compiled Kotlin/JS fare-core bridge.
 * This endpoint cannot grant consent, reserve a provider call, run a search,
 * access provider API keys, or persist a search.
 */
const ALLOWED_PROVIDERS = new Set(["serpapi", "searchapi", "travelpayouts", "duffel"]);
const MAX_BODY_BYTES = 16 * 1024;
const MAX_BASE_CALLS = 2000;
const WEB_CALL_WARNING_AT = 16;

function json(value, status = 200) {
  return new Response(JSON.stringify(value), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "private, no-store",
      "x-content-type-options": "nosniff",
      "vary": "Cookie, oai-authenticated-user-email",
    },
  });
}

async function limitedBody(request) {
  const rawLength = request.headers.get("content-length");
  if (rawLength != null && (/[^0-9]/.test(rawLength) || Number(rawLength) > MAX_BODY_BYTES)) {
    return { error: "Search estimate body is too large.", status: 413 };
  }
  if (!request.body) return { error: "JSON search query is required.", status: 400 };
  const reader = request.body.getReader();
  const chunks = [];
  let total = 0;
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > MAX_BODY_BYTES) {
        await reader.cancel();
        return { error: "Search estimate body is too large.", status: 413 };
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  const all = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    all.set(chunk, offset);
    offset += chunk.byteLength;
  }
  let parsed;
  try {
    parsed = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(all));
  } catch {
    return { error: "Malformed JSON search query.", status: 400 };
  }
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
    return { error: "Expected a search query object.", status: 400 };
  }
  return { parsed };
}

function isValidDate(value) {
  if (typeof value !== "string" || !/^20[2-9][0-9]-[0-9]{2}-[0-9]{2}$/.test(value)) return false;
  const when = new Date(value + "T00:00:00Z");
  return Number.isFinite(when.getTime()) && when.toISOString().slice(0, 10) === value;
}

function requestBoundaryProblem(query) {
  const isAirports = list => Array.isArray(list) && list.length <= 8 &&
    list.every(s => typeof s === "string" && /^[A-Za-z]{3}$/.test(s));
  if (!isAirports(query.origins) || !isAirports(query.destinations)) {
    return "Provide at most eight bounded origin and destination codes.";
  }
  if (!Array.isArray(query.providers) || query.providers.length > 4 ||
      query.providers.some(id => typeof id !== "string" || !ALLOWED_PROVIDERS.has(id.toLowerCase()))) {
    return "Select at most four supported providers.";
  }
  if (!isValidDate(query.outboundFrom) ||
      !isValidDate(query.outboundTo || query.outboundFrom)) {
    return "Provide a valid outbound date range from 2020 through 2099.";
  }
  const outboundTo = query.outboundTo || query.outboundFrom;
  const outSpan = (Date.parse(outboundTo + "T00:00:00Z") -
    Date.parse(query.outboundFrom + "T00:00:00Z")) / 86400000;
  if (outSpan < 0 || outSpan > 30) return "Outbound date range must be at most 31 days.";
  if (query.returnFrom != null && query.returnFrom !== "") {
    if (!isValidDate(query.returnFrom) || !isValidDate(query.returnTo || query.returnFrom)) {
      return "Provide a valid return date range from 2020 through 2099.";
    }
    const retSpan = (Date.parse((query.returnTo || query.returnFrom) + "T00:00:00Z") -
      Date.parse(query.returnFrom + "T00:00:00Z")) / 86400000;
    if (retSpan < 0 || retSpan > 30) return "Return date range must be at most 31 days.";
  }
  if (query.nights != null && (!Number.isInteger(query.nights) || query.nights < 1 || query.nights > 60)) {
    return "Trip length must be between 1 and 60 nights.";
  }
  if (query.returnChoices != null && (!Number.isInteger(query.returnChoices) ||
      query.returnChoices < 1 || query.returnChoices > 8)) {
    return "Return choices must be between 1 and 8.";
  }
  if (query.tripType != null && !["one_way", "round_trip"].includes(query.tripType)) {
    return "Unsupported trip type.";
  }
  return null;
}

/**
 * Only call this after getVerifiedEmail has authenticated the request.
 * A successful response is informative, NOT a reusable approval token.
 */
export async function estimateHostedSearch(request, kotlinEngine) {
  if (!kotlinEngine || typeof kotlinEngine.planSearchJson !== "function" ||
      typeof kotlinEngine.projectBudgetJson !== "function") {
    throw new Error("Compiled Kotlin/JS budget engine is required.");
  }
  if (!/^application\/json(?:\s*;|$)/i.test(request.headers.get("content-type") || "")) {
    return json({ error: "Content-Type: application/json required." }, 415);
  }
  const origin = request.headers.get("origin");
  if (origin !== null && origin !== new URL(request.url).origin) {
    return json({ error: "Cross-origin search estimates are not permitted." }, 403);
  }
  const supplied = await limitedBody(request);
  if (supplied.error) return json({ error: supplied.error }, supplied.status);
  // Bound the input *before* invoking Kotlin's date-range planner, so an
  // authenticated visitor cannot allocate huge intermediate date sequences.
  const problem = requestBoundaryProblem(supplied.parsed);
  if (problem) return json({ error: problem }, 400);
  let plan;
  try {
    plan = JSON.parse(kotlinEngine.planSearchJson(JSON.stringify(supplied.parsed)));
  } catch {
    return json({ error: "Invalid Kotlin flight search query." }, 400);
  }
  const warningBase = [
    "Estimate only. Hosted searches are disabled; no provider has been contacted or charged.",
    "Request counts are upper bounds, not provider price quotations or guaranteed billable usage.",
  ];
  if (plan.error) {
    return json({
      liveCalls: 0, cachedCalls: 0, skippedCalls: 0,
      returnLookupsUpperBound: 0,
      lines: [], quota: [],
      warnings: [plan.error, ...warningBase],
      canRun: false,
    });
  }
  const query = plan.query;
  const pairs = plan.pairs;
  if (!query || !Array.isArray(pairs) || !Array.isArray(query.origins) ||
      !Array.isArray(query.destinations) || !Array.isArray(query.providers) ||
      pairs.length > 500 || query.origins.length > 500 || query.destinations.length > 500) {
    return json({ error: "Invalid Kotlin search plan." }, 400);
  }
  const providerIds = [...new Set(query.providers)];
  if (providerIds.length > ALLOWED_PROVIDERS.size ||
      providerIds.some(id => !ALLOWED_PROVIDERS.has(id))) {
    return json({ error: "Unsupported provider in search plan." }, 400);
  }
  const baseCalls = pairs.length * query.origins.length * query.destinations.length * providerIds.length;
  if (baseCalls > MAX_BASE_CALLS) {
    return json({ error: "Search estimate exceeds the hosted route limit." }, 413);
  }
  const providers = providerIds.map(id => ({
    id,
    configured: true, // Hypothetical calls IF explicitly enabled later.
    calls: pairs.flatMap(pair => query.origins.flatMap(originCode =>
      query.destinations.map(destinationCode => ({
        description: id + ": " + originCode + " → " + destinationCode + " " +
          pair.outbound + (pair.returning ? " / " + pair.returning : ""),
        cached: false, // Hosted read-only Site has no response cache.
      })))),
  }));
  let budget;
  try {
    budget = JSON.parse(kotlinEngine.projectBudgetJson(
      JSON.stringify(query), JSON.stringify(providers),
    ));
  } catch {
    throw new Error("Compiled Kotlin budget projection failed.");
  }
  const upperBound = budget.liveCalls + budget.returnLookupsUpperBound;
  const warnings = [...warningBase];
  if (query.cacheOnly) {
    warnings.push("Cache-only mode requested. This Site has no hosted provider-response cache.");
  }
  if (providerIds.length === 0) {
    warnings.push("Select at least one provider to estimate a search.");
  }
  if (upperBound > WEB_CALL_WARNING_AT) {
    warnings.push("Potential network calls exceed the " + WEB_CALL_WARNING_AT +
      "-request web safety threshold. Narrow the search before enabling providers.");
  }
  if (query.maxLiveCalls != null && upperBound > query.maxLiveCalls) {
    warnings.push("Potential requests exceed the query's configured call cap.");
  }
  return json({
    liveCalls: budget.liveCalls,
    cachedCalls: budget.cachedCalls,
    skippedCalls: budget.skippedCalls,
    returnLookupsUpperBound: budget.returnLookupsUpperBound,
    lines: (budget.lines || []).slice(0, 12),
    warnings,
    quota: providerIds.map(provider => ({
      provider, configured: false, remaining: null,
      note: "Disabled in this Site; no provider quota queried",
    })),
    canRun: false,
  });
}
