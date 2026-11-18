import assert from "node:assert/strict";
import test from "node:test";
import { webcrypto } from "node:crypto";
import {
  D1SearchConsents, SearchConsentError, requireSameOrigin,
  INSERT_CONSENT_SQL, CONSUME_CONSENT_SQL,
} from "../src/search-consent.mjs";
import { READ_LOCAL_PROVIDER_CAPS_SQL } from "../src/provider-http-budget.mjs";

const ownerA = "acct_" + "a".repeat(64);
const ownerB = "acct_" + "b".repeat(64);
const secret = "synthetic-hosted-consent-signing-secret-at-least-32-bytes";
const query = JSON.stringify({
  origins: ["LHR"], destinations: ["NRT"],
  outboundFrom: "2027-04-12", outboundTo: "2027-04-12",
  adults: 2, providers: ["serpapi"], tripType: "one_way", confirmed: false,
});
const calls = [{ provider: "serpapi", category: "base_search", count: 1 }];
const today = new Date("2026-11-18T10:00:00Z");
function fakeD1({ caps = [
  { provider: "serpapi", daily_cap: 3, monthly_cap: 6, daily_used: 0, monthly_used: 0 },
] } = {}) {
  const tokens = new Map();
  let writes = 0;
  let reads = 0;
  return {
    tokens,
    get writes() { return writes; },
    get reads() { return reads; },
    prepare(sql) {
      if (![READ_LOCAL_PROVIDER_CAPS_SQL, INSERT_CONSENT_SQL, CONSUME_CONSENT_SQL].includes(sql)) {
        throw new Error("Unexpected D1 statement");
      }
      return {
        bind(...args) {
          return {
            async all() {
              if (sql !== READ_LOCAL_PROVIDER_CAPS_SQL) throw new Error("Unexpected all");
              reads++;
              return { results: caps };
            },
            async run() {
              if (sql !== INSERT_CONSENT_SQL) throw new Error("Unexpected insert");
              writes++;
              const [nonce, ownerId, digest, limits, createdAt, expiresAt] = args;
              if (tokens.has(nonce)) throw new Error("Duplicate nonce");
              tokens.set(nonce, { ownerId, digest, limits, createdAt, expiresAt, state: "issued" });
              return { success: true };
            },
            async first() {
              if (sql !== CONSUME_CONSENT_SQL) throw new Error("Unexpected consume");
              writes++;
              const [now, nonce, ownerId, digest, limits, expiryNow] = args;
              assert.equal(now, expiryNow);
              const row = tokens.get(nonce);
              if (!row || row.state !== "issued" || row.ownerId !== ownerId ||
                  row.digest !== digest || row.limits !== limits || row.expiresAt <= now) {
                return null;
              }
              row.state = "consumed";
              row.consumedAt = now;
              return { nonce };
            },
          };
        },
      };
    },
  };
}
function service(db, more = {}) {
  return new D1SearchConsents(db, {
    secret, enabled: true, cryptoApi: webcrypto,
    credentialPresence: { serpapi: true }, clock: () => today,
    ...more,
  });
}
async function codeOf(promise) {
  try { await promise; } catch (error) {
    assert.ok(error instanceof SearchConsentError, String(error));
    return error.code;
  }
  assert.fail("Expected search authorization denial");
}
test("issue binds exact Kotlin JSON/owner/provider/limits and atomically consumes once", async () => {
  const db = fakeD1();
  const a = service(db);
  const quote = await a.issue({ ownerId: ownerA, canonicalQueryJson: query, callLimits: calls });
  assert.equal(quote.totalCalls, 1);
  assert.equal(quote.callLimits[0].provider, "serpapi");
  assert.equal(db.tokens.size, 1);
  assert.equal(db.reads, 1);
  const nextWorker = service(db);
  const approved = await nextWorker.consume({
    ownerId: ownerA, canonicalQueryJson: query, token: quote.token,
  });
  assert.equal(approved.totalCalls, 1);
  assert.match(approved.queryDigest, /^[a-f0-9]{64}$/);
  assert.equal(await codeOf(a.consume({
    ownerId: ownerA, canonicalQueryJson: query, token: quote.token,
  })), "already_used");
  assert.equal(db.tokens.values().next().value.state, "consumed");
  assert.ok(!JSON.stringify([...db.tokens.values()]).includes("LHR"));
  assert.ok(!JSON.stringify([...db.tokens.values()]).includes("synthetic-hosted-consent-signing"));
});
test("cross-owner, changed query and forged signature fail without consuming D1 nonce", async () => {
  const db = fakeD1();
  const a = service(db);
  const { token } = await a.issue({ ownerId: ownerA, canonicalQueryJson: query, callLimits: calls });
  const priorWrites = db.writes;
  assert.equal(await codeOf(a.consume({
    ownerId: ownerB, canonicalQueryJson: query, token,
  })), "wrong_owner");
  assert.equal(await codeOf(a.consume({
    ownerId: ownerA, canonicalQueryJson: query.replace("NRT", "HND"), token,
  })), "query_mismatch");
  const parts = token.split(".");
  parts[0] = (parts[0][0] === "A" ? "B" : "A") + parts[0].slice(1);
  assert.equal(await codeOf(a.consume({
    ownerId: ownerA, canonicalQueryJson: query, token: parts.join("."),
  })), "invalid_token");
  assert.equal(db.writes, priorWrites, "no failed validation can consume approval");
  assert.equal((await a.consume({ ownerId: ownerA, canonicalQueryJson: query, token })).totalCalls, 1);
});
test("expiry, policy rotation and disabled release switch deny without HTTP", async () => {
  const db = fakeD1();
  const issuer = service(db);
  const { token } = await issuer.issue({
    ownerId: ownerA, canonicalQueryJson: query, callLimits: calls, lifetimeMs: 3000,
  });
  assert.equal(await codeOf(service(db, { policy: "hosted-v2" }).consume({
    ownerId: ownerA, canonicalQueryJson: query, token,
  })), "invalid_policy");
  assert.equal(await codeOf(service(db, {
    clock: () => new Date(today.getTime() + 5000),
  }).consume({ ownerId: ownerA, canonicalQueryJson: query, token })), "expired");
  assert.equal(await codeOf(service(db, { enabled: false }).consume({
    ownerId: ownerA, canonicalQueryJson: query, token,
  })), "disabled");
  assert.equal(db.tokens.values().next().value.state, "issued");
});
test("parallel double submit and Worker restart allow exactly one consumption", async () => {
  const db = fakeD1();
  const a = service(db), b = service(db);
  const { token } = await a.issue({ ownerId: ownerA, canonicalQueryJson: query, callLimits: calls });
  const results = await Promise.allSettled([a, b, service(db)].map(instance =>
    instance.consume({ ownerId: ownerA, canonicalQueryJson: query, token })));
  assert.equal(results.filter(result => result.status === "fulfilled").length, 1);
  assert.equal(results.filter(result => result.status === "rejected").length, 2);
  assert.equal(db.tokens.values().next().value.state, "consumed");
});
test("provider caps and secrets fail closed at issue, without stored authorizations", async () => {
  for (const [db, cred] of [
    [fakeD1({ caps: [] }), true],
    [fakeD1({ caps: [{ provider: "serpapi", daily_cap: 0, monthly_cap: null, daily_used: 0, monthly_used: 0 }] }), true],
    [fakeD1(), false],
    [fakeD1({ caps: [{ provider: "serpapi", daily_cap: 2, monthly_cap: 5, daily_used: 2, monthly_used: 0 }] }), true],
  ]) {
    assert.equal(await codeOf(service(db, { credentialPresence: { serpapi: cred } }).issue({
      ownerId: ownerA, canonicalQueryJson: query, callLimits: calls,
    })), "quota_denied");
    assert.equal(db.tokens.size, 0);
  }
  assert.equal(await codeOf(service(fakeD1()).issue({
    ownerId: ownerA, canonicalQueryJson: query,
    callLimits: [{ provider: "serpapi", category: "base_search", count: 17 }],
  })), "invalid_limits");
  assert.equal(await codeOf(service(fakeD1()).issue({
    ownerId: ownerA, canonicalQueryJson: query,
    callLimits: [...calls, ...calls],
  })), "invalid_limits");
});
test("same-origin confirmation requires an explicit Origin; reject spoofed browser origins", () => {
  assert.doesNotThrow(() => requireSameOrigin(new Request("https://flight.example/api/search", {
    method: "POST", headers: { origin: "https://flight.example", "sec-fetch-site": "same-origin" },
  })));
  for (const headers of [
    {},
    { origin: "https://attacker.example" },
    { origin: "https://flight.example", "sec-fetch-site": "cross-site" },
  ]) {
    assert.throws(() => requireSameOrigin(new Request("https://flight.example/api/search", {
      method: "POST", headers,
    })), /origin|confirmation/i);
  }
});
