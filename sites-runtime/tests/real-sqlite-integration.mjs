#!/usr/bin/env node
/**
 * Real cross-runtime verification, not a D1 mock:
 *   Kotlin/JVM fixture JSON -> SQLite D1 migrations -> hosted Fetch API,
 *   and a compiled Kotlin/JS bundle -> exported shared fare calculations.
 *
 * Node 22's experimental built-in SQLite is used only as a local stand-in
 * for D1's SQLite-compatible prepared statement API. This does not attest
 * to the ChatGPT Sites ingress identity trust boundary or deployment.
 */
import assert from "node:assert/strict";
import { webcrypto } from "node:crypto";
import { createRequire } from "node:module";
import { DatabaseSync } from "node:sqlite";
import { readFileSync, readdirSync } from "node:fs";
import { resolve } from "node:path";
import { createReadOnlySitesHandler } from "../src/site-handler.mjs";
import { D1Investigations } from "../src/investigations.mjs";

import { D1ProviderHttpBudget } from "../src/provider-http-budget.mjs";
import { kotlinEngineFromBundle } from "../src/kotlin-engine.mjs";
import { ownerKeyFromVerifiedEmail } from "../src/read-only-api.mjs";

const [samplePath, bundleDir, htmlPath] = process.argv.slice(2);
if (!samplePath || !bundleDir || !htmlPath) {
  throw new Error("Usage: node --experimental-sqlite sites-runtime/tests/real-sqlite-integration.mjs <sample.json> <webpack-directory> <web/index.html>");
}
const require = createRequire(import.meta.url);
const bundles = readdirSync(bundleDir).filter(name => name.endsWith(".js") && !name.includes("hot-update"));
assert.equal(bundles.length, 1, "Expected one Kotlin/JS compiled production bundle");
const engine = kotlinEngineFromBundle(require(resolve(bundleDir, bundles[0])));
const query = JSON.stringify({
  origins: [" lhr "], destinations: ["tpa"], outboundFrom: "2028-02-28",
  nights: 2, providers: ["serpapi"], tripType: "round_trip",
});
const plan = JSON.parse(engine.planSearchJson(query));
assert.equal(plan.error ?? null, null);
assert.deepEqual(plan.pairs, [{ outbound: "2028-02-28", returning: "2028-03-01" }]);
assert.deepEqual(JSON.parse(engine.normalizeSearchJson(query)).origins, ["LHR"]);
const budgetProjection = JSON.parse(engine.projectBudgetJson(query, JSON.stringify([{
  id: "serpapi", configured: true,
  calls: [{ description: "LHR -> TPA 2028-02-28", cached: false }],
}])));
assert.equal(budgetProjection.liveCalls, 1);
assert.equal(budgetProjection.returnLookupsUpperBound, 3);
assert.equal(engine.toGbpOrNull(125, 1.25), 100);

// D1-like binding with real SQLite FK/constraint enforcement, using only
// bound positional values (no handcrafted SQL result or fake response rows).
const sql = new DatabaseSync(":memory:");
sql.exec("PRAGMA foreign_keys = ON");
for (const migration of [
  "sites-runtime/migrations/0001_provider_http_budget.sql",
  "sites-runtime/migrations/0002_investigation_snapshots.sql",
  "sites-runtime/migrations/0003_public_sample_fixtures.sql",
]) sql.exec(readFileSync(migration, "utf8"));

const db = {
  prepare(statement) {
    const prepared = sql.prepare(statement);
    return {
      bind(...args) {
        return {
          async first() { return prepared.get(...args) ?? null; },
          async all() { return { results: prepared.all(...args) }; },
          async run() { return { success: true, meta: prepared.run(...args) }; },
        };
      },
    };
  },
};
const sample = JSON.parse(readFileSync(samplePath, "utf8"));
// A new hosted D1 binding initially has no imported sample. Sites may apply
// migrations during deployment without offering an administrative seed hook.
assert.equal(sql.prepare("SELECT COUNT(*) AS n FROM public_sample_fixtures").get().n, 0);
assert.equal(sql.prepare("SELECT COUNT(*) AS n FROM investigation_snapshots").get().n, 0);

const secret = "integration-only-long-random-ish-secret-not-for-hosting";
const ownerA = await ownerKeyFromVerifiedEmail("alice@example.com", secret, webcrypto);
const ownerB = await ownerKeyFromVerifiedEmail("bob@example.com", secret, webcrypto);
const store = new D1Investigations(db, {
  cryptoApi: webcrypto, clock: () => new Date("2026-11-16T12:30:00Z"),
});
const stub = title => ({
  id: "private-dec-2026", title, itineraries: [],
  fetchedAt: "2026-11-16T12:00:00Z",
});
await store.saveSnapshot(ownerA, stub("Alice only"));
await store.saveSnapshot(ownerB, stub("Bob only"));
const observation = {
  itineraryId: "lh-a", provider: "serpapi",
  sourceLabel: "cached", livePrice: false,
  capturedAt: "2026-11-16T12:00:00Z",
  originalCurrency: "GBP", amountDecimal: "350.00",
};
const observed = await store.recordPrice(ownerA, "private-dec-2026", observation);
assert.equal(observed, await store.recordPrice(ownerA, "private-dec-2026", observation));
assert.equal(sql.prepare("SELECT COUNT(*) AS n FROM fare_price_observations").get().n, 1);
assert.equal(sql.prepare("SELECT COUNT(*) AS n FROM provider_http_reservations").get().n, 0);

const templateHtml = readFileSync(htmlPath, "utf8");
const createSite = email => createReadOnlySitesHandler({
  templateHtml, db, cryptoApi: webcrypto, ownerKeySecret: secret,
  bundledPublicSample: sample, // Fixed, trusted build artifact; never request data
  kotlinEngine: engine, // Actual Kotlin/JS, no handwritten budget
  getVerifiedEmail: async () => email, // Test-only trusted identity injection
});
const sites = [createSite("alice@example.com"), createSite("bob@example.com"), createSite(null)];
const req = (path, method = "GET", headers) =>
  new Request("https://clawfare.example" + path, { method, headers });
const get = async (site, path) => {
  const res = await site(req(path));
  return { status: res.status, data: await res.json(), headers: res.headers };
};
const homepage = await sites[0](req("/"));
const homepageHtml = await homepage.text();
assert.equal(homepage.status, 200);
assert.ok(homepageHtml.includes("window.__CLAWFARE_HOSTED_READ_ONLY__ = true"));
assert.ok(homepageHtml.includes("window.__CLAWFARE_HOSTED_ESTIMATE_ONLY__ = true"));
assert.ok(homepageHtml.includes("Hosted read-only preview:"));
assert.ok(homepageHtml.includes("loadSample().then(refreshSaved)"));

// Signed-out requests cannot initialize a database. Two first signed-in
// requests may race, but INSERT ... ON CONFLICT DO NOTHING creates one row.
assert.equal((await get(sites[2], "/api/sample")).status, 401);
assert.equal(sql.prepare("SELECT COUNT(*) AS n FROM public_sample_fixtures").get().n, 0);
const [aliceSample, bobSample] = await Promise.all([
  get(sites[0], "/api/sample"), get(sites[1], "/api/sample"),
]);
assert.equal(aliceSample.status, 200);
assert.deepEqual(aliceSample.data, sample);
assert.deepEqual(bobSample.data, sample);
assert.equal(aliceSample.headers.get("cache-control"), "private, no-store");
assert.equal(sql.prepare("SELECT COUNT(*) AS n FROM public_sample_fixtures").get().n, 1);
assert.match(
  sql.prepare("SELECT content_sha256 FROM public_sample_fixtures").get().content_sha256,
  /^[a-f0-9]{64}$/,
);
assert.equal(sql.prepare("SELECT COUNT(*) AS n FROM investigation_snapshots").get().n, 2);

const aliceListing = await get(sites[0], "/api/searches");
const bobListing = await get(sites[1], "/api/searches");
assert.deepEqual(aliceListing.data.map(s => s.id), ["private-dec-2026"]);
assert.deepEqual(bobListing.data.map(s => s.id), ["private-dec-2026"]);
assert.equal((await get(sites[0], "/api/results/private-dec-2026")).data.title, "Alice only");
assert.equal((await get(sites[1], "/api/results/private-dec-2026")).data.title, "Bob only");
assert.equal((await get(sites[0], "/api/history/private-dec-2026/lh-a")).data.length, 1);
assert.deepEqual((await get(sites[1], "/api/history/private-dec-2026/lh-a")).data, []);
assert.equal((await get(sites[0], "/api/results/not-found")).status, 404);
assert.equal((await get(sites[0], "/api/searches?limit=1000")).status, 400);

// A caller's spoofed header cannot influence the trusted callback.
const forged = await sites[2](req("/api/searches", "GET", {
  "oai-authenticated-user-email": "alice@example.com",
}));
assert.equal(forged.status, 401);
assert.equal((await get(sites[2], "/api/sample")).status, 401);
for (const site of sites) {
  for (const path of ["/api/search", "/api/export/csv", "/api/results/private-dec-2026/track"]) {
    const response = await site(req(path, "POST"));
    assert.equal(response.status, 405, path);
  }
}
// This one informational POST is allowed only for signed-in users.
const estimateRequest = () => new Request("https://clawfare.example/api/estimate", {
  method: "POST",
  headers: { "content-type": "application/json" },
  body: JSON.stringify({
    origins: ["LHR"], destinations: ["TPA"],
    outboundFrom: "2028-02-28", outboundTo: "2028-02-28",
    nights: 2, tripType: "round_trip", returnChoices: 3,
    providers: ["serpapi"],
  }),
});
const estimateResponse = await sites[0](estimateRequest());
assert.equal(estimateResponse.status, 200);
const estimate = await estimateResponse.json();
assert.equal(estimate.liveCalls, 1);
assert.equal(estimate.returnLookupsUpperBound, 3);
assert.equal(estimate.canRun, false);
assert.match(estimate.warnings.join(" "), /no provider has been contacted/);
assert.equal((await sites[2](estimateRequest())).status, 401);
assert.equal(sql.prepare("SELECT COUNT(*) AS n FROM provider_http_reservations").get().n, 0);
assert.equal(sql.prepare("SELECT COUNT(*) AS n FROM investigation_snapshots").get().n, 2);

// Missing provider caps enforce denial even for authenticated callers.
const paid = new D1ProviderHttpBudget(db, () => new Date("2026-11-16T12:00:00Z"));
await assert.rejects(paid.reserve("serpapi", "base_search", "denied-test-1"), /cap reached|not configured/);
assert.equal(sql.prepare("SELECT COUNT(*) AS n FROM provider_http_reservations").get().n, 0);

// Any tampering, even syntactically valid extra whitespace, causes the public
// sample to be rejected before the hosting handler returns a response.
sql.prepare("UPDATE public_sample_fixtures SET result_json = result_json || ' ' WHERE slug = ?")
  .run("florida-dec-2026");
await assert.rejects(sites[0](req("/api/sample")), /digest mismatch/);
sql.close();
console.log("Clawfare hosted SQLite integration PASS: " + sample.itineraries.length +
  " Kotlin sample itineraries, independent tenants, JS flight planning, quota denial and fail-closed integrity");
