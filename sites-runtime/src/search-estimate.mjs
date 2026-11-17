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
