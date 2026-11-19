import assert from "node:assert/strict";
import test from "node:test";
import { webcrypto } from "node:crypto";
import { handleReadOnlyApi, ownerKeyFromVerifiedEmail } from "../src/read-only-api.mjs";
import {
  GET_INVESTIGATION_SQL, LIST_INVESTIGATIONS_SQL, LIST_PRICE_OBSERVATIONS_SQL,
} from "../src/investigations.mjs";
import { LIST_ATTEMPTS_SQL } from "../src/provider-persistence.mjs";

const secret = "a-strong-server-side-secret-with-at-least-32-bytes";
function call(path, method = "GET", headers = {}) {
  return new Request("https://clawfare.example" + path, { method, headers });
}
function mockDatabase(accounts = new Map(), attempts = new Map()) {
  const calls = [];
  return {
    calls,
    prepare(statement) {
      return {
        bind(...args) {
          calls.push({ statement, args });
          return {
            async first() {
              if (statement !== GET_INVESTIGATION_SQL) throw new Error("Unexpected D1 read");
              const item = accounts.get(args[0])?.[args[1]];
              return item ? { result_json: JSON.stringify(item) } : null;
            },
            async all() {
              if (statement === LIST_INVESTIGATIONS_SQL) {
                return { results: Object.entries(accounts.get(args[0]) || {})
                  .map(([slug, result]) => ({
                    slug, title: result.title, fetched_at: result.fetchedAt,
                    updated_at: "2026-11-16T00:00:00Z",
                  })).slice(0, args[1]) };
              }
              if (statement === LIST_PRICE_OBSERVATIONS_SQL) return { results: [] };
              if (statement === LIST_ATTEMPTS_SQL) {
                return { results: (attempts.get(args[0]) || []).slice(0, args[2]) };
              }
              throw new Error("Unexpected query");
            },
          };
        },
      };
    },
  };
}
function config(db, email, extra = {}) {
  return {
    db,
    getVerifiedEmail: async () => email,
    ownerKeySecret: secret,
    cryptoApi: webcrypto,
    ...extra,
  };
}

test("owner keys are stable, case-insensitive, pseudonymous and site-secret scoped", async () => {
  const key = await ownerKeyFromVerifiedEmail("Person@Example.com", secret, webcrypto);
  assert.match(key, /^acct_[a-f0-9]{64}$/);
  assert.equal(key, await ownerKeyFromVerifiedEmail("person@example.com", secret, webcrypto));
  assert.notEqual(key, await ownerKeyFromVerifiedEmail("other@example.com", secret, webcrypto));
  assert.notEqual(key, await ownerKeyFromVerifiedEmail("person@example.com",
    "different-server-secret-longer-than-32-bytes", webcrypto));
  assert.ok(!key.includes("example"));
  await assert.rejects(ownerKeyFromVerifiedEmail("person@example.com", "short", webcrypto), /secret/);
  await assert.rejects(ownerKeyFromVerifiedEmail("not-an-email", secret, webcrypto), /identity/);
});

test("forged identity headers cannot bypass missing trusted gateway identity", async () => {
  const db = mockDatabase();
  const response = await handleReadOnlyApi(
    call("/api/searches", "GET", { "oai-authenticated-user-email": "admin@example.com" }),
    config(db, null),
  );
  assert.equal(response.status, 401);
  assert.match(await response.text(), /Sign in/);
  assert.equal(db.calls.length, 0);
  await assert.rejects(
    handleReadOnlyApi(call("/api/searches"), { db, ownerKeySecret: secret, cryptoApi: webcrypto }),
    /Trusted identity gateway/,
  );
});

test("each logged-in viewer sees only own saved investigations", async () => {
  const ownerA = await ownerKeyFromVerifiedEmail("a@example.com", secret, webcrypto);
  const ownerB = await ownerKeyFromVerifiedEmail("b@example.com", secret, webcrypto);
  const accounts = new Map([
    [ownerA, { "same-slug": { id: "same-slug", title: "Private A", itineraries: [] } }],
    [ownerB, { "same-slug": { id: "same-slug", title: "Private B", itineraries: [] } }],
  ]);
  const db = mockDatabase(accounts);
  const first = await handleReadOnlyApi(call("/api/results/same-slug"), config(db, "a@example.com"));
  const second = await handleReadOnlyApi(call("/api/results/same-slug"), config(db, "b@example.com"));
  assert.equal((await first.json()).title, "Private A");
  assert.equal((await second.json()).title, "Private B");
  assert.equal(db.calls[0].args[0], ownerA);
  assert.equal(db.calls[1].args[0], ownerB);
  assert.ok(!JSON.stringify(db.calls).includes("a@example.com"));
  assert.equal(first.headers.get("cache-control"), "private, no-store");
  assert.equal(first.headers.get("x-content-type-options"), "nosniff");
});

test("stored search listing has JVM-compatible id/title fields and no email", async () => {
  const owner = await ownerKeyFromVerifiedEmail("a@example.com", secret, webcrypto);
  const db = mockDatabase(new Map([[owner, {
    "florida-dec-2026": {
      id: "florida-dec-2026",
      title: "Florida sample",
      fetchedAt: "2026-10-11T18:00:00Z",
    },
  }]]));
  const listed = await handleReadOnlyApi(call("/api/searches?limit=10"), config(db, "a@example.com"));
  const result = await listed.json();
  assert.equal(result[0].id, "florida-dec-2026");
  assert.equal(result[0].title, "Florida sample");
  assert.equal(result[0].subtitle, "Saved investigation");
  assert.equal(db.calls[0].args[1], 10);
  const bad = await handleReadOnlyApi(call("/api/searches?limit=1000000"), config(db, "a@example.com"));
  assert.equal(bad.status, 400);
  assert.equal(db.calls.length, 1);
});

test("read-only preview disables provider search, export and tracking mutations", async () => {
  const db = mockDatabase();
  for (const path of [
    "/api/estimate", "/api/search", "/api/results/test/vendors",
    "/api/results/test/track", "/api/export/csv", "/api/export/html",
  ]) {
    const response = await handleReadOnlyApi(call(path, "POST"), config(db, "a@example.com"));
    assert.equal(response.status, 405);
    assert.equal(response.headers.get("allow"), "GET");
  }
  assert.equal(db.calls.length, 0);
});

test("only API requests are handled and no quota/network discovery occurs", async () => {
  const db = mockDatabase();
  assert.equal(await handleReadOnlyApi(call("/"), config(db, "a@example.com")), null);
  const status = await handleReadOnlyApi(call("/api/status"), config(db, "a@example.com"));
  assert.deepEqual((await status.json()).providers, []);
  const quota = await handleReadOnlyApi(call("/api/quota"), config(db, "a@example.com"));
  assert.deepEqual(await quota.json(), []);
  const invalid = await handleReadOnlyApi(call("/api/results/bad%2fslug"), config(db, "a@example.com"));
  assert.equal(invalid.status, 404);
  assert.equal(db.calls.length, 0);
});

test("missing, invalid and cross-tenant results do not disclose data", async () => {
  const db = mockDatabase();
  for (const path of ["/api/results/nope", "/api/history/a/b"]) {
    const result = await handleReadOnlyApi(call(path), config(db, "a@example.com"));
    if (path.includes("history")) {
      assert.equal(result.status, 200);
      assert.deepEqual(await result.json(), []);
    } else {
      assert.equal(result.status, 404);
    }
  }
  const malformedIdentity = await handleReadOnlyApi(
    call("/api/results/test"), config(db, "not-an-email"),
  );
  assert.equal(malformedIdentity.status, 401);
});

test("authenticated owner can read only their own bounded search-attempt coverage", async () => {
  const ownerA = await ownerKeyFromVerifiedEmail("a@example.com", secret, webcrypto);
  const ownerB = await ownerKeyFromVerifiedEmail("b@example.com", secret, webcrypto);
  const nonce = "e".repeat(32);
  const attempts = new Map([[ownerA, [{
    provider: "serpapi", route_digest: "a".repeat(64), state: "partial",
    attempted_calls: 1, loaded_calls: 0, cached_calls: 0,
    returned_offers: 0, eligible_offers: 0,
    error_kind: "timeout", started_at: "2026-11-18T19:00:00Z",
    finished_at: "2026-11-18T19:00:03Z",
  }]]]);
  const db = mockDatabase(new Map(), attempts);
  const path = "/api/attempts/" + nonce;
  const first = await handleReadOnlyApi(call(path), config(db, "a@example.com"));
  assert.equal(first.status, 200);
  const rows = await first.json();
  assert.equal(rows.length, 1);
  assert.equal(rows[0].state, "partial");
  assert.equal(rows[0].errorKind, "timeout");
  assert.equal(rows[0].loadedCalls, 0);
  const second = await handleReadOnlyApi(call(path), config(db, "b@example.com"));
  assert.equal(second.status, 200);
  assert.deepEqual(await second.json(), []);
  assert.equal(db.calls[0].statement, LIST_ATTEMPTS_SQL);
  assert.deepEqual(db.calls[0].args, [ownerA, nonce, 50]);
  assert.deepEqual(db.calls[1].args, [ownerB, nonce, 50]);
  assert.ok(!JSON.stringify(db.calls).includes("a@example.com"));
  const invalid = await handleReadOnlyApi(call(path + "?limit=999"), config(db, "a@example.com"));
  assert.equal(invalid.status, 400);
  const badNonce = await handleReadOnlyApi(call("/api/attempts/1234"), config(db, "a@example.com"));
  assert.equal(badNonce.status, 404);
});
