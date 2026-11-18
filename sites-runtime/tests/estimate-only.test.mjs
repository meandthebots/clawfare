import assert from "node:assert/strict";
import test from "node:test";
import { webcrypto } from "node:crypto";
import { estimateHostedSearch } from "../src/search-estimate.mjs";
import { handleReadOnlyApi } from "../src/read-only-api.mjs";

const endpoint = "https://clawfare.example/api/estimate";
const secret = "a-stable-local-test-only-owner-key-of-sufficient-length";
const basicQuery = {
  origins: ["LHR"], destinations: ["TPA"],
  outboundFrom: "2028-02-28", outboundTo: "2028-02-28",
  tripType: "round_trip", nights: 2,
  returnChoices: 3, fetchReturns: true,
  providers: ["serpapi"],
};
function req(value = basicQuery, headers = {}) {
  return new Request(endpoint, {
    method: "POST",
    headers: { "content-type": "application/json", ...headers },
    body: JSON.stringify(value),
  });
}
function stub() {
  const events = [];
  const engine = {
    planSearchJson(input) {
      events.push("plan");
      const query = JSON.parse(input);
      return JSON.stringify({
        query,
        pairs: [{ outbound: "2028-02-28", returning: "2028-03-01" }],
      });
    },
    projectBudgetJson(input, planned) {
      events.push("budget");
      const query = JSON.parse(input);
      const providers = JSON.parse(planned);
      const calls = providers.flatMap(provider => provider.calls);
      const skipped = query.cacheOnly ? calls.length : 0;
      return JSON.stringify({
        liveCalls: calls.length - skipped,
        skippedCalls: skipped, cachedCalls: 0,
        returnLookupsUpperBound: query.cacheOnly ? 0 :
          providers.filter(p => ["serpapi", "searchapi"].includes(p.id))
            .reduce((sum, p) => sum + p.calls.length * query.returnChoices, 0),
        lines: calls.map(c => c.description),
      });
    },
  };
  return { events, engine };
}

test("Kotlin-backed estimate shows potential calls without consent, D1 writes or egress", async () => {
  const { events, engine } = stub();
  const result = await estimateHostedSearch(req(), engine);
  assert.equal(result.status, 200);
  assert.equal(result.headers.get("cache-control"), "private, no-store");
  const body = await result.json();
  assert.equal(body.liveCalls, 1);
  assert.equal(body.returnLookupsUpperBound, 3);
  assert.equal(body.canRun, false);
  assert.equal(body.quota[0].configured, false);
  assert.match(body.warnings.join(" "), /no provider has been contacted/);
  assert.deepEqual(events, ["plan", "budget"]);
});

test("authentication must succeed before parsing or invoking Kotlin estimation", async () => {
  const { events, engine } = stub();
  const db = { prepare() { throw new Error("Estimating must not touch D1"); } };
  const context = {
    db, kotlinEngine: engine, cryptoApi: webcrypto,
    ownerKeySecret: secret, getVerifiedEmail: async () => null,
  };
  const unsigned = await handleReadOnlyApi(req(basicQuery, {
    "oai-authenticated-user-email": "owner@example.com",
  }), context);
  assert.equal(unsigned.status, 401);
  assert.deepEqual(events, []);
  const verified = await handleReadOnlyApi(req(), {
    ...context, getVerifiedEmail: async () => "owner@example.com",
  });
  assert.equal(verified.status, 200);
  assert.equal((await verified.json()).liveCalls, 1);
  assert.deepEqual(events, ["plan", "budget"]);
  const live = await handleReadOnlyApi(new Request("https://clawfare.example/api/search", {
    method: "POST", body: "{}", headers: { "content-type": "application/json" },
  }), { ...context, getVerifiedEmail: async () => "owner@example.com" });
  assert.equal(live.status, 405);
});

test("bad JSON, wrong origin and content-type fail before planning", async () => {
  const { events, engine } = stub();
  const crossSite = await estimateHostedSearch(req(basicQuery, { origin: "https://attacker.example" }), engine);
  assert.equal(crossSite.status, 403);
  const missing = await estimateHostedSearch(new Request(endpoint, {
    method: "POST", body: JSON.stringify(basicQuery),
    headers: { "content-type": "text/plain" },
  }), engine);
  assert.equal(missing.status, 415);
  const malformed = await estimateHostedSearch(new Request(endpoint, {
    method: "POST", body: "{", headers: { "content-type": "application/json" },
  }), engine);
  assert.equal(malformed.status, 400);
  const huge = await estimateHostedSearch(new Request(endpoint, {
    method: "POST", body: "x".repeat(20_000),
    headers: { "content-type": "application/json" },
  }), engine);
  assert.equal(huge.status, 413);
  const unknownProvider = await estimateHostedSearch(
    req({ ...basicQuery, providers: ["unrestricted-unknown"] }), engine);
  assert.equal(unknownProvider.status, 400);
  const hugeRange = await estimateHostedSearch(req({
    ...basicQuery, outboundFrom: "2028-01-01", outboundTo: "2099-01-01",
  }), engine);
  assert.equal(hugeRange.status, 400);
  assert.deepEqual(events, []);
});

test("cache-only projection cannot imply a provider request was executed", async () => {
  const { engine } = stub();
  const result = await estimateHostedSearch(req({
    ...basicQuery, cacheOnly: true,
  }), engine);
  const body = await result.json();
  assert.equal(body.liveCalls, 0);
  assert.equal(body.returnLookupsUpperBound, 0);
  assert.equal(body.skippedCalls, 1);
  assert.equal(body.canRun, false);
  assert.match(body.warnings.join(" "), /no hosted provider-response cache/);
});

test("hosted parser rejects malformed hard constraints before Kotlin planning", async () => {
  const { events, engine } = stub();
  const malformed = [
    { excludedAirlines: ["LONGCODE"] },
    { excludedTransitAirports: ["LONDON"] },
    { excludedTransitCountries: ["GBR"] },
    { strictPerLegCabin: "yes" },
    { strictOperatingCarrier: 1 },
    { maxLiveCalls: -1 },
    { market: "all" },
    { currency: "EU" },
    { investigation: "../secret" },
    { adults: 20 },
    { tripType: "one_way", nights: 3 },
    { nights: 2, returnFrom: "2028-03-01" },
    { nights: null, returnFrom: null, tripType: "round_trip" },
  ];
  for (const addition of malformed) {
    const result = await estimateHostedSearch(req({ ...basicQuery, ...addition }), engine);
    assert.equal(result.status, 400, JSON.stringify(addition));
  }
  assert.deepEqual(events, [], "untrusted hard constraints never reach Kotlin planning");
});
