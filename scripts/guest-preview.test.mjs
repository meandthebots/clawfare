import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";
import { GUEST_SAMPLE } from "./guest-fixture.mjs";
import { buildGuestHtml } from "./build-guest-preview.mjs";

const source = readFileSync(new URL("./guest-runtime.js", import.meta.url), "utf8");
const slot = "__CLAWFARE_GUEST_SAMPLE_JSON__";
function guest() {
  const window = {};
  vm.runInNewContext(source.replace(slot, JSON.stringify(GUEST_SAMPLE)), {
    window, Response, URL, Date,
  });
  return window;
}
const query = {
  origins: ["LHR"], destinations: ["TPA"], outboundFrom: "2026-12-16",
  adults: 2, providers: ["serpapi", "duffel"], confirmed: false,
};
function call(window, path, { method = "GET", body, consent, session = true } = {}) {
  const headers = {};
  if (session) headers["X-Clawfare-Session"] = "offline-guest-session";
  if (consent) headers["X-Clawfare-Consent"] = consent;
  return window.fetch(path, { method, headers, body: body && JSON.stringify(body) });
}

test("all guest fares are synthetic with no refund assertions or booking URLs", () => {
  assert.equal(GUEST_SAMPLE.id.startsWith("guest-"), true);
  assert.ok(GUEST_SAMPLE.itineraries.length >= 2);
  assert.deepEqual(new Set(GUEST_SAMPLE.cells.map((c) => c.status)), new Set(["ok", "catch", "no"]));
  for (const it of [...GUEST_SAMPLE.itineraries, ...GUEST_SAMPLE.addons]) {
    assert.equal(it.sourceLabel, "guest");
    assert.equal(it.livePrice, false);
    assert.equal(it.cashRefundProofVerified, false);
    assert.deepEqual(it.links, []);
  }
  assert.deepEqual(GUEST_SAMPLE.sellerOptions, []);
});

test("guest API starts without credentials, never delegates requests to the network", async () => {
  const window = guest();
  assert.equal(window.__CLAWFARE_GUEST__, true);
  assert.deepEqual(await (await window.fetch("/api/session")).json(), { token: "offline-guest-session" });
  assert.equal((await call(window, "/api/sample")).status, 200);
  assert.equal((await call(window, "/api/status")).status, 200);
  assert.deepEqual(await (await call(window, "/api/quota")).json(), []);
  assert.equal((await call(window, "/api/results/does-not-exist")).status, 404);
  assert.equal((await call(window, "/api/sample", { session: false })).status, 401);
  assert.equal((await call(window, "/api/storage")).status, 404);
  assert.equal((await call(window, "/api/search", { method: "POST", body: query })).status, 400);
  await assert.rejects(window.fetch("https://some-provider.example/search"), /blocks all/);
  assert.equal((await call(window, "/api/../../../secrets")).status, 404);
});

test("estimate requires same-query, unexpired and single-use confirmation", async () => {
  const window = guest();
  const estimated = await call(window, "/api/estimate", { method: "POST", body: query });
  assert.equal(estimated.status, 200);
  const estimate = await estimated.json();
  assert.equal(estimate.liveCalls, 0);
  assert.equal(estimate.cachedCalls, 1);
  assert.equal(estimate.canRun, true);
  const consent = estimated.headers.get("X-Clawfare-Consent");
  assert.ok(consent);
  const invalid = await call(window, "/api/search", {
    method: "POST",
    body: { ...query, adults: 5, confirmed: true },
    consent,
  });
  assert.equal(invalid.status, 403);
  const replay = await call(window, "/api/search", {
    method: "POST", body: { ...query, confirmed: true }, consent,
  });
  assert.equal(replay.status, 403);

  const second = await call(window, "/api/estimate", { method: "POST", body: query });
  const goodConsent = second.headers.get("X-Clawfare-Consent");
  const saved = await call(window, "/api/search", {
    method: "POST", body: { ...query, confirmed: true }, consent: goodConsent,
  });
  assert.equal(saved.status, 200);
  const result = (await saved.json()).result;
  assert.ok(result.id.startsWith("guest-search-"));
  assert.equal(result.itineraries[0].livePrice, false);
  const listed = await (await call(window, "/api/searches")).json();
  assert.equal(listed.length, 1);
  assert.equal(listed[0].id, result.id);
  assert.equal((await call(window, "/api/results/" + result.id)).status, 200);
  assert.equal((await call(window, "/api/results/" + result.id + "/vendors", {
    method: "POST", body: { itineraryId: result.itineraries[0].id },
  })).status, 200);
  assert.equal((await call(window, "/api/results/" + result.id + "/track", {
    method: "POST", body: { itineraryId: result.itineraries[0].id, tripSlug: "x" },
  })).status, 200);
  assert.equal((await call(window, "/api/search", {
    method: "POST", body: { ...query, confirmed: true }, consent: goodConsent,
  })).status, 403);
  assert.deepEqual(await (await call(guest(), "/api/searches")).json(), [],
    "refresh/new guest session must not expose previous in-memory searches");
});

test("guest exports are local and clearly marked synthetic", async () => {
  const window = guest();
  const csv = await call(window, "/api/export/csv", { method: "POST", body: {} });
  assert.equal(csv.status, 200);
  assert.match(await csv.text(), /synthetic_price_gbp/);
  const html = await call(window, "/api/export/html", { method: "POST", body: {} });
  assert.equal(html.status, 200);
  assert.match(await html.text(), /No real flights/);
  assert.equal((await call(window, "/api/provider/live", { method: "POST", body: {} })).status, 405);
});

test("standalone HTML is explicit and blocks all browser network connections", () => {
  const html = [
    "<!doctype html><html><head><style>body{color:#111}</style></head>",
    "<body><div id=\"root\"></div><script>window.__SNAPSHOT__ = null;</script>",
    "<script type=\"module\">console.log('bundled react')</script></body></html>",
  ].join("");
  const guestHtml = buildGuestHtml(html, GUEST_SAMPLE, source);
  assert.match(guestHtml, /id="guestPreviewNotice"/);
  assert.match(guestHtml, /connect-src 'none'/);
  assert.match(guestHtml, /__CLAWFARE_GUEST__/);
  assert.match(guestHtml, /synthetic flights and prices/i);
  assert.equal(html.includes("guestPreviewNotice"), false, "production copy is unchanged");
  assert.equal(guestHtml.includes(slot), false, "fixture placeholder must be replaced");
  assert.throws(() => buildGuestHtml(html.replace("window.__SNAPSHOT__", "window.NO"), GUEST_SAMPLE, source),
    /template/);
  assert.throws(() => buildGuestHtml(html.replace("<head>", "<head><script src='remote.js'></script>"),
    GUEST_SAMPLE, source), /self-contained/);
  const malicious = structuredClone(GUEST_SAMPLE);
  malicious.title = "</script><script>alert('unsafe')</script>";
  const escaped = buildGuestHtml(html, malicious, source);
  assert.doesNotMatch(escaped, /<script>alert\('unsafe'\)<\/script>/);
  assert.match(escaped, /\\u003c\/script>/);
  malicious.itineraries[0].livePrice = true;
  assert.throws(() => buildGuestHtml(html, malicious, source), /refunds or bookings/);
});
