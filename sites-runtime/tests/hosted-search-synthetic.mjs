#!/usr/bin/env node
/**
 * Full synthetic hosted search integration: real compiled Kotlin/JS, real
 * SQLite migrations, signed consent, fake SerpApi HTTP, one D1 reservation,
 * durable snapshot and price history, replay and tenant isolation.
 *
 * No real network calls, credentials, or Sites deployment.
 */
import assert from "node:assert/strict";
import { webcrypto } from "node:crypto";
import { createRequire } from "node:module";
import { DatabaseSync } from "node:sqlite";
import { readFileSync, readdirSync } from "node:fs";
import { resolve } from "node:path";
import { createReadOnlySitesHandler } from "../src/site-handler.mjs";
import { kotlinEngineFromBundle } from "../src/kotlin-engine.mjs";

const [samplePath, bundleDir, htmlPath] = process.argv.slice(2);
if (!samplePath || !bundleDir || !htmlPath) {
  throw new Error("Usage: node --experimental-sqlite sites-runtime/tests/hosted-search-synthetic.mjs <sample.json> <bundle-dir> <html>");
}
const require = createRequire(import.meta.url);
const bundles = readdirSync(bundleDir).filter(name => name.endsWith(".js") && !name.includes("hot-update"));
assert.equal(bundles.length, 1);
const engine = kotlinEngineFromBundle(require(resolve(bundleDir, bundles[0])));
const sample = JSON.parse(readFileSync(samplePath, "utf8"));
const html = readFileSync(htmlPath, "utf8");
const conn = new DatabaseSync(":memory:");
conn.exec("PRAGMA foreign_keys = ON");
for (const migration of [
  "sites-runtime/migrations/0001_provider_http_budget.sql",
  "sites-runtime/migrations/0002_investigation_snapshots.sql",
  "sites-runtime/migrations/0003_public_sample_fixtures.sql",
  "sites-runtime/migrations/0004_search_consents.sql",
  "sites-runtime/migrations/0005_hosted_provider_cache.sql",
]) conn.exec(readFileSync(migration, "utf8"));
conn.prepare("INSERT INTO provider_http_limits(provider, daily_cap, monthly_cap) VALUES(?, ?, ?)")
  .run("serpapi", 5, 5);
const db = {
  prepare(sql) {
    const statement = conn.prepare(sql);
    return {
      bind(...params) {
        return {
          async first() { return statement.get(...params) ?? null; },
          async all() { return { results: statement.all(...params) }; },
          async run() { return { success: true, meta: statement.run(...params) }; },
        };
      },
    };
  },
  async batch(statements) {
    conn.exec("BEGIN TRANSACTION");
    try {
      const results = [];
      for (const statement of statements) results.push(await statement.run());
      conn.exec("COMMIT");
      return results;
    } catch (error) {
      conn.exec("ROLLBACK");
      throw error;
    }
  },
};
const now = () => new Date("2026-11-19T12:00:00Z");
const secret = "test-only-hosted-integration-long-owner-secret";
const signSecret = "test-only-signing-different-private-secret-32bytes";
const apiKey = "synthetic-serpapi-key-never-used-on-the-network";
let outboundRequests = 0;
const fetcher = async (url, init) => {
  outboundRequests++;
  const route = new URL(url);
  assert.equal(route.origin, "https://serpapi.com");
  assert.equal(route.searchParams.get("api_key"), apiKey);
  assert.equal(route.searchParams.get("adults"), "2");
  const trip = route.searchParams.get("type");
  assert.ok(trip === "1" || trip === "2");
  assert.equal(route.searchParams.get("travel_class"), "3");
  assert.equal(route.searchParams.get("outbound_date"), "2027-04-12");
  assert.equal(init.redirect, "manual");
  assert.equal(conn.prepare("SELECT COUNT(*) AS n FROM provider_http_reservations").get().n, outboundRequests,
    "Durable cap reservation must be recorded before provider egress");
  assert.equal(conn.prepare("SELECT COUNT(*) AS n FROM hosted_provider_search_attempts WHERE state = 'attempted'").get().n,
    1, "Route attempt must be recorded before provider egress");
  if (trip === "1" && route.searchParams.has("departure_token")) {
    assert.equal(route.searchParams.get("departure_token"), "private-departure-token");
    assert.equal(route.searchParams.get("return_date"), "2027-04-20");
    return Response.json({
      best_flights: [{
        price: 3600, total_duration: 720, booking_token: "private-return-booking",
        flights: [{
          departure_airport: { id: "NRT", time: "2027-04-20 12:00" },
          arrival_airport: { id: "LHR", time: "2027-04-20 17:00" },
          flight_number: "BA 8", airline: "British Airways",
          plane_and_crew_by: "British Airways", duration: 720,
          travel_class: "Business",
        }],
      }],
      other_flights: [],
    });
  }
  if (trip === "1") assert.equal(route.searchParams.get("return_date"), "2027-04-20");
  return new Response(JSON.stringify({
    best_flights: [{
      price: 2440,
      total_duration: 780,
      departure_token: "private-departure-token",
      booking_token: "private-booking-token",
      flights: [{
        departure_airport: { id: "LHR", time: "2027-04-12 13:30" },
        arrival_airport: { id: "NRT", time: "2027-04-13 10:15" },
        flight_number: "BA 7", airline: "British Airways",
        plane_and_crew_by: "British Airways", duration: 780,
        travel_class: "Business",
      }],
    }],
    other_flights: [],
  }), { headers: { "content-type": "application/json" } });
};
const baseConfig = {
  templateHtml: html, db, cryptoApi: webcrypto,
  ownerKeySecret: secret, bundledPublicSample: sample,
  kotlinEngine: engine,
  providerKeyPresence: { serpapi: true },
};
const makeSite = (email, enabled) => createReadOnlySitesHandler({
  ...baseConfig,
  getVerifiedEmail: async () => email,
  searchPrepareConfig: {
    enabled, executionAvailable: enabled,
    providerKeyPresent: true, consentSigningSecret: signSecret,
    clock: now,
  },
  searchExecuteConfig: {
    enabled, approvedForPaidCalls: enabled,
    serpApiKey: apiKey, consentSigningSecret: signSecret,
    fetcher, clock: now,
  },
});
const owner = makeSite("alice@example.com", true);
const stranger = makeSite("bob@example.com", true);
const disabled = makeSite("alice@example.com", false);
const origin = "https://clawfare.example";
function post(path, body) {
  return new Request(origin + path, {
    method: "POST",
    headers: { "content-type": "application/json", origin, "sec-fetch-site": "same-origin" },
    body: JSON.stringify(body),
  });
}
function get(path) { return new Request(origin + path); }
const query = {
  origins: ["LHR"], destinations: ["NRT"],
  outboundFrom: "2027-04-12", outboundTo: "2027-04-12",
  tripType: "one_way", adults: 2, cabin: "business",
  currency: "GBP", market: "uk",
  providers: ["serpapi"], maxLiveCalls: 1,
  fetchReturns: false, cacheOnly: false, confirmed: false,
};
const disabledStatus = await disabled(get("/api/status"));
assert.equal(disabledStatus.status, 200);
assert.equal((await disabledStatus.json()).hostedSearch.canExecuteOneWayGbpSerpApi, false);
const enabledStatus = await owner(get("/api/status"));
assert.equal(enabledStatus.status, 200);
assert.equal((await enabledStatus.json()).hostedSearch.canExecuteOneWayGbpSerpApi, true);
assert.equal((await disabled(post("/api/prepare-search", query))).status, 405);
assert.equal((await disabled(post("/api/search", { canonicalQuery: query, consentToken: "fake" }))).status, 405);
assert.equal(outboundRequests, 0);

const quoteResponse = await owner(post("/api/prepare-search", query));
assert.equal(quoteResponse.status, 200);
const quote = await quoteResponse.json();
assert.equal(quote.canExecuteHere, true);
assert.equal(quote.totalCallsUpperBound, 1);
assert.equal(typeof quote.consentToken, "string");
assert.equal(outboundRequests, 0);

// Signed token cannot be used by another verified owner, even with matching
// canonical query. No budget reservation or provider HTTP results.
const request = { canonicalQuery: quote.canonicalQuery, consentToken: quote.consentToken };
const crossOwner = await stranger(post("/api/search", request));
assert.equal(crossOwner.status, 409);
assert.equal(outboundRequests, 0);
assert.equal(conn.prepare("SELECT COUNT(*) AS n FROM hosted_search_consents WHERE state = 'issued'").get().n, 1);

const resultResponse = await owner(post("/api/search", request));
assert.equal(resultResponse.status, 200);
const payload = await resultResponse.json();
assert.equal(payload.ok, true);
assert.equal(payload.result.itineraries.length, 1);
assert.equal(payload.result.itineraries[0].pricePerPerson, 1220);
assert.equal(payload.result.itineraries[0].passengers, 2);
assert.equal(payload.result.itineraries[0].outboundDate, "2027-04-12");
assert.equal(payload.result.itineraries[0].destination, "NRT");
assert.equal(payload.result.itineraries[0].returnDate ?? null, null);
assert.equal(payload.result.itineraries[0].cashRefundProofVerified, false);
assert.equal(payload.providerObservations[0].baseCallsLoaded, 1);
assert.equal(payload.providerObservations[0].eligible, 1);
assert.deepEqual(payload.result.coverage, {
  version: 1, observedAt: now().toISOString(), state: "complete",
  routes: [{
    provider: "serpapi", market: "uk", origin: "LHR", destination: "NRT",
    outboundDate: "2027-04-12", returnDate: null,
    state: "complete", sourceMode: "live", providerRows: 1, parsedOffers: 1, eligibleOffers: 1,
    returnLookupsLoaded: 0, unresolvedReturns: 0, returnSelectionLimited: false, errorKind: null,
  }],
  eligibleItineraries: 1, excludedByReason: {},
});
assert.equal(outboundRequests, 1);
assert.equal(conn.prepare("SELECT COUNT(*) AS n FROM provider_http_reservations").get().n, 1);
assert.equal(conn.prepare("SELECT COUNT(*) AS n FROM hosted_provider_search_attempts WHERE state = 'complete'").get().n, 1);
assert.equal(conn.prepare("SELECT COUNT(*) AS n FROM fare_price_observations").get().n, 1);
const resultJson = JSON.stringify(payload);
for (const privateValue of ["private-departure-token", "private-booking-token", apiKey,
  "departureToken", "bookingToken"]) assert.equal(resultJson.includes(privateValue), false);

const slug = payload.result.id;
const saved = await owner(get("/api/results/" + slug));
assert.equal(saved.status, 200);
assert.deepEqual(await saved.json(), payload.result);
assert.equal((await stranger(get("/api/results/" + slug))).status, 404);
const listing = await owner(get("/api/searches"));
assert.equal((await listing.json())[0].id, slug);
const history = await owner(get("/api/history/" + slug + "/" + payload.result.itineraries[0].id));
assert.equal((await history.json()).length, 1);
const attempts = await owner(get("/api/attempts/" + payload.searchAttemptNonce));
assert.equal((await attempts.json()).length, 1);
const foreignAttempts = await stranger(get("/api/attempts/" + payload.searchAttemptNonce));
assert.deepEqual(await foreignAttempts.json(), []);

const replay = await owner(post("/api/search", request));
assert.equal(replay.status, 409);
assert.equal(outboundRequests, 1);
assert.equal(conn.prepare("SELECT COUNT(*) AS n FROM provider_http_reservations").get().n, 1);

const changed = await owner(post("/api/search", {
  ...request, canonicalQuery: { ...request.canonicalQuery, outboundFrom: "2027-04-13" },
}));
assert.equal(changed.status, 422);
assert.equal(outboundRequests, 1);
// Round-trip quotes must explicitly budget both base and return selection
// categories. The second HTTP request is a real D1 reservation and its
// private departure_token must never reach D1 result snapshots or the browser.
const roundQuery = {
  ...query, tripType: "round_trip", nights: 8,
  fetchReturns: true, returnChoices: 1, maxLiveCalls: 2,
};
const roundQuoteResponse = await owner(post("/api/prepare-search", roundQuery));
assert.equal(roundQuoteResponse.status, 200);
const roundQuote = await roundQuoteResponse.json();
assert.equal(roundQuote.canExecuteHere, true);
assert.equal(roundQuote.baseCallsUpperBound, 1);
assert.equal(roundQuote.returnLookupsUpperBound, 1);
assert.equal(roundQuote.totalCallsUpperBound, 2);
const roundRequest = {
  canonicalQuery: roundQuote.canonicalQuery,
  consentToken: roundQuote.consentToken,
};
const roundResponse = await owner(post("/api/search", roundRequest));
assert.equal(roundResponse.status, 200);
const round = await roundResponse.json();
assert.equal(round.ok, true);
assert.equal(round.result.itineraries.length, 1);
const roundFare = round.result.itineraries[0];
assert.equal(roundFare.pricePerPerson, 1800, "3600 is the two-person entire return fare");
assert.equal(roundFare.outboundDate, "2027-04-12");
assert.equal(roundFare.returnDate, "2027-04-20");
assert.equal(roundFare.nights, 8);
assert.equal(roundFare.origin, "LHR");
assert.equal(roundFare.destination, "NRT");
assert.equal(roundFare.returnFrom, "NRT");
assert.equal(roundFare.returnMissing, false);
assert.equal(roundFare.cashRefundProofVerified, false);
assert.equal(round.providerObservations[0].baseCallsLoaded, 1);
assert.equal(round.providerObservations[0].returnLookupsExpected, 1);
assert.equal(round.providerObservations[0].returnLookupsLoaded, 1);
assert.equal(round.providerObservations[0].unresolvedReturns, 0);
assert.equal(round.result.coverage.routes[0].returnLookupsLoaded, 1);
assert.equal(round.result.coverage.routes[0].returnDate, "2027-04-20");
assert.equal(outboundRequests, 3);
assert.equal(conn.prepare("SELECT COUNT(*) AS n FROM provider_http_reservations").get().n, 3);
assert.equal(conn.prepare(
  "SELECT attempted_calls AS n, loaded_calls AS loaded FROM hosted_provider_search_attempts WHERE consent_nonce = ?",
).get(round.searchAttemptNonce).n, 2);
assert.equal(conn.prepare("SELECT COUNT(*) AS n FROM fare_price_observations").get().n, 2);
assert.deepEqual(await (await owner(get("/api/results/" + round.result.id))).json(), round.result);
assert.equal((await stranger(get("/api/results/" + round.result.id))).status, 404);
const roundHistory = await owner(get("/api/history/" + round.result.id + "/" + roundFare.id));
assert.equal((await roundHistory.json()).length, 1);
const allRoundResults = JSON.stringify(round);
for (const secretValue of [apiKey, "private-departure-token",
  "private-booking-token", "private-return-booking", "departureToken", "bookingToken"]) {
  assert.equal(allRoundResults.includes(secretValue), false);
}
assert.equal((await owner(post("/api/search", roundRequest))).status, 409);
assert.equal(outboundRequests, 3);

// A provider can report outbound options without any departure token. That
// must produce an *incomplete* result, never a £1220 round-trip quote.
const missingTokenFetcher = async (url, init) => {
  const response = await fetcher(url, init);
  const obj = await response.json();
  for (const item of obj.best_flights || []) delete item.departure_token;
  return Response.json(obj);
};
const incompleteSite = createReadOnlySitesHandler({
  ...baseConfig,
  getVerifiedEmail: async () => "alice@example.com",
  searchPrepareConfig: { enabled: true, executionAvailable: true,
    providerKeyPresent: true, consentSigningSecret: signSecret, clock: now },
  searchExecuteConfig: { enabled: true, approvedForPaidCalls: true,
    serpApiKey: apiKey, consentSigningSecret: signSecret,
    fetcher: missingTokenFetcher, clock: now },
});
const missingQuoteResponse = await incompleteSite(post("/api/prepare-search", roundQuery));
assert.equal(missingQuoteResponse.status, 200);
const missingQuote = await missingQuoteResponse.json();
const incompleteResponse = await incompleteSite(post("/api/search", {
  canonicalQuery: missingQuote.canonicalQuery, consentToken: missingQuote.consentToken,
}));
assert.equal(incompleteResponse.status, 200);
const incomplete = await incompleteResponse.json();
assert.equal(incomplete.ok, true);
assert.equal(incomplete.result.itineraries.length, 0);
assert.equal(incomplete.providerObservations[0].state, "partial");
assert.equal(incomplete.providerObservations[0].unresolvedReturns, 1);
assert.equal(incomplete.result.coverage.state, "partial");
assert.equal(incomplete.result.coverage.routes[0].state, "partial");
assert.equal(incomplete.result.coverage.routes[0].unresolvedReturns, 1);
assert.deepEqual(await (await incompleteSite(get("/api/results/" + incomplete.result.id))).json(), incomplete.result);
assert.ok(incomplete.result.warnings.some(message => /verified return/.test(message)));
assert.equal(conn.prepare("SELECT COUNT(*) AS n FROM provider_http_reservations").get().n, 4);
assert.equal(conn.prepare("SELECT COUNT(*) AS n FROM fare_price_observations").get().n, 2);
conn.close();
console.log("Synthetic one-way + verified round-trip / Kotlin JS / D1 integration passed");
