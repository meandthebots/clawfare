import assert from "node:assert/strict";
import test from "node:test";
import { webcrypto } from "node:crypto";
import {
  D1ProviderPersistence, baseCallCacheDigest,
  INSERT_ATTEMPT_SQL, FINISH_ATTEMPT_SQL, LIST_ATTEMPTS_SQL,
  READ_PROVIDER_CACHE_SQL, WRITE_PROVIDER_CACHE_SQL,
} from "../src/provider-persistence.mjs";

const OWNER_A = "acct_" + "a".repeat(64);
const OWNER_B = "acct_" + "b".repeat(64);
const DIGEST = "c".repeat(64);
const ROUTE = "d".repeat(64);
const NONCE = "e".repeat(32);
const TOKEN = "private-booking-token-not-for-cache";
const canonicalQuery = JSON.stringify({
  origins: ["LHR"], destinations: ["NRT"], outboundFrom: "2027-04-12",
  cabin: "business", adults: 2, market: "uk", currency: "GBP", providers: ["serpapi"],
});
const providerCall = {
  canonicalQueryJson: canonicalQuery, provider: "serpapi", parserVersion: 1,
  params: { departure_id: "LHR", arrival_id: "NRT", outbound_date: "2027-04-12" },
};
const offer = {
  price: 1500.75,
  departureToken: TOKEN,
  bookingToken: TOKEN,
  untrustedRawBody: { token: TOKEN },
  fareBrand: "Business Flex",
  legs: [{
    flightNumber: "BA 7", airline: "British Airways", airlineCode: "BA",
    operatingCarrier: "British Airways", operatingCarrierVerified: true,
    from: "LHR", to: "NRT", departDate: "2027-04-12", arriveDate: "2027-04-13",
    departTime: "13:30", arriveTime: "10:15",
    durationMinutes: 780, cabin: "Business", aircraft: "Boeing 787",
    bookingToken: TOKEN,
  }],
  layovers: [],
};
function fakeD1() {
  const cache = new Map(), attempts = new Map(), calls = [];
  const cacheId = a => a.slice(0, 4).join(":");
  const attemptId = a => [a[0], a[1], a[3], a[4]].join(":");
  return {
    cache, attempts, calls,
    prepare(sql) {
      return {
        bind(...args) {
          calls.push({ sql, args });
          return {
            async run() {
              if (sql !== WRITE_PROVIDER_CACHE_SQL) throw new Error("Unexpected D1 run");
              const key = cacheId(args);
              const original = cache.get(key);
              if (!original || original.observed_at < args[8]) {
                cache.set(key, {
                  market: args[4], original_currency: args[5], outcome: args[6],
                  offers_json: args[7], observed_at: args[8], expires_at: args[9],
                });
              }
              return { success: true };
            },
            async first() {
              if (sql === READ_PROVIDER_CACHE_SQL) {
                const row = cache.get(cacheId(args));
                return row && row.expires_at > args[4] ? row : null;
              }
              if (sql === INSERT_ATTEMPT_SQL) {
                const key = attemptId(args);
                if (attempts.has(key)) return null;
                attempts.set(key, {
                  owner_id: args[0], consent_nonce: args[1], query_digest: args[2],
                  provider: args[3], route_digest: args[4], investigation_slug: args[5],
                  state: "attempted", attempted_calls: 0, loaded_calls: 0,
                  cached_calls: 0, returned_offers: 0, eligible_offers: 0,
                  error_kind: null, started_at: args[6], finished_at: null,
                });
                return { route_digest: args[4] };
              }
              if (sql === FINISH_ATTEMPT_SQL) {
                const key = [args[8], args[9], args[10], args[11]].join(":");
                const row = attempts.get(key);
                if (!row || row.state !== "attempted") return null;
                Object.assign(row, {
                  state: args[0], attempted_calls: args[1], loaded_calls: args[2],
                  cached_calls: args[3], returned_offers: args[4], eligible_offers: args[5],
                  error_kind: args[6], finished_at: args[7],
                });
                return { route_digest: row.route_digest };
              }
              throw new Error("Unexpected D1 first");
            },
            async all() {
              if (sql !== LIST_ATTEMPTS_SQL) throw new Error("Unexpected D1 all");
              return { results: [...attempts.values()]
                .filter(row => row.owner_id === args[0] && row.consent_nonce === args[1])
                .slice(0, args[2]) };
            },
          };
        },
      };
    },
  };
}
function persistence(db, clock) {
  return new D1ProviderPersistence(db, { cryptoApi: webcrypto, clock: () => clock.now });
}
const at = iso => ({ now: new Date(iso) });

test("cache identity includes Kotlin query, provider parameters, and parser version", async () => {
  const hash = await baseCallCacheDigest(providerCall, webcrypto);
  assert.match(hash, /^[a-f0-9]{64}$/);
  assert.equal(hash, await baseCallCacheDigest({
    ...providerCall, params: Object.fromEntries(Object.entries(providerCall.params).reverse()),
  }, webcrypto));
  for (const changed of [
    { ...providerCall, canonicalQueryJson: canonicalQuery.replace('"adults":2', '"adults":1') },
    { ...providerCall, canonicalQueryJson: canonicalQuery.replace('"business"', '"economy"') },
    { ...providerCall, canonicalQueryJson: canonicalQuery.replace('"GBP"', '"JPY"') },
    { ...providerCall, parserVersion: 2 },
    { ...providerCall, params: { ...providerCall.params, departure_id: "LGW" } },
  ]) {
    assert.notEqual(hash, await baseCallCacheDigest(changed, webcrypto));
  }
  for (const secretParams of [
    { booking_token: TOKEN }, { api_key: TOKEN }, { departure_token: TOKEN },
    { departure_id: "https://provider.example/booking" },
  ]) {
    await assert.rejects(baseCallCacheDigest({ ...providerCall, params: secretParams }, webcrypto));
  }
});

test("cache stores bounded token-free parsed summary and never upgrades it to live", async () => {
  const db = fakeD1(), clock = at("2026-11-18T19:00:00Z");
  const store = persistence(db, clock);
  const cacheDigest = await baseCallCacheDigest(providerCall, webcrypto);
  const identity = { ownerId: OWNER_A, provider: "serpapi", cacheDigest, parserVersion: 1 };
  await assert.rejects(store.cacheBaseOffers({
    ...identity, market: "uk", originalCurrency: "GBP",
    recognizedResponse: true, offers: [offer],
  }), /permission/);
  assert.equal(db.calls.length, 0, "no D1 operation without explicit provider caching permission");
  const metadata = await store.cacheBaseOffers({
    ...identity, market: "uk", originalCurrency: "GBP",
    recognizedResponse: true, offers: [offer], cachingPermitted: true, ttlSeconds: 60,
  });
  assert.equal(metadata.count, 1);
  const rawStored = JSON.stringify([...db.cache.values()]);
  assert.doesNotMatch(rawStored, /private-booking|bookingToken|departureToken|untrustedRawBody/);
  const cached = await store.readBaseOffers(identity);
  assert.equal(cached.sourceLabel, "cached");
  assert.equal(cached.livePrice, false);
  assert.equal(cached.originalCurrency, "GBP");
  assert.equal(cached.offers[0].price, 1500.75);
  assert.equal(cached.offers[0].legs[0].flightNumber, "BA 7");
  assert.ok(!("bookingToken" in cached.offers[0]));
  assert.equal(await store.readBaseOffers({ ...identity, ownerId: OWNER_B }), null);
  clock.now = new Date("2026-11-18T19:01:01Z");
  assert.equal(await store.readBaseOffers(identity), null, "expired fare is not fresh");
});

test("only recognized provider-empty responses can cache verified empty", async () => {
  const db = fakeD1(), clock = at("2026-11-18T19:00:00Z");
  const store = persistence(db, clock);
  const cacheDigest = DIGEST;
  const base = {
    ownerId: OWNER_A, provider: "serpapi", cacheDigest, parserVersion: 1,
    market: "jp", originalCurrency: "JPY", cachingPermitted: true,
  };
  await assert.rejects(store.cacheBaseOffers({
    ...base, offers: [], recognizedResponse: false,
  }), /Unrecognized/);
  await assert.rejects(store.cacheBaseOffers({
    ...base, offers: [{ ...offer, price: 0 }], recognizedResponse: true,
  }), /price/);
  await assert.rejects(store.cacheBaseOffers({
    ...base, offers: [offer], recognizedResponse: true, ttlSeconds: 9999,
  }), /TTL/);
  assert.equal(db.cache.size, 0);
  await store.cacheBaseOffers({ ...base, offers: [], recognizedResponse: true });
  const cached = await store.readBaseOffers(base);
  assert.equal(cached.outcome, "verified_empty");
  assert.equal(cached.offers.length, 0);
  assert.equal(cached.livePrice, false);
});

test("route outcomes are insert-before-fetch, terminal-once and tenant isolated", async () => {
  const db = fakeD1(), clock = at("2026-11-18T19:00:00Z");
  const store = persistence(db, clock);
  const identity = {
    ownerId: OWNER_A, consentNonce: NONCE, queryDigest: DIGEST,
    provider: "serpapi", routeDigest: ROUTE, investigationSlug: "tokyo-2027",
  };
  assert.equal((await store.beginAttempt(identity)).state, "attempted");
  await assert.rejects(store.beginAttempt(identity), /duplicate provider dispatch/);
  assert.deepEqual(await store.listAttempts(OWNER_B, NONCE), [], "cross-user attempt not visible");
  const pending = await store.listAttempts(OWNER_A, NONCE);
  assert.equal(pending[0].state, "attempted");
  assert.equal(pending[0].finishedAt, null, "crashed Worker remains incomplete");
  clock.now = new Date("2026-11-18T19:00:03Z");
  const outcome = {
    ...identity, state: "complete", attemptedCalls: 1, loadedCalls: 1,
    cachedCalls: 0, returnedOffers: 2, eligibleOffers: 0,
  };
  assert.equal((await store.finishAttempt(outcome)).state, "complete");
  assert.equal((await store.listAttempts(OWNER_A, NONCE))[0].eligibleOffers, 0,
    "no eligible flights is distinct from no provider flights");
  await assert.rejects(store.finishAttempt(outcome), /not pending/);
  await assert.rejects(store.finishAttempt({ ...outcome, ownerId: OWNER_B }), /not pending/);
  assert.equal(db.attempts.size, 1);
});

test("partial and failed coverage cannot be called verified empty", async () => {
  const db = fakeD1(), clock = at("2026-11-18T19:00:00Z");
  const store = persistence(db, clock);
  const base = {
    ownerId: OWNER_A, consentNonce: NONCE, queryDigest: DIGEST,
    provider: "serpapi", routeDigest: ROUTE,
  };
  await store.beginAttempt(base);
  for (const bad of [
    { state: "complete", returnedOffers: 0, errorKind: null },
    { state: "empty", returnedOffers: 3, errorKind: null },
    { state: "failed", returnedOffers: 0, errorKind: null },
    { state: "partial", returnedOffers: 1, eligibleOffers: 2, errorKind: "timeout" },
  ]) {
    await assert.rejects(store.finishAttempt({
      ...base, attemptedCalls: 1, loadedCalls: 0, cachedCalls: 0,
      eligibleOffers: 0, ...bad,
    }), /Invalid outcome|Invalid eligible/);
  }
  assert.equal((await store.listAttempts(OWNER_A, NONCE))[0].state, "attempted");
  await store.finishAttempt({
    ...base, state: "partial", attemptedCalls: 1, loadedCalls: 0,
    cachedCalls: 0, returnedOffers: 1, eligibleOffers: 0, errorKind: "timeout",
  });
  assert.equal((await store.listAttempts(OWNER_A, NONCE))[0].state, "partial");
});
