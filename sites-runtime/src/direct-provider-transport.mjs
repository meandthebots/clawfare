import { D1SearchConsents, SearchConsentError } from "./search-consent.mjs";
import { D1ProviderHttpBudget, meteredProviderFetch } from "./provider-http-budget.mjs";
import { D1ProviderPersistence } from "./provider-persistence.mjs";
import { directProviderRequest, duffelListRequest, parseDirectProvider } from "./direct-provider-fares.mjs";
import { digestFor, SerpApiTransportError, terminalErrorKind } from "./serpapi-metered-transport.mjs";

export async function executeDirectHostedSearch({
  db, ownerId, query, pairs, canonicalQueryJson, consentToken, consentSigningSecret,
  providerKey, mode, fetcher, clock, cryptoApi, kotlinEngine, investigationSlug,
}) {
  const provider = query.providers[0];
  const routes = pairs.flatMap(pair => query.origins.flatMap(origin => query.destinations.map(destination => ({
    origin, destination, outboundDate: pair.outbound, returnDate: pair.returning ?? null,
  }))));
  const authority = new D1SearchConsents(db, {
    secret: consentSigningSecret, enabled: true, clock, cryptoApi,
    policy: provider === "duffel" ? "hosted-duffel-" + mode : undefined,
  });
  let consent;
  try {
    consent = await authority.consume({ ownerId, canonicalQueryJson, token: consentToken });
  } catch (error) {
    if (error instanceof SearchConsentError) throw new SerpApiTransportError("consent_" + error.code);
    throw error;
  }
  const plannedCalls = routes.length * (provider === "duffel" ? 2 : 1);
  const [limit] = consent.callLimits;
  if (consent.totalCalls !== plannedCalls || consent.callLimits.length !== 1 ||
      limit?.provider !== provider || limit.category !== "base_search" || limit.count !== plannedCalls) {
    throw new SerpApiTransportError("consent_call_limit_mismatch");
  }
  const budget = new D1ProviderHttpBudget(db, clock);
  const persistence = new D1ProviderPersistence(db, { clock, cryptoApi });
  const fetchedAt = clock().toISOString();
  const attempts = [];
  const drafts = [];
  let completed = 0;
  for (const route of routes) {
    const routeDigest = await digestFor(JSON.stringify([consent.queryDigest, route]), cryptoApi);
    const identity = { ownerId, consentNonce: consent.nonce, provider, routeDigest };
    await persistence.beginAttempt({ ...identity, queryDigest: consent.queryDigest, investigationSlug });
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 12_000);
    let observed;
    let attemptedCalls = 0, loadedCalls = 0;
    try {
      const run = async ({ url, init }) => {
        attemptedCalls++;
        const response = await meteredProviderFetch(budget, url, { ...init, signal: controller.signal }, fetcher);
        if (response.ok) loadedCalls++;
        return response;
      };
      const request = directProviderRequest(provider, query, route, providerKey);
      let response = await run(request);
      if (provider === "duffel") {
        response = await run(await duffelListRequest(response, mode, request.init.headers));
      }
      observed = await parseDirectProvider(response, provider, query, route, mode, fetchedAt, cryptoApi);
    } catch (error) {
      const code = controller.signal.aborted ? "provider_timeout"
        : error instanceof SerpApiTransportError ? error.code : error.code ?? "provider_transport";
      const errorKind = terminalErrorKind(code);
      await persistence.finishAttempt({
        ...identity, state: "failed", attemptedCalls, loadedCalls, cachedCalls: 0,
        returnedOffers: 0, eligibleOffers: 0, errorKind,
      });
      attempts.push({ ...route, state: "failed", errorKind });
      break;
    } finally {
      clearTimeout(timer);
    }
    const { items, providerRows, moreAvailable } = observed;
    const normalized = items.slice(0, 200 - drafts.length);
    let eligibleOffers = 0;
    for (const draft of normalized) {
      if (provider === "travelpayouts" || JSON.parse(kotlinEngine.evaluateOfferEligibilityJson(JSON.stringify({
        query, evidence: { outbound: draft.outbound, returning: draft.returning }, airportCountries: {},
      }))).eligible) eligibleOffers++;
    }
    const state = moreAvailable || normalized.length < providerRows ? "partial" : normalized.length ? "complete" : "empty";
    const errorKind = state === "partial" ? "incomplete" : null;
    await persistence.finishAttempt({
      ...identity, state, attemptedCalls, loadedCalls, cachedCalls: 0,
      returnedOffers: normalized.length, eligibleOffers, errorKind,
    });
    attempts.push({
      ...route, state, sourceLabel: mode, providerRows, offersReported: normalized.length, eligibleOffers, errorKind,
    });
    drafts.push(...normalized);
    completed++;
  }
  return {
    provider, attempts, drafts, fetchedAt, consentNonce: consent.nonce,
    plannedCalls, baseCallsExpected: routes.length, completedCalls: completed,
    state: attempts.every(attempt => attempt.state === "complete" || attempt.state === "empty") ? "complete"
      : completed ? "partial" : "failed",
  };
}

export function assembleCachedLeads(id, query, observed, fx) {
  const leads = new Map();
  for (const lead of observed.drafts) {
    const existing = leads.get(lead.id);
    const price = row => row.pricePerPerson ?? row.priceOriginal;
    if (!existing || price(lead) < price(existing)) leads.set(lead.id, lead);
  }
  const itineraries = [...leads.values()];
  return {
    eligible: itineraries.length, excludedByReason: {},
    result: {
      id, query, fetchedAt: observed.fetchedAt, currency: "GBP", checkedBags: 0, passengers: 1, fx,
      title: query.origins.join("/") + " → " + query.destinations.join("/") + " · " + query.outboundFrom,
      subtitle: "Travelpayouts cached calendar price leads",
      warnings: ["Cabin, exact flights, cash refund terms and current availability are unverified."],
      itineraries, addons: [], cells: [], vendors: [], sellerOptions: [],
      vendorIntro: [], headerNotes: [], suggestedFilters: {},
    },
  };
}
