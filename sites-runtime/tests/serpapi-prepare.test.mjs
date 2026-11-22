import assert from "node:assert/strict";
import test from "node:test";
import { webcrypto } from "node:crypto";
import { prepareSerpApiSearch } from "../src/serpapi-prepare.mjs";
import { handleReadOnlyApi, ownerKeyFromVerifiedEmail } from "../src/read-only-api.mjs";
import {
  D1SearchConsents, READ_CONSENT_CAPS_SQL, INSERT_CONSENT_SQL, CONSUME_CONSENT_SQL,
} from "../src/search-consent.mjs";

const OWNER = "acct_" + "a".repeat(64);
const ownerSecret = "test-owner-scope-secret-32-bytes-minimum";
const consentSecret = "independent-signing-secret-32-bytes-minimum-for-tests";
const clock = () => new Date("2026-11-18T19:00:00Z");
const query = {
  origins: ["LHR"], destinations: ["NRT"],
  outboundFrom: "2027-04-12", outboundTo: "2027-04-12",
  tripType: "round_trip", nights: 7, adults: 2,
  currency: "GBP", market: "uk", cabin: "business",
  providers: ["serpapi"], cacheOnly: false, confirmed: false,
  fetchReturns: true, returnChoices: 3, maxLiveCalls: 4,
};
const endpoint = "https://clawfare.example/api/prepare-search";
function req(value = query, headers = {}) {
  return new Request(endpoint, {
    method: "POST",
    headers: { "content-type": "application/json",
      origin: "https://clawfare.example", "sec-fetch-site": "same-origin", ...headers },
    body: JSON.stringify(value),
  });
}
function engine(log = []) {
  return {
    planSearchJson(raw) {
      log.push("kotlin");
      const q = JSON.parse(raw);
      const plan = { ...q,
        adults: q.adults ?? 1,
        outboundTo: q.outboundTo || q.outboundFrom,
        returnChoices: q.returnChoices ?? 3,
        fetchReturns: q.fetchReturns ?? true,
        cacheOnly: q.cacheOnly ?? false,
        confirmed: q.confirmed ?? false,
      };
      return JSON.stringify({ query: plan, pairs: [{
        outbound: plan.outboundFrom,
        returning: plan.tripType === "one_way" ? null : "2027-04-19",
      }] });
    },
  };
}
function fakeD1({ dailyCap = 10, monthlyCap = 100, dailyUsed = 0, monthlyUsed = 0 } = {}) {
  const writes = [], calls = [], tokens = new Map();
  const row = {
    provider: "serpapi", daily_cap: dailyCap, monthly_cap: monthlyCap,
    daily_used: dailyUsed, monthly_used: monthlyUsed,
  };
  return {
    writes, calls, tokens,
    prepare(sql) {
      return {
        bind(...args) {
          calls.push({ sql, args });
          return {
            async all() {
              if (sql !== READ_CONSENT_CAPS_SQL) throw new Error("Unexpected SQL all");
              return { results: [row] };
            },
            async run() {
              if (sql !== INSERT_CONSENT_SQL) throw new Error("Unexpected SQL write");
              writes.push(args);
              tokens.set(args[0], { owner: args[1], digest: args[2], limits: args[3],
                expiresAt: args[5], state: "issued" });
              return { success: true };
            },
            async first() {
              if (sql !== CONSUME_CONSENT_SQL) throw new Error("Unexpected SQL consume");
              const [now, nonce, owner, digest, limits] = args;
              const token = tokens.get(nonce);
              if (!token || token.state !== "issued" || token.owner !== owner ||
                  token.digest !== digest || token.limits !== limits ||
                  token.expiresAt <= now) return null;
              token.state = "consumed";
              return { nonce };
            },
          };
        },
      };
    },
  };
}
function options(db, more = {}) {
  return {
    db, ownerId: OWNER, kotlinEngine: engine(), consentSigningSecret: consentSecret,
    providerKeyPresent: true, enabled: true, clock, cryptoApi: webcrypto, ...more,
  };
}

test("disabled prepare has no Kotlin planning, D1 reads or egress", async () => {
  const db = fakeD1(), log = [];
  const result = await prepareSerpApiSearch(req(), options(db, {
    enabled: false, kotlinEngine: engine(log),
  }));
  assert.equal(result.status, 405);
  assert.deepEqual(db.calls, []);
  assert.deepEqual(log, []);
});

test("quote binds Kotlin-normalized 2-adult SerpApi query and exact worst-case calls", async () => {
  const db = fakeD1();
  const response = await prepareSerpApiSearch(req(), options(db));
  assert.equal(response.status, 200);
  const body = await response.json();
  assert.deepEqual(body.providers, ["serpapi"]);
  assert.equal(body.canonicalQuery.adults, 2);
  assert.match(body.queryDigest, /^[a-f0-9]{64}$/);
  const expectedDigest = await webcrypto.subtle.digest(
    "SHA-256", new TextEncoder().encode(JSON.stringify(body.canonicalQuery)));
  assert.equal(body.queryDigest, Buffer.from(expectedDigest).toString("hex"));
  assert.equal(body.baseCallsUpperBound, 1);
  assert.equal(body.returnLookupsUpperBound, 3);
  assert.equal(body.totalCallsUpperBound, 4);
  assert.equal(body.bookingOptionCallsUpperBound, 0);
  assert.equal(body.canExecuteHere, false);
  assert.equal(body.cost.vendorBillingKnown, false);
  assert.equal(db.writes.length, 1);
  assert.ok(!JSON.stringify(db.calls).includes(consentSecret));
  const consumer = new D1SearchConsents(db, {
    enabled: true, secret: consentSecret, clock, cryptoApi: webcrypto,
  });
  const consumed = await consumer.consume({
    ownerId: OWNER, canonicalQueryJson: JSON.stringify(body.canonicalQuery),
    token: body.consentToken,
  });
  assert.equal(consumed.totalCalls, 4);
  assert.deepEqual(consumed.callLimits, [
    { provider: "serpapi", category: "base_search", count: 1 },
    { provider: "serpapi", category: "return_selection", count: 3 },
  ]);
  await assert.rejects(consumer.consume({
    ownerId: OWNER, canonicalQueryJson: JSON.stringify(body.canonicalQuery),
    token: body.consentToken,
  }), error => error.code === "already_used");
});

test("insufficient D1 daily/monthly budget never issues a nonce", async () => {
  for (const caps of [
    { dailyCap: 0 }, { dailyCap: 3 }, { monthlyCap: 3 },
    { dailyCap: 10, dailyUsed: 9 }, { monthlyCap: 5, monthlyUsed: 2 },
  ]) {
    const db = fakeD1(caps);
    const response = await prepareSerpApiSearch(req(), options(db));
    assert.equal(response.status, 429, JSON.stringify(caps));
    assert.equal(db.writes.length, 0);
    assert.ok((await response.json()).error.includes("Local provider budget"));
  }
});

test("changed provider, unbounded calls, cache-only and client-confirmed reject before D1 writes", async () => {
  const db = fakeD1();
  const variants = [
    [{ ...query, providers: ["duffel"] }, 503],
    [{ ...query, providers: ["serpapi", "searchapi"] }, 422],
    [{ ...query, maxLiveCalls: 3 }, 422],
    [{ ...query, maxLiveCalls: null }, 422],
    [{ ...query, maxLiveCalls: 16, returnChoices: 8,
      origins: ["LHR", "LGW", "MAN"] }, 422],
    [{ ...query, cacheOnly: true }, 400],
    [{ ...query, confirmed: true }, 400],
    [{ ...query, tripType: "one_way", nights: null, fetchReturns: false }, 200],
  ];
  for (const [candidate, status] of variants) {
    const response = await prepareSerpApiSearch(req(candidate), options(db));
    assert.equal(response.status, status, JSON.stringify(candidate));
    if (status === 200) {
      assert.equal((await response.json()).returnLookupsUpperBound, 0);
    }
  }
  assert.equal(db.writes.length, 1);
});

test("missing credential, spoofed Origin and oversized payload deny without consent", async () => {
  const db = fakeD1();
  assert.equal((await prepareSerpApiSearch(req(), options(db, {
    providerKeyPresent: false,
  }))).status, 503);
  assert.equal((await prepareSerpApiSearch(req(query, {
    origin: "https://attacker.example",
  }), options(db))).status, 403);
  assert.equal((await prepareSerpApiSearch(new Request(endpoint, {
    method: "POST", headers: { "content-type": "application/json" },
    body: JSON.stringify(query),
  }), options(db))).status, 403);
  assert.equal((await prepareSerpApiSearch(new Request(endpoint, {
    method: "POST", headers: {
      "content-type": "application/json", origin: "https://clawfare.example",
    },
    body: "x".repeat(20_000),
  }), options(db))).status, 413);
  assert.deepEqual(db.writes, []);
});

test("only trusted Sites authentication can reach enabled prepare; search stays 405", async () => {
  const db = fakeD1();
  const config = {
    db, ownerKeySecret: ownerSecret, cryptoApi: webcrypto,
    kotlinEngine: engine(),
    getVerifiedEmail: async () => null,
    searchPrepareConfig: {
      enabled: true, consentSigningSecret: consentSecret,
      providerKeyPresent: true, clock,
    },
  };
  const forged = await handleReadOnlyApi(req(query, {
    "oai-authenticated-user-email": "owner@example.com",
  }), config);
  assert.equal(forged.status, 401);
  assert.equal(db.calls.length, 0);
  const authenticated = await handleReadOnlyApi(req(), {
    ...config, getVerifiedEmail: async () => "owner@example.com",
  });
  assert.equal(authenticated.status, 200);
  const tokenBody = await authenticated.json();
  const expectedOwner = await ownerKeyFromVerifiedEmail("owner@example.com", ownerSecret, webcrypto);
  assert.equal(db.writes[0][1], expectedOwner);
  assert.equal(tokenBody.totalCallsUpperBound, 4);
  const searchRequest = new Request("https://clawfare.example/api/search", {
    method: "POST", headers: { "content-type": "application/json",
      origin: "https://clawfare.example" }, body: JSON.stringify(query),
  });
  const stillDenied = await handleReadOnlyApi(searchRequest, {
    ...config, getVerifiedEmail: async () => "owner@example.com",
  });
  assert.equal(stillDenied.status, 405);
});
