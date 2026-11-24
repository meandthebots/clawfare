import assert from "node:assert/strict";
import test from "node:test";
import { webcrypto } from "node:crypto";
import { D1SearchConsents, INSERT_CONSENT_SQL, CONSUME_CONSENT_SQL, READ_CONSENT_CAPS_SQL } from "../src/search-consent.mjs";
import { RESERVE_SQL } from "../src/provider-http-budget.mjs";
import { executeConsentedOneWaySerpApi, SerpApiTransportError } from "../src/serpapi-metered-transport.mjs";

const owner = "acct_" + "a".repeat(64);
const stranger = "acct_" + "b".repeat(64);
const secret = "test-only-separate-search-signing-secret-longer-than-32";
const key = "test-only-serpapi-credential-never-contact-provider";
const now = () => new Date("2026-11-19T02:00:00Z");
const routeQuery = {
  origins: ["LHR"], destinations: ["NRT"],
  outboundFrom: "2027-04-12", outboundTo: "2027-04-12",
  tripType: "one_way", adults: 2, currency: "GBP", market: "uk",
  cabin: "business", nonstopOnly: false,
  providers: ["serpapi"], confirmed: false, cacheOnly: false,
  fetchReturns: false, maxLiveCalls: 1,
};
const canonical = JSON.stringify(routeQuery);
function engine(calls = []) {
  return {
    planSearchJson(raw) {
      calls.push("plan");
      const query = JSON.parse(raw);
      return JSON.stringify({
        query, pairs: [{
          outbound: query.outboundFrom,
          returning: query.tripType === "one_way" ? null : "2027-04-20",
        }],
      });
    },
    parseGoogleFlightsOffersJson(raw) {
      calls.push("parse");
      const obj = JSON.parse(raw);
      return JSON.stringify((obj.best_flights || []).map(() => ({
        legs: [{ flightNumber: "BA 7", from: "LHR", to: "NRT" }],
        departureToken: "private-departure",
        bookingToken: "private-booking",
      })));
    },
  };
}
function dbWithQuota({ dailyCap = 10, monthlyCap = 20 } = {}) {
  const issued = new Map(), reservations = new Map(), calls = [];
  return {
    issued, reservations, calls, quota: { dailyCap, monthlyCap },
    prepare(sql) {
      return {
        bind(...args) {
          calls.push({ sql, args });
          return {
            async all() {
              if (sql !== READ_CONSENT_CAPS_SQL) throw Error("Unexpected SQL list");
              return { results: [{
                provider: "serpapi", daily_cap: dailyCap, monthly_cap: monthlyCap,
                daily_used: reservations.size, monthly_used: reservations.size,
              }] };
            },
            async run() {
              if (sql !== INSERT_CONSENT_SQL) throw Error("Unexpected insert");
              issued.set(args[0], {
                owner: args[1], digest: args[2], limits: args[3],
                expires: args[5], state: "issued",
              });
              return { success: true };
            },
            async first() {
              if (sql === CONSUME_CONSENT_SQL) {
                const [stamp, nonce, ownerId, digest, limits] = args;
                const row = issued.get(nonce);
                if (!row || row.owner !== ownerId || row.digest !== digest ||
                    row.limits !== limits || row.expires <= stamp || row.state !== "issued") {
                  return null;
                }
                row.state = "consumed";
                return { nonce };
              }
              if (sql === RESERVE_SQL) {
                const [id, category, stamp, day, month, provider] = args;
                if (provider !== "serpapi" || category !== "base_search" ||
                    reservations.has(id) || reservations.size >= dailyCap ||
                    reservations.size >= monthlyCap) return null;
                reservations.set(id, { category, stamp, day, month, phase: "reserved" });
                return { id };
              }
              if (sql.startsWith("UPDATE provider_http_reservations")) {
                const [status, outcome, id, provider, category] = args;
                const row = reservations.get(id);
                if (!row || row.phase !== "reserved" ||
                    provider !== "serpapi" || row.category !== category) return null;
                Object.assign(row, { status, outcome, phase: "completed" });
                return { id };
              }
              throw Error("Unexpected SQL statement: " + sql.slice(0, 60));
            },
          };
        },
      };
    },
  };
}
async function quote(db, source = canonical, limits = [{ provider: "serpapi", category: "base_search", count: 1 }]) {
  const consents = new D1SearchConsents(db, {
    enabled: true, secret, credentialPresence: { serpapi: true },
    clock: now, cryptoApi: webcrypto,
  });
  return (await consents.issue({
    ownerId: owner, canonicalQueryJson: source, callLimits: limits,
  })).token;
}
function runtime(db, token, extra = {}) {
  return {
    db, enabled: true, ownerId: owner, canonicalQueryJson: canonical,
    consentToken: token, consentSigningSecret: secret,
    serpApiKey: key, kotlinEngine: engine(), clock: now, cryptoApi: webcrypto,
    ...extra,
  };
}
function response(body = {
  best_flights: [{ flights: [{ flight_number: "BA 7" }] }],
  other_flights: [],
}, status = 200, headers = {}) {
  return new Response(JSON.stringify(body), {
    status, headers: { "content-type": "application/json", ...headers },
  });
}
async function denyCode(task, code) {
  await assert.rejects(task, error =>
    error instanceof SerpApiTransportError && error.code === code);
}

test("default OFF blocks token processing and network, even if credentials exist", async () => {
  const db = dbWithQuota(), token = await quote(db);
  let contacted = 0;
  await denyCode(executeConsentedOneWaySerpApi(runtime(db, token, {
    enabled: false, fetcher: async () => { contacted++; return response(); },
  })), "disabled");
  assert.equal(db.reservations.size, 0);
  assert.equal(contacted, 0);
  assert.equal([...db.issued.values()][0].state, "issued");
});

test("single one-way route consumes signed approval, reserves D1 before HTTP, returns no tokens", async () => {
  const db = dbWithQuota(), token = await quote(db), seen = [], kotlinCalls = [];
  const fetcher = async (url, init) => {
    assert.equal(db.reservations.size, 1, "reservation must precede provider fetch");
    assert.equal(init.method, "GET");
    assert.equal(init.redirect, "manual");
    assert.equal(init.signal.aborted, false);
    const parsed = new URL(url);
    assert.equal(parsed.origin, "https://serpapi.com");
    assert.equal(parsed.pathname, "/search.json");
    assert.equal(parsed.searchParams.get("engine"), "google_flights");
    assert.equal(parsed.searchParams.get("api_key"), key);
    assert.equal(parsed.searchParams.get("adults"), "2");
    assert.equal(parsed.searchParams.get("travel_class"), "3");
    assert.equal(parsed.searchParams.get("type"), "2");
    assert.equal(parsed.searchParams.get("departure_id"), "LHR");
    assert.equal(parsed.searchParams.get("arrival_id"), "NRT");
    seen.push("fetch");
    return response();
  };
  const first = await executeConsentedOneWaySerpApi(runtime(db, token, {
    fetcher, kotlinEngine: engine(kotlinCalls),
  }));
  assert.equal(first.state, "complete");
  assert.equal(first.plannedCalls, 1);
  assert.equal(first.completedCalls, 1);
  assert.equal(first.attempts[0].offersReported, 1);
  assert.equal(first.attempts[0].sourceLabel, "live");
  assert.equal(first.hasResultSet, false);
  assert.deepEqual(kotlinCalls, ["plan", "parse"]);
  assert.deepEqual(seen, ["fetch"]);
  assert.equal(db.reservations.size, 1);
  assert.equal([...db.reservations.values()][0].phase, "completed");
  const text = JSON.stringify(first);
  assert.doesNotMatch(text, /private-booking|private-departure|api_key|test-only-serpapi/);
  await denyCode(executeConsentedOneWaySerpApi(runtime(db, token, { fetcher })),
    "consent_already_used");
  assert.equal(seen.length, 1, "replayed token never contacts the provider");
});

test("owner theft, exact query edit and changed airline/provider reject before network", async () => {
  const db = dbWithQuota(), token = await quote(db);
  let called = 0;
  const fetcher = async () => { called++; return response(); };
  await denyCode(executeConsentedOneWaySerpApi(runtime(db, token, {
    ownerId: stranger, fetcher,
  })), "consent_wrong_owner");
  await denyCode(executeConsentedOneWaySerpApi(runtime(db, token, {
    canonicalQueryJson: canonical.replace('"LHR"', '"LGW"'), fetcher,
  })), "consent_query_mismatch");
  await denyCode(executeConsentedOneWaySerpApi(runtime(db, token, {
    canonicalQueryJson: JSON.stringify({ ...routeQuery, providers: ["duffel"] }),
    fetcher,
  })), "unsupported_plan");
  assert.equal(called, 0);
  assert.equal([...db.issued.values()][0].state, "issued");
});

test("wrong-category quote is atomically consumed but authorizes zero HTTP", async () => {
  const db = dbWithQuota(), token = await quote(db, canonical,
    [{ provider: "serpapi", category: "return_selection", count: 1 }]);
  await denyCode(executeConsentedOneWaySerpApi(runtime(db, token, {
    fetcher: async () => { throw Error("must never contact provider"); },
  })), "consent_call_limit_mismatch");
  assert.equal(db.reservations.size, 0);
  assert.equal([...db.issued.values()][0].state, "consumed");
});

test("budget exhaustion between quote and dispatch fails closed without network", async () => {
  const db = dbWithQuota({ dailyCap: 1 }), token = await quote(db);
  db.reservations.set("another-worker-reserved", { category: "base_search", phase: "reserved" });
  let calls = 0;
  const out = await executeConsentedOneWaySerpApi(runtime(db, token, {
    fetcher: async () => { calls++; return response(); },
  }));
  assert.equal(out.state, "failed");
  assert.equal(out.completedCalls, 0);
  assert.equal(out.errorCode, "quota_exhausted");
  assert.equal(calls, 0);
  assert.equal(db.reservations.size, 1);
});

test("provider 429, redirect, malformed payload, auth error and oversized response never retry", async () => {
  for (const [http, expected] of [
    [response({ error: "billable vendor detail" }, 429), "provider_rate_limited"],
    [response({}, 302, { location: "https://attacker.example" }), "provider_redirect"],
    [response({ unrelated: "not a result" }), "invalid_response"],
    [response({ error: "private provider message" }), "provider_http"],
    [response({}, 401), "provider_auth"],
    [response({ best_flights: [] }, 200, { "content-type": "text/html" }), "invalid_response"],
    [response({ best_flights: [] }, 200, { "content-length": "300000" }), "response_too_large"],
  ]) {
    const db = dbWithQuota(), token = await quote(db);
    let fetched = 0;
    const outcome = await executeConsentedOneWaySerpApi(runtime(db, token, {
      fetcher: async () => { fetched++; return http.clone(); },
    }));
    assert.equal(outcome.state, "failed", expected);
    assert.equal(outcome.errorCode, expected);
    assert.equal(fetched, 1);
    assert.equal(db.reservations.size, 1);
    assert.equal([...db.reservations.values()][0].phase, "completed");
    assert.doesNotMatch(JSON.stringify(outcome), /attacker|private provider|api_key/);
  }
});

test("strict one-way planning rejects round trip, oversized matrix and unknown currency before consent", async () => {
  const scenarios = [
    { ...routeQuery, tripType: "round_trip", nights: 8 },
    { ...routeQuery, maxLiveCalls: 0 },
    { ...routeQuery, origins: ["LHR", "LGW"] },
    { ...routeQuery, currency: "usd" },
    { ...routeQuery, fetchReturns: true },
  ];
  for (const bad of scenarios) {
    const db = dbWithQuota(), token = await quote(db);
    await denyCode(executeConsentedOneWaySerpApi(runtime(db, token, {
      canonicalQueryJson: JSON.stringify(bad),
    })), bad.origins?.length > 1 ? "request_limit" : "unsupported_plan");
    assert.equal([...db.issued.values()][0].state, "issued");
    assert.equal(db.reservations.size, 0);
  }
});

test("aborted network request is metered once and never retried", async () => {
  const db = dbWithQuota(), token = await quote(db);
  let called = 0;
  const result = await executeConsentedOneWaySerpApi(runtime(db, token, {
    timeoutMs: 5,
    fetcher: async (_url, init) => {
      called++;
      await new Promise((resolve, reject) => {
        init.signal.addEventListener("abort", () => reject(new Error("simulated abort")), { once: true });
      });
      return response();
    },
  }));
  assert.equal(result.state, "failed");
  assert.equal(result.errorCode, "provider_timeout");
  assert.equal(called, 1);
  assert.equal(db.reservations.size, 1);
  assert.equal([...db.reservations.values()][0].outcome, "transport_error");
});
