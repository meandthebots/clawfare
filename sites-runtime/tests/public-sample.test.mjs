import assert from "node:assert/strict";
import test from "node:test";
import { webcrypto } from "node:crypto";
import {
  bootstrapPublicSample, loadPublicSample, validatePublicSample,
  ensureBundledPublicSample, publicSampleStorageEvidence, GET_PUBLIC_SAMPLE_METADATA_SQL,
  INSERT_PUBLIC_SAMPLE_IF_MISSING_SQL,
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
              assert.ok([UPSERT_PUBLIC_SAMPLE_SQL, INSERT_PUBLIC_SAMPLE_IF_MISSING_SQL].includes(sql));
              if (sql === UPSERT_PUBLIC_SAMPLE_SQL || !row) {
                row = { result_json: args[1], content_sha256: args[2], imported_at: args[3] };
              }
              return { success: true };
            },
            async first() {
              assert.ok([GET_PUBLIC_SAMPLE_SQL, GET_PUBLIC_SAMPLE_METADATA_SQL].includes(sql));
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


test("an authenticated first GET seeds the fixed public Kotlin fixture exactly once", async () => {
  const db = fakeDatabase();
  const request = new Request("https://example.com/api/sample");
  const context = {
    db, ownerKeySecret: "this-is-a-stable-secret-longer-than-thirty-two-bytes",
    cryptoApi: webcrypto, bundledPublicSample: base,
    getVerifiedEmail: async () => "alice@example.com",
  };
  const first = await handleReadOnlyApi(request, context);
  assert.equal(first.status, 200);
  assert.deepEqual(await first.json(), base);
  assert.deepEqual(db.calls.map(x => x.sql), [
    GET_PUBLIC_SAMPLE_SQL, INSERT_PUBLIC_SAMPLE_IF_MISSING_SQL, GET_PUBLIC_SAMPLE_SQL,
  ]);
  const second = await handleReadOnlyApi(request, context);
  assert.equal(second.status, 200);
  assert.deepEqual(await second.json(), base);
  assert.equal(db.calls.filter(x => x.sql === INSERT_PUBLIC_SAMPLE_IF_MISSING_SQL).length, 1);
  const direct = await ensureBundledPublicSample(db, base, { cryptoApi: webcrypto });
  assert.deepEqual(direct, base);
  assert.equal(db.calls.filter(x => x.sql === INSERT_PUBLIC_SAMPLE_IF_MISSING_SQL).length, 1);
});

test("anonymous and spoofed requests cannot trigger even an internal sample seed", async () => {
  const db = fakeDatabase();
  const context = {
    db, ownerKeySecret: "this-is-a-stable-secret-longer-than-thirty-two-bytes",
    cryptoApi: webcrypto, bundledPublicSample: base,
    getVerifiedEmail: async () => null,
  };
  const req = new Request("https://example.com/api/sample", {
    headers: { "oai-authenticated-user-email": "alice@example.com" },
  });
  assert.equal((await handleReadOnlyApi(req, context)).status, 401);
  assert.equal((await handleReadOnlyApi(
    new Request("https://example.com/api/sample", { method: "POST" }), context,
  )).status, 405);
  assert.deepEqual(db.calls, []);
});

test("concurrent first requests are idempotent; mismatched or corrupt fixtures fail closed", async () => {
  const db = fakeDatabase();
  const options = { cryptoApi: webcrypto, clock: () => new Date("2026-11-16T12:00:00Z") };
  const [a, b] = await Promise.all([
    ensureBundledPublicSample(db, base, options),
    ensureBundledPublicSample(db, base, options),
  ]);
  assert.deepEqual(a, base);
  assert.deepEqual(b, base);
  const conflict = { ...base, title: "New Kotlin release" };
  await assert.rejects(
    ensureBundledPublicSample(db, conflict, options),
    /differs from bundled Kotlin fixture/,
  );
  assert.equal((await loadPublicSample(db, options)).title, base.title);
  db.corrupt();
  const writes = db.calls.filter(x => x.sql === INSERT_PUBLIC_SAMPLE_IF_MISSING_SQL).length;
  await assert.rejects(
    ensureBundledPublicSample(db, base, options),
    /digest mismatch/,
  );
  assert.equal(db.calls.filter(x => x.sql === INSERT_PUBLIC_SAMPLE_IF_MISSING_SQL).length, writes);
  await assert.rejects(ensureBundledPublicSample(db, {
    ...base, itineraries: [{ ...base.itineraries[0], livePrice: true }],
  }, options), /sample-only/);
});

test("read-only storage evidence verifies D1 sample digest and stable first import timestamp", async () => {
  const db = fakeDatabase();
  const options = { cryptoApi: webcrypto, clock: () => new Date("2026-11-16T12:00:00Z") };
  const before = await publicSampleStorageEvidence(db, options);
  assert.deepEqual(before, {
    backend: "d1", sampleStored: false, firstImportedAt: null, sha256: null,
  });
  const digest = await bootstrapPublicSample(db, base, options);
  db.calls.length = 0;
  const first = await publicSampleStorageEvidence(db, options);
  assert.deepEqual(first, {
    backend: "d1", sampleStored: true,
    firstImportedAt: "2026-11-16T12:00:00.000Z",
    sha256: digest,
  });
  assert.deepEqual(db.calls.map(x => x.sql), [
    GET_PUBLIC_SAMPLE_SQL, GET_PUBLIC_SAMPLE_METADATA_SQL,
  ]);
  const again = await publicSampleStorageEvidence(db, options);
  assert.deepEqual(again, first);
  db.corrupt();
  await assert.rejects(publicSampleStorageEvidence(db, options), /digest mismatch/);
});
