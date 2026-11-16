import assert from "node:assert/strict";
import test from "node:test";
import { webcrypto } from "node:crypto";
import {
  bootstrapPublicSample, loadPublicSample, validatePublicSample,
  UPSERT_PUBLIC_SAMPLE_SQL, GET_PUBLIC_SAMPLE_SQL,
} from "../src/public-sample.mjs";
import { handleReadOnlyApi } from "../src/read-only-api.mjs";

const base = {
  id: "florida-dec-2026",
  title: "Public Florida sample",
  currency: "GBP",
  itineraries: [{
    id: "lh-1", source: "sample", sourceLabel: "sample",
    livePrice: false, pricePerPerson: 425,
  }],
  addons: [],
  vendors: [],
};

function fakeDatabase() {
  let row = null;
  const calls = [];
  return {
    calls,
    prepare(sql) {
      return {
        bind(...args) {
          calls.push({ sql, args });
          return {
            async run() {
              assert.equal(sql, UPSERT_PUBLIC_SAMPLE_SQL);
              row = { result_json: args[1], content_sha256: args[2] };
              return { success: true };
            },
            async first() {
              assert.equal(sql, GET_PUBLIC_SAMPLE_SQL);
              return row;
            },
          };
        },
      };
    },
    corrupt() {
      if (!row) throw new Error("No sample");
      row = { ...row, result_json: row.result_json + " " };
    },
  };
}

test("only valid public Kotlin sample snapshots can be seeded", () => {
  const encoded = validatePublicSample(base);
  assert.deepEqual(JSON.parse(encoded), base);
  for (const value of [
    { ...base, id: "private-search" },
    { ...base, itineraries: [] },
    { ...base, itineraries: [{ ...base.itineraries[0], livePrice: true }] },
    { ...base, itineraries: [{ ...base.itineraries[0], sourceLabel: "live" }] },
    { ...base, itineraries: [{ ...base.itineraries[0], bookingToken: "sensitive" }] },
    { ...base, secret: "sensitive" },
    { ...base, notes: "x".repeat(600_000) },
  ]) assert.throws(() => validatePublicSample(value));
});

test("seed is idempotent and only verified snapshots are read", async () => {
  const db = fakeDatabase();
  const options = { cryptoApi: webcrypto, clock: () => new Date("2026-11-16T12:00:00Z") };
  const digest = await bootstrapPublicSample(db, base, options);
  assert.match(digest, /^[a-f0-9]{64}$/);
  assert.equal(digest, await bootstrapPublicSample(db, base, options));
  const snapshot = await loadPublicSample(db, { cryptoApi: webcrypto });
  assert.deepEqual(snapshot, base);
  assert.deepEqual(db.calls[0].args, ["florida-dec-2026", JSON.stringify(base), digest, "2026-11-16T12:00:00.000Z"]);
  db.corrupt();
  await assert.rejects(loadPublicSample(db, { cryptoApi: webcrypto }), /digest mismatch/);
});

test("GET /api/sample is authenticated and never accesses tenant investigations", async () => {
  const db = fakeDatabase();
  const options = { cryptoApi: webcrypto, clock: () => new Date("2026-11-16T12:00:00Z") };
  await bootstrapPublicSample(db, base, options);
  db.calls.length = 0;
  const env = {
    db, ownerKeySecret: "this-is-a-stable-secret-longer-than-thirty-two-bytes",
    cryptoApi: webcrypto,
    getVerifiedEmail: async () => "alice@example.com",
  };
  const request = new Request("https://example.com/api/sample");
  const result = await handleReadOnlyApi(request, env);
  assert.equal(result.status, 200);
  assert.deepEqual(await result.json(), base);
  assert.deepEqual(db.calls.map(call => call.sql), [GET_PUBLIC_SAMPLE_SQL]);
  const anonymous = await handleReadOnlyApi(request, { ...env, getVerifiedEmail: async () => null });
  assert.equal(anonymous.status, 401);
  assert.equal(db.calls.length, 1);
  const rejected = await handleReadOnlyApi(new Request("https://example.com/api/sample", { method: "POST" }), env);
  assert.equal(rejected.status, 405);
  assert.equal(db.calls.length, 1);
});
