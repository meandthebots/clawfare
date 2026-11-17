import assert from "node:assert/strict";
import test from "node:test";
import { readFileSync } from "node:fs";
import { webcrypto } from "node:crypto";
import { createReadOnlySitesHandler } from "../src/site-handler.mjs";

const templateHtml = readFileSync("build/frontend/web/index.html", "utf8");
const secret = "strong-signed-in-owner-scope-secret-for-this-site";
const db = { prepare() {
  return { bind() { return {
    async first() { return null; },
    async all() { return { results: [] }; },
  }; } };
} };

function handler(email = "alice@example.com") {
  return createReadOnlySitesHandler({
    templateHtml,
    db,
    ownerKeySecret: secret,
    cryptoApi: webcrypto,
    getVerifiedEmail: async () => email,
  });
}
function req(path, method = "GET") {
  return new Request("https://clawfare.example" + path, { method });
}

test("serves exact Kotlin UI with hosted auth mode and no localhost session path", async () => {
  const site = handler();
  const html = await site(req("/"));
  assert.equal(html.status, 200);
  assert.match(await html.text(), /window.__CLAWFARE_HOSTED_READ_ONLY__ = true/);
  assert.equal(html.headers.get("cache-control"), "no-store");
  assert.equal(html.headers.get("referrer-policy"), "no-referrer");
  const head = await site(req("/index.html", "HEAD"));
  assert.equal(head.status, 200);
  assert.equal(await head.text(), "");
});

test("auth-gated read-only GET APIs use hosted handler and never issue requests", async () => {
  const signedIn = handler();
  const status = await signedIn(req("/api/status"));
  assert.equal(status.status, 200);
  assert.deepEqual((await status.json()).providers, []);
  const sample = await signedIn(req("/api/sample"));
  assert.equal(sample.status, 404); // no fixture imported
  const signedOut = handler(null);
  const denied = await signedOut(req("/api/searches"));
  assert.equal(denied.status, 401);
  const mutation = await signedIn(req("/api/search", "POST"));
  assert.equal(mutation.status, 405);
});

test("returns platform authentication paths for Sites ingress and disallows unconfigured app", async () => {
  const site = handler();
  assert.equal(await site(req("/signin-with-chatgpt")), null);
  assert.equal(await site(req("/signout-with-chatgpt")), null);
  const denied = await site(req("/", "POST"));
  assert.equal(denied.status, 405);
  assert.throws(() => createReadOnlySitesHandler({
    templateHtml, db, getVerifiedEmail: () => null, ownerKeySecret: "short",
  }), /secret/);
  assert.throws(() => createReadOnlySitesHandler({
    templateHtml: "<html></html>", db, getVerifiedEmail: () => null, ownerKeySecret: secret,
  }), /snapshot marker/);
});


test("bundled sample is validated at startup, never read from visitor payload", () => {
  const options = {
    templateHtml,
    db,
    getVerifiedEmail: async () => "alice@example.com",
    ownerKeySecret: secret,
    cryptoApi: webcrypto,
  };
  const base = {
    id: "florida-dec-2026",
    title: "Kotlin fixture",
    currency: "GBP",
    itineraries: [{ id: "lh-1", source: "sample", sourceLabel: "sample", livePrice: false }],
    addons: [],
    vendors: [],
  };
  assert.equal(typeof createReadOnlySitesHandler({ ...options, bundledPublicSample: base }), "function");
  assert.throws(() => createReadOnlySitesHandler({
    ...options,
    bundledPublicSample: { ...base, itineraries: [{ ...base.itineraries[0], livePrice: true }] },
  }), /sample-only/);
  assert.throws(() => createReadOnlySitesHandler({
    ...options,
    bundledPublicSample: { ...base, id: "private-user-trip" },
  }), /bundled public Kotlin/);
});
