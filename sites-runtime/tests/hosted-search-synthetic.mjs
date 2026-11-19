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
  .run("serpapi", 2, 2);
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
  assert.equal(route.searchParams.get("type"), "2");
  assert.equal(route.searchParams.get("travel_class"), "3");
  assert.equal(route.searchParams.get("outbound_date"), "2027-04-12");
  assert.equal(init.redirect, "manual");
  assert.equal(conn.prepare("SELECT COUNT(*) AS n FROM provider_http_reservations").get().n, outboundRequests,
    "Durable cap reservation must be recorded before provider egress");
  assert.equal(conn.prepare("SELECT COUNT(*) AS n FROM hosted_provider_search_attempts WHERE state = 'attempted'").get().n,
    1, "Route attempt must be recorded before provider egress");
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
conn.close();
console.log("Synthetic SerpApi / Kotlin JS / D1 / private Results integration passed");
