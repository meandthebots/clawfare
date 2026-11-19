import assert from "node:assert/strict";
import test from "node:test";
import { webcrypto } from "node:crypto";
import {
  D1Investigations, UPSERT_INVESTIGATION_SQL, GET_INVESTIGATION_SQL,
  LIST_INVESTIGATIONS_SQL, INSERT_PRICE_OBSERVATION_SQL, LIST_PRICE_OBSERVATIONS_SQL,
} from "../src/investigations.mjs";

function fakeDatabase() {
  const calls = [];
  const db = {
    prepare(statement) {
      return {
        bind(...params) {
          calls.push({ statement, params });
          return {
            async run() { return { success: true }; },
            async first() { return { result_json: '{"id":"x","itineraries":[]}' }; },
            async all() { return { results: [] }; },
          };
        },
      };
    },
  };
  return { db, calls };
}

const owner = "account_abc123";
const snapshot = {
  id: "florida-dec-2026",
  title: "Florida sample",
  fetchedAt: "2026-10-11T18:00:00Z",
  itineraries: [{ id: "lh-1", pricePerPerson: 123.45, sourceLabel: "sample" }],
  warnings: ["Saved prices are not live quotes"],
};
const observation = {
  itineraryId: "lh-1",
  provider: "serpapi",
  sourceLabel: "cached",
  livePrice: false,
  capturedAt: "2026-10-11T18:00:00Z",
  originalCurrency: "GBP",
  amountDecimal: "123.45",
};
function store(db) {
  return new D1Investigations(db, {
    cryptoApi: webcrypto, clock: () => new Date("2026-11-16T12:30:00Z"),
  });
}

test("snapshot insertion uses bound owner, slug and JSON; does not flatten provenance", async () => {
  const { db, calls } = fakeDatabase();
  assert.equal(await store(db).saveSnapshot(owner, snapshot), snapshot.id);
  const row = calls[0];
  assert.equal(row.statement, UPSERT_INVESTIGATION_SQL);
  assert.deepEqual(row.params.slice(0, 2), [owner, snapshot.id]);
  assert.deepEqual(JSON.parse(row.params[2]), snapshot);
  assert.equal(row.params[4], snapshot.fetchedAt);
  assert.equal(row.params[5], "2026-11-16T12:30:00.000Z");
  assert.ok(!JSON.stringify(row.params).includes("api_key"));
});

test("owner scope is bound in every read and bounded result list", async () => {
  const { db, calls } = fakeDatabase();
  const s = store(db);
  assert.deepEqual(await s.loadSnapshot(owner, snapshot.id), { id: "x", itineraries: [] });
  assert.deepEqual(await s.listSnapshots(owner, 20), []);
  assert.equal(calls[0].statement, GET_INVESTIGATION_SQL);
  assert.deepEqual(calls[0].params, [owner, snapshot.id]);
  assert.equal(calls[1].statement, LIST_INVESTIGATIONS_SQL);
  assert.deepEqual(calls[1].params, [owner, 20]);
  await assert.rejects(s.listSnapshots(owner, 1000), /pagination limit/);
});

test("untrusted identity, path or StoredResult wrapper is rejected", async () => {
  const { db, calls } = fakeDatabase();
  const s = store(db);
  await assert.rejects(s.saveSnapshot("x", snapshot), /owner/);
  await assert.rejects(s.loadSnapshot(owner, "' OR 1=1 --"), /slug/);
  await assert.rejects(s.saveSnapshot(owner, { result: snapshot, tokens: { secret: "opaque" } }), /ResultSet/);
  await assert.rejects(s.saveSnapshot(owner, { ...snapshot, tokens: {} }), /ResultSet/);
  await assert.rejects(s.saveSnapshot(owner, { ...snapshot, itineraries: "x" }), /ResultSet/);
  await assert.rejects(s.saveSnapshot(owner, { ...snapshot, title: "x".repeat(201) }), /ResultSet/);
  await assert.rejects(s.saveSnapshot(owner, { ...snapshot, notes: "x".repeat(600_000) }), /limit/);
  assert.equal(calls.length, 0);
});

test("price observations are deterministic, immutable and owner-isolated", async () => {
  const { db, calls } = fakeDatabase();
  const s = store(db);
  const first = await s.recordPrice(owner, snapshot.id, observation);
  const repeat = await s.recordPrice(owner, snapshot.id, observation);
  assert.equal(first, repeat);
  assert.match(first, /^[a-f0-9]{64}$/);
  const newAmount = await s.recordPrice(owner, snapshot.id, { ...observation, amountDecimal: "124.00" });
  assert.notEqual(first, newAmount);
  const otherOwner = await s.recordPrice("account_other123", snapshot.id, observation);
  assert.notEqual(first, otherOwner);
  const record = calls[0];
  assert.equal(record.statement, INSERT_PRICE_OBSERVATION_SQL);
  assert.deepEqual(record.params.slice(0, 2), [owner, snapshot.id]);
  assert.equal(record.params[9], "123.45");
  assert.equal(record.params[6], 0);
  assert.deepEqual(await s.priceHistory(owner, snapshot.id, observation.itineraryId), []);
  assert.equal(calls.at(-1).statement, LIST_PRICE_OBSERVATIONS_SQL);
  assert.deepEqual(calls.at(-1).params, [owner, snapshot.id, "lh-1", 50]);
});

test("missing or unsafe price provenance fails closed before D1 writes", async () => {
  const { db, calls } = fakeDatabase();
  const s = store(db);
  for (const bad of [
    { ...observation, livePrice: true },
    { ...observation, sourceLabel: "unverified" },
    { ...observation, amountDecimal: "-5" },
    { ...observation, amountDecimal: "1e6" },
    { ...observation, amountDecimal: "0" },
    { ...observation, amountDecimal: "Infinity" },
    { ...observation, originalCurrency: "gbp" },
    { ...observation, capturedAt: "yesterday" },
    { ...observation, itineraryId: "../not-an-id" },
  ]) {
    await assert.rejects(s.recordPrice(owner, snapshot.id, bad));
  }
  assert.equal(calls.length, 0);
});

test("saveResultWithPrices commits snapshot and observations as one D1 batch", async () => {
  const { db, calls } = fakeDatabase();
  let batchStatements = null;
  db.batch = async statements => {
    batchStatements = statements;
    return statements.map(() => ({ success: true }));
  };
  const result = await store(db).saveResultWithPrices(owner, snapshot, [
    observation, { ...observation, amountDecimal: "124.00" },
  ]);
  assert.equal(result.id, snapshot.id);
  assert.equal(result.observationIds.length, 2);
  assert.notEqual(result.observationIds[0], result.observationIds[1]);
  assert.equal(batchStatements.length, 3);
  assert.equal(calls[0].statement, UPSERT_INVESTIGATION_SQL);
  assert.equal(calls[1].statement, INSERT_PRICE_OBSERVATION_SQL);
  assert.equal(calls[2].statement, INSERT_PRICE_OBSERVATION_SQL);
  assert.ok(!JSON.stringify(calls).includes("private-departure"));
});

test("batch persistence validates all observations before any D1 statement", async () => {
  const { db, calls } = fakeDatabase();
  let batchCalled = false;
  db.batch = async () => { batchCalled = true; };
  const s = store(db);
  await assert.rejects(s.saveResultWithPrices(owner, snapshot, [
    observation, { ...observation, amountDecimal: "-1.00" },
  ]), /original fare amount/);
  assert.equal(batchCalled, false);
  assert.equal(calls.length, 0);
  await assert.rejects(s.saveResultWithPrices(owner, snapshot, Array(201).fill(observation)),
    /bounded price observation batch/);
  await assert.rejects(s.saveResultWithPrices(owner, {
    ...snapshot, itineraries: [{
      id: "lh-1", bookingToken: "dont-save-this",
    }],
  }, [observation]), /Provider tokens are forbidden/);
  assert.equal(batchCalled, false);
  assert.equal(calls.length, 0);
  await assert.rejects(new D1Investigations(db, { cryptoApi: webcrypto })
    .saveResultWithPrices(owner, { ...snapshot, tokens: {} }, []), /ResultSet/);
  assert.equal(calls.length, 0);
});
