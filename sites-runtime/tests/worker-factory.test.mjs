import assert from "node:assert/strict";
import test from "node:test";
import { readFileSync } from "node:fs";
import { createClawfareWorkerFactory } from "../src/worker-factory.mjs";

const templateHtml = readFileSync("build/frontend/web/index.html", "utf8");
const sample = {
  id: "florida-dec-2026",
  title: "Public saved Kotlin sample", currency: "GBP",
  itineraries: [{ id: "lh-a", source: "sample", sourceLabel: "sample", livePrice: false }],
  addons: [], vendors: [],
};
const bridge = {
  "fare-core": { com: { clawfare: { search: {
    normalizeSearchForHost: query => query,
    planSearchForHost: query => query,
    projectBudgetForHost: query => query,
    toGbpForHost: amount => amount,
  } } } },
};
const create = () => createClawfareWorkerFactory({
  templateHtml, bundledPublicSample: sample, kotlinBundle: bridge,
});
const secret = "a-fake-test-only-owner-secret-longer-than-32bytes";
const db = {
  prepare() {
    return {
      bind() {
        return {
          async first() { return null; },
          async all() { return { results: [] }; },
        };
      },
    };
  },
};
const req = (route, options) => new Request("https://clawfare.example" + route, options);

test("assembly requires the Kotlin compiler export, public fixture and host verifier", () => {
  const factory = create();
  assert.equal(factory.kotlin.toGbpOrNull(10, 2), 10);
  assert.throws(() => factory.createTrustedWorker({}), /Verified Sites identity/);
  assert.throws(() => createClawfareWorkerFactory({
    templateHtml, bundledPublicSample: sample, kotlinBundle: {},
  }), /Incompatible Kotlin\/JS/);
  assert.throws(() => createClawfareWorkerFactory({
    templateHtml, bundledPublicSample: { ...sample, secret: "hidden" },
    kotlinBundle: bridge,
  }), /Private provider data/);
});

test("missing hosted secrets and D1 binding fail closed even with a verifier", async () => {
  const worker = create().createTrustedWorker({ getVerifiedEmail: async () => "owner@example.com" });
  assert.equal((await worker.fetch(req("/api/status"), {})).status, 503);
  assert.equal((await worker.fetch(req("/api/status"), { DB: db, OWNER_KEY_SECRET: "short" })).status, 503);
  assert.equal((await worker.fetch(req("/"), { OWNER_KEY_SECRET: secret })).status, 503);
});

test("server retains the Kotlin UI and invokes an explicit identity verifier", async () => {
  let verifiedCalls = 0;
  const worker = create().createTrustedWorker({
    getVerifiedEmail: async () => { verifiedCalls++; return "owner@example.com"; },
  });
  const env = { DB: db, OWNER_KEY_SECRET: secret };
  const homepage = await worker.fetch(req("/"), env);
  assert.equal(homepage.status, 200);
  assert.match(await homepage.text(), /window.__CLAWFARE_HOSTED_READ_ONLY__ = true/);
  const status = await worker.fetch(req("/api/status"), env);
  assert.equal(status.status, 200);
  const readiness = (await status.json()).providers;
  assert.equal(readiness.length, 4);
  assert.ok(readiness.every(p => p.configured === false && p.remaining === null));
  assert.ok(readiness.every(p => /No D1 provider cap/.test(p.note)));
  const quota = await worker.fetch(req("/api/quota"), env);
  assert.equal(quota.status, 200);
  assert.deepEqual(await quota.json(), readiness);
  assert.equal(verifiedCalls, 1);
  assert.equal((await worker.fetch(req("/api/search", { method: "POST" }), env)).status, 405);
  assert.equal((await worker.fetch(req("/unknown"), env)).status, 404);
  assert.equal((await worker.fetch(req("/signin-with-chatgpt"), env)).status, 404);
});

test("forged identity headers do not bypass an unauthenticated trusted callback", async () => {
  const worker = create().createTrustedWorker({ getVerifiedEmail: async () => null });
  const env = { DB: db, OWNER_KEY_SECRET: secret };
  const forged = req("/api/searches", { headers: {
    "oai-authenticated-user-email": "owner@example.com",
    "x-user-id": "owner@example.com",
  } });
  assert.equal((await worker.fetch(forged, env)).status, 401);
});
