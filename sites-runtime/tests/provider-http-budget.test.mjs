import assert from "node:assert/strict";
import test from "node:test";
import {
  D1ProviderHttpBudget,
  classifyProviderRequest,
  meteredProviderFetch,
} from "../src/provider-http-budget.mjs";

function fakeDatabase(log, accepted = true) {
  return {
    prepare(statement) {
      return {
        bind(...values) {
          return {
            async first() {
              const id = statement.startsWith("INSERT") ? values[0] : values[2];
              log.push({ sql: statement.startsWith("INSERT") ? "reserve" : "complete", values });
              return accepted ? { id } : null;
            },
          };
        },
      };
    },
  };
}

test("D1 budget rejects a missing or exhausted provider cap", async () => {
  const calls = [];
  const budget = new D1ProviderHttpBudget(fakeDatabase(calls, false), () => new Date("2026-11-16T09:00:00Z"));
  await assert.rejects(budget.reserve("serpapi", "base_search", "r1"), /cap reached/);
  assert.equal(calls.length, 1);
});

test("UTC day and month are supplied by server clock, never caller inputs", async () => {
  const calls = [];
  const budget = new D1ProviderHttpBudget(fakeDatabase(calls), () => new Date("2026-11-30T23:59:59Z"));
  assert.equal(await budget.reserve("searchapi", "booking_options", "r2"), "r2");
  const values = calls[0].values;
  assert.deepEqual(values, [
    "r2", "booking_options", "2026-11-30T23:59:59.000Z",
    "2026-11-30", "2026-11", "searchapi", "2026-11-30", "2026-11",
  ]);
  await budget.complete("r2", "searchapi", "booking_options", 429, "rate_limited");
  assert.equal(calls[1].sql, "complete");
  assert.deepEqual(calls[1].values, [429, "rate_limited", "r2", "searchapi", "booking_options"]);
});

test("rejects unknown providers/categories, invalid clocks and status codes", async () => {
  const calls = [];
  const budget = new D1ProviderHttpBudget(fakeDatabase(calls), () => new Date("bad"));
  await assert.rejects(budget.reserve("serpapi", "base_search", "bad-clock"), /UTC time/);
  await assert.rejects(budget.reserve("unknown", "base_search", "bad"), /Unknown provider/);
  await assert.rejects(budget.reserve("serpapi", "payment", "bad"), /Unknown provider/);
  await assert.rejects(budget.complete("id", "serpapi", "base_search", 700, "response"), /Invalid HTTP/);
  assert.equal(calls.length, 0);
});

test("only explicit HTTPS, safe provider paths and methods are allowed", () => {
  const allowed = [
    ["GET", "https://serpapi.com/search.json?departure_token=secret", "serpapi", "return_selection"],
    ["GET", "https://www.searchapi.io/api/v1/search?booking_token=secret", "searchapi", "booking_options"],
    ["GET", "https://serpapi.com/account.json", "serpapi", "quota"],
    ["POST", "https://api.duffel.com/air/offer_requests", "duffel", "base_search"],
    ["GET", "https://api.duffel.com/air/offers/off_123", "duffel", "exact_offer"],
    ["GET", "https://api.travelpayouts.com/aviasales/v3/prices_for_dates", "travelpayouts", "base_search"],
  ];
  for (const [method, url, provider, category] of allowed) {
    assert.deepEqual(classifyProviderRequest(method, url), { provider, category });
  }
  for (const [method, url] of [
    ["POST", "https://api.duffel.com/air/orders"],
    ["GET", "https://serpapi.com:444/search"],
    ["GET", "http://serpapi.com/search"],
    ["GET", "https://serpapi.com.evil.invalid/search"],
    ["GET", "https://user:password@serpapi.com/search"],
    ["GET", "https://api.duffel.com/air/offers/"],
    ["POST", "https://serpapi.com/search.json"],
    ["GET", "not a url"],
  ]) {
    assert.equal(classifyProviderRequest(method, url), null, url);
  }
});

test("reserve is completed before returning an HTTP result; never follow redirects", async () => {
  const events = [];
  const budget = {
    async reserve(provider, category) {
      events.push(["reserve", provider, category]);
      return "r3";
    },
    async complete(id, provider, category, code, outcome) {
      events.push(["complete", id, provider, category, code, outcome]);
    },
  };
  const response = await meteredProviderFetch(
    budget,
    "https://serpapi.com/search.json?api_key=PRIVATE",
    { headers: { "x-test": "ok" } },
    async (_url, init) => {
      events.push(["fetch", init.redirect]);
      return { status: 429 };
    },
  );
  assert.equal(response.status, 429);
  assert.deepEqual(events, [
    ["reserve", "serpapi", "base_search"],
    ["fetch", "manual"],
    ["complete", "r3", "serpapi", "base_search", 429, "rate_limited"],
  ]);
  assert.ok(!JSON.stringify(events).includes("PRIVATE"));
});

test("deny before egress on exhausted caps and record transport errors", async () => {
  let calls = 0;
  const budget = {
    async reserve() { throw new Error("cap exceeded"); },
    async complete() { throw new Error("should not complete"); },
  };
  await assert.rejects(
    meteredProviderFetch(budget, "https://api.duffel.com/air/offer_requests", { method: "POST" }, async () => {
      calls++;
      return { status: 200 };
    }),
    /cap exceeded/,
  );
  assert.equal(calls, 0);
  const outcomes = [];
  const counting = {
    async reserve() { return "r4"; },
    async complete(...args) { outcomes.push(args); },
  };
  await assert.rejects(
    meteredProviderFetch(counting, "https://serpapi.com/search", {}, async () => {
      throw new Error("synthetic network failure");
    }),
    /synthetic network failure/,
  );
  assert.deepEqual(outcomes, [["r4", "serpapi", "base_search", null, "transport_error"]]);
});
