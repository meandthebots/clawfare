#!/usr/bin/env node
/**
 * Real Chromium regression of the complete hosted React search lifecycle.
 * All HTTP is intercepted at a same-origin synthetic API. No provider egress,
 * credentials, Sites deployment, or paid reservations are possible in CI.
 *
 * The companion hosted-search-synthetic.mjs tests the actual signed Kotlin/JS
 * + D1 transport. This checks the browser interactions that Node alone misses.
 */
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { chromium } from "playwright";

const original = readFileSync(process.argv[2] || "build/frontend/web/index.html", "utf8");
const sample = JSON.parse(readFileSync(
  process.argv[3] || "build/sites/clawfare-sample.json", "utf8",
));
const marker = "<script>window.__SNAPSHOT__ = null;</script>";
assert.ok(original.includes(marker), "Expected compiled React standalone template");
const html = original.replace(marker,
  "<script>window.__SNAPSHOT__ = null;" +
  "window.__CLAWFARE_HOSTED_READ_ONLY__ = true;" +
  "window.__CLAWFARE_HOSTED_ESTIMATE_ONLY__ = true;</script>");
assert.ok(sample.itineraries?.length, "Canonical Kotlin sample fixture required");

const origin = "https://clawfare.test";
const itineraryId = "serpapi-verified-two-adults";
const resultId = "search-real-roundtrip";
const trackedId = "tracked-" + "a".repeat(40);
const observedAt = new Date().toISOString();
const depart = sample.itineraries[0].outbound.legs[0];
const flight = (number, from, to, date, arriveDate) => ({
  ...depart,
  flightNumber: number,
  airline: "British Airways",
  airlineCode: "BA",
  operatingCarrier: "British Airways",
  operatingCarrierVerified: true,
  from, to, departDate: date, arriveDate,
  departTime: "13:00", arriveTime: "17:00",
  cabin: "Business", durationMinutes: 720,
});
const outward = flight("BA 7", "LHR", "NRT", "2027-04-12", "2027-04-13");
const inbound = flight("BA 8", "NRT", "LHR", "2027-04-20", "2027-04-20");
const direction = leg => ({
  legs: [leg], stops: 0, durationMinutes: 720, layovers: [],
});
const result = {
  ...sample,
  id: resultId,
  title: "LHR → NRT · verified round-trip result",
  subtitle: "SerpApi fare observations, not confirmed bookings",
  fetchedAt: observedAt,
  scenarioLabel: null,
  query: { market: "uk", tripType: "round_trip" },
  coverage: {
    version: 1, observedAt, state: "complete", eligibleItineraries: 1, excludedByReason: {},
    routes: [{
      provider: "serpapi", market: "uk", origin: "LHR", destination: "NRT",
      outboundDate: "2027-04-12", returnDate: "2027-04-20", state: "complete", sourceMode: "live",
      providerRows: 1, parsedOffers: 1, eligibleOffers: 1, returnLookupsLoaded: 1,
      unresolvedReturns: 0, returnSelectionLimited: false, errorKind: null,
    }],
  },
  headerNotes: [],
  vendorIntro: [],
  suggestedFilters: {},
  itineraries: [{
    ...sample.itineraries[0],
    id: itineraryId,
    origin: "LHR", destination: "NRT", returnFrom: "NRT",
    outboundDate: "2027-04-12", returnDate: "2027-04-20", nights: 8,
    cabin: "Business", cabinKey: "business",
    passengers: 2, checkedBags: 0, priceCurrency: "GBP",
    pricePerPerson: 1800, priceOriginal: null,
    airlineSummary: "British Airways", airlineCodes: ["BA"],
    outbound: direction(outward), returning: direction(inbound),
    stopsOut: 0, stopsRet: 0, maxLayoverMinutes: null,
    travelMinutes: 1440, returnMissing: false, assumedReturn: false,
    source: "serpapi", sourceLabel: "live", livePrice: true,
    fetchedAt: observedAt, links: [], cashRefundProofVerified: false,
    refundEvidence: null, notes: "Unverified booking and refund conditions",
  }],
  addons: [], cells: [], vendors: [], sellerOptions: [],
  fx: { asOf: observedAt.slice(0, 10), perGbp: { GBP: 1 } },
  warnings: ["Observed full return-selection quote; availability can change."],
};
assert.equal(result.itineraries[0].links.length, 0, "No synthetic booking link");

const state = {
  enabled: false, quotes: [], searches: [], consumed: new Set(),
  stored: new Map([[sample.id, sample]]),
  requests: [], unexpected: [], currentQuote: null,
};
function respond(route, value, status = 200) {
  return route.fulfill({
    status, body: JSON.stringify(value), contentType: "application/json",
    headers: { "cache-control": "private, no-store" },
  });
}
async function api(route) {
  const request = route.request();
  const url = new URL(request.url());
  if (url.origin !== origin) {
    state.unexpected.push("External network: " + url.origin);
    return route.abort();
  }
  if (url.pathname === "/") return route.fulfill({
    status: 200, contentType: "text/html", body: html,
  });
  const entry = {
    path: url.pathname, method: request.method(), headers: request.headers(),
    data: request.postDataJSON?.() ?? null,
  };
  state.requests.push(entry);
  if ("x-clawfare-session" in entry.headers ||
      "oai-authenticated-user-email" in entry.headers ||
      entry.path === "/api/session") {
    state.unexpected.push("Browser forged a trusted identity or session");
  }
  if (entry.path === "/api/status") return respond(route, {
    cacheDir: "d1", searchesDir: "d1", hostedTracking: true,
    providers: ["serpapi", "searchapi", "travelpayouts", "duffel"].map((provider, index) => ({
      provider, configured: index === 0 && state.enabled,
      hosted: {
        state: index === 0 ? state.enabled ? "ready" : "disabled" : "unsupported",
        priceMode: ["live", "live", "cached", "mock"][index],
        dailyRemaining: index === 0 ? 5 : null, monthlyRemaining: null,
        externalQuota: "unknown", checkedAt: observedAt,
        oneWay: index === 0, roundTrip: index === 0, sellerOptions: false,
      },
    })),
    hostedSearch: {
      canExecuteOneWayGbpSerpApi: state.enabled,
      canExecuteRoundTripGbpSerpApi: state.enabled,
    },
  });
  if (entry.path === "/api/sample") return respond(route, sample);
  if (entry.path === "/api/searches") return respond(route,
    [...state.stored.values()].map(item => ({ id: item.id, title: item.title })));
  if (entry.path.startsWith("/api/results/") && entry.method === "GET") {
    const item = state.stored.get(decodeURIComponent(entry.path.slice("/api/results/".length)));
    return respond(route, item ?? { error: "Not found" }, item ? 200 : 404);
  }
  if (entry.path === `/api/results/${resultId}/track` && entry.method === "POST") {
    assert.deepEqual(entry.data, { itineraryId, tripSlug: "tokyo-move" });
    assert.equal(entry.headers.origin, origin);
    state.stored.set(trackedId, { ...result, id: trackedId, title: "tokyo-move · LHR → NRT" });
    return respond(route, { slug: trackedId });
  }
  if (entry.path === `/api/history/${trackedId}/${itineraryId}` && entry.method === "GET") {
    return respond(route, [{
      observation_id: "a".repeat(64), itinerary_id: itineraryId, provider: "serpapi",
      source_label: "live", live_price: 1, captured_at: observedAt,
      original_currency: "GBP", amount_decimal: "1800",
    }]);
  }
  if (entry.path === "/api/quota") return respond(route, []);
  if (entry.path === "/api/estimate" && entry.method === "POST") {
    assert.equal(entry.data.confirmed, false);
    assert.equal(entry.data.tripType, "round_trip");
    assert.equal(entry.data.fetchReturns, true);
    assert.deepEqual(entry.data.providers, ["serpapi"]);
    assert.deepEqual(entry.data.origins, ["LHR"]);
    assert.deepEqual(entry.data.destinations, ["NRT"]);
    return respond(route, {
      liveCalls: 2, cachedCalls: 0, skippedCalls: 0,
      returnLookupsUpperBound: 1, quota: [], lines: ["No paid calls executed."],
      warnings: [], canRun: false,
    });
  }
  if (entry.path === "/api/prepare-search" && entry.method === "POST") {
    assert.equal(state.enabled, true, "Server capability required to prepare");
    assert.equal(entry.data.maxLiveCalls, 2);
    assert.equal(entry.data.returnChoices, 1);
    const token = "synthetic-one-use-" + (state.quotes.length + 1) + "-signed-consent";
    const quote = {
      canonicalQuery: { ...entry.data },
      queryDigest: "a".repeat(64),
      consentToken: token,
      expiresAt: new Date(Date.now() + 5 * 60_000).toISOString(),
      totalCallsUpperBound: 2, baseCallsUpperBound: 1,
      returnLookupsUpperBound: 1, canExecuteHere: true,
      cost: { vendorBillingKnown: false,
        message: "Provider account billing is unknown; no automatic retry." },
    };
    state.currentQuote = quote;
    state.quotes.push(quote);
    return respond(route, quote);
  }
  if (entry.path === "/api/search" && entry.method === "POST") {
    assert.equal(state.enabled, true, "Server execution switch required");
    assert.equal(entry.data.confirmed, undefined);
    assert.deepEqual(entry.data.canonicalQuery, state.currentQuote.canonicalQuery);
    assert.equal(entry.data.consentToken, state.currentQuote.consentToken);
    assert.equal(state.consumed.has(entry.data.consentToken), false,
      "Browser repeated a one-use potentially billable request");
    state.consumed.add(entry.data.consentToken);
    state.searches.push(entry);
    // Deliberately leave the request pending long enough to test the loading
    // lock, but still without any provider network call.
    await new Promise(resolve => setTimeout(resolve, 150));
    result.query = state.currentQuote.canonicalQuery;
    state.stored.set(resultId, result);
    return respond(route, {
      ok: true, result,
      providerObservations: [{
        provider: "serpapi", state: "completed",
        baseCallsExpected: 1, baseCallsLoaded: 1,
        returnLookupsExpected: 1, returnLookupsLoaded: 1,
        returned: 1, eligible: 1, unresolvedReturns: 0,
      }],
    });
  }
  state.unexpected.push("Unexpected API: " + entry.method + " " + entry.path);
  return respond(route, { error: "Unexpected API route" }, 405);
}

async function fillRoundTrip(page) {
  await page.locator("#origins").fill("LHR");
  await page.locator("#destinations").fill("NRT");
  await page.locator("#outFrom").fill("2027-04-12");
  await page.locator("#outTo").fill("2027-04-12");
  await page.locator("#nights").fill("8");
  await page.locator("#adults").fill("2");
  await page.locator("#cabin").selectOption("business");
  await page.locator("#maxLiveCalls").fill("2");
  await page.locator("#returnChoices").fill("1");
  await page.locator("#pSerp").check();
  assert.equal(await page.locator("#fetchReturns").isChecked(), true);
}

const browser = await chromium.launch({ headless: true });
try {
  const page = await browser.newPage({ viewport: { width: 1300, height: 900 } });
  const errors = [];
  page.on("pageerror", error => errors.push(error.message));
  await page.route("**/*", api);
  await page.goto(origin);
  await page.locator("#grid td.cell").first().waitFor();
  for (const id of ["pSearch", "pTp", "pDuffel"]) assert.equal(await page.locator("#" + id).isDisabled(), true);
  assert.equal(await page.locator("#pSerp").isEnabled(), true);
  await fillRoundTrip(page);
  await page.locator("#btnEstimate").click();
  await page.waitForFunction(() =>
    document.querySelector("#estimate")?.textContent?.includes("No paid calls executed."));
  assert.equal(state.quotes.length, 0, "Estimate-only mode must not issue signed approval");
  assert.equal(state.searches.length, 0);
  assert.equal(await page.locator("#btnRun").isDisabled(), true);

  state.enabled = true;
  await page.reload();
  await page.locator("#grid td.cell").first().waitFor();
  await page.waitForFunction(() =>
    document.querySelector("#estimate")?.textContent?.includes("Configured providers"));
  await fillRoundTrip(page);
  assert.equal(await page.locator("#btnRun").isDisabled(), true);
  await page.locator("#btnEstimate").click();
  await page.locator("#btnRun").waitFor({ state: "visible" });
  await page.waitForFunction(() =>
    document.querySelector("#btnRun")?.disabled === false);
  assert.equal(state.quotes.length, 1);
  assert.equal(state.searches.length, 0, "Preparation never sends a billable search");
  assert.equal(await page.locator("#btnRun").textContent(), "Confirm and run search");

  page.once("dialog", dialog => {
    assert.match(dialog.message(), /LHR → NRT/);
    assert.match(dialog.message(), /2 adults/);
    assert.match(dialog.message(), /2 provider requests/);
    assert.match(dialog.message(), /return lookups: 1/);
    return dialog.dismiss();
  });
  await page.locator("#btnRun").click();
  assert.equal(state.searches.length, 0, "Cancelled confirmation must not execute");
  assert.equal(await page.locator("#btnRun").isEnabled(), true);

  // A changed form invalidates the prior quote. A second estimate must issue
  // a fresh token, not silently reuse consent for different search constraints.
  await page.locator("#returnChoices").fill("2");
  assert.equal(await page.locator("#btnRun").isDisabled(), true);
  await page.locator("#returnChoices").fill("1");
  await page.locator("#btnEstimate").click();
  await page.waitForFunction(() => document.querySelector("#btnRun")?.disabled === false);
  assert.equal(state.quotes.length, 2);
  page.once("dialog", dialog => dialog.accept());
  await page.locator("#btnRun").click();
  await page.waitForFunction(() =>
    document.querySelector("#itineraries tbody tr[data-p]") !== null);
  assert.equal(state.searches.length, 1);
  assert.equal(await page.locator("#btnRun").isDisabled(), true);
  assert.match(await page.locator("#title").textContent(), /LHR.*NRT/);
  const coverage = page.locator("summary").filter({ hasText: "Search coverage" });
  await coverage.click();
  await page.getByText(/1 received, 1 parsed, 1 eligible/).waitFor();
  const row = page.locator("#itineraries tbody tr[data-p]").first();
  assert.match(await row.textContent(), /£1,800/);
  assert.match(await row.textContent(), /£3,600/);
  assert.match(await row.textContent(), /20 Apr/);
  assert.equal(await page.locator("#itineraries a:has-text('Book')").count(), 0,
    "Unverified provider results must not have invented booking links");
  await row.locator("button").first().click();
  assert.equal(await page.locator("#panel").getAttribute("aria-hidden"), "false");
  assert.match(await page.locator("#pBody").textContent(), /BA 7/);
  assert.match(await page.locator("#pBody").textContent(), /verified operator/);
  await page.getByRole("button", { name: "Save to a trip" }).click();
  const trip = page.getByRole("textbox", { name: "Trip name" });
  assert.ok(await trip.evaluate(input => input === document.activeElement));
  await trip.fill("tokyo move");
  await page.getByRole("button", { name: "Save fare", exact: true }).click();
  assert.equal(await trip.evaluate(input => input.validity.patternMismatch), true);
  await trip.fill("tokyo-move");
  await page.getByRole("button", { name: "Save fare", exact: true }).click();
  await page.getByText("Fare saved. Open it from Investigations.").waitFor();
  await page.locator("#pClose").click();

  await page.locator('button[data-tab="matrix"]').click();
  const matrix = page.locator("#matrix td.mxcell");
  assert.equal(await matrix.count(), 1);
  await matrix.first().click();
  assert.equal(await page.locator("#panel").getAttribute("aria-hidden"), "false");
  await page.locator("#pClose").click();

  await page.locator("#fCashRefund").check();
  await page.locator("#itineraries").count();
  await page.locator('button[data-tab="itineraries"]').click();
  await page.waitForSelector("#itineraries tbody td.empty");
  assert.match(await page.locator("#itineraries tbody td.empty").textContent(), /No itineraries/);
  await page.locator("#btnClear").click();
  await page.waitForSelector("#itineraries tbody tr[data-p]");
  await page.locator("#btnSample").click();
  await page.waitForFunction(expected =>
    document.querySelector("#title")?.textContent?.includes(expected), sample.title);
  await page.locator("#saved").selectOption(resultId);
  await page.locator("#btnOpen").click();
  await page.waitForSelector("#itineraries tbody tr[data-p]");
  assert.match(await page.locator("#title").textContent(), /verified round-trip/);
  assert.equal(state.searches.length, 1, "Opening saved result cannot repeat paid calls");
  await page.locator("#origins").fill("MAN");
  await page.getByRole("button", { name: "Use search settings" }).click();
  assert.equal(await page.locator("#origins").inputValue(), "LHR");
  assert.equal(await page.locator("#adults").inputValue(), "2");
  assert.equal(await page.locator("#btnRun").isDisabled(), true);
  assert.equal(state.quotes.length, 2);
  assert.equal(state.searches.length, 1);

  // Loaded investigations also survive a browser reload through the API;
  // no synthetic live result is mixed into the initial Florida sample.
  await page.reload();
  await page.waitForSelector("#grid td.cell");
  await page.locator("#saved option[value='" + resultId + "']").waitFor({ state: "attached" });
  await page.locator("#saved").selectOption(resultId);
  await page.locator("#btnOpen").click();
  await page.waitForSelector("#itineraries tbody tr[data-p]");
  assert.equal(state.searches.length, 1);

  const mobile = await browser.newPage({
    viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true,
    deviceScaleFactor: 2,
  });
  mobile.on("pageerror", error => errors.push(error.message));
  await mobile.route("**/*", api);
  await mobile.goto(origin);
  await mobile.locator("#saved option[value='" + resultId + "']").waitFor({ state: "attached" });
  await mobile.locator("#saved").selectOption(trackedId);
  await mobile.locator("#btnOpen").tap();
  await mobile.waitForSelector("#mobileItineraries article");
  assert.equal(await mobile.locator("#itineraries").evaluate(el =>
    getComputedStyle(el).display), "none", "Wide desktop table must not be visible on mobile");
  assert.equal(await mobile.locator("#mobileItineraries").evaluate(el =>
    getComputedStyle(el).display), "grid", "Live search uses mobile itinerary cards");
  const action = mobile.locator("#mobileItineraries button[data-p]").first();
  assert.match(await action.textContent(), /£3,600/);
  await action.tap();
  assert.equal(await mobile.locator("#panel").getAttribute("aria-hidden"), "false");
  await mobile.getByRole("button", { name: "Price history" }).tap();
  await mobile.getByText(/GBP 1800 · serpapi · live/).waitFor();
  assert.match(await mobile.locator("#title").textContent(), /tokyo-move/);
  await mobile.locator("#pClose").tap();
  assert.equal(await mobile.locator("#panel").getAttribute("aria-hidden"), "true");
  const overflow = await mobile.evaluate(() =>
    document.documentElement.scrollWidth > window.innerWidth + 4);
  assert.equal(overflow, false, "Mobile live-result cards must not overflow viewport");
  assert.equal(state.searches.length, 1, "Mobile reopening must not initiate a provider call");

  assert.equal(state.unexpected.length, 0, JSON.stringify(state.unexpected));
  assert.deepEqual(errors, [], "No unhandled browser errors in hosted search lifecycle");
  assert.equal(state.requests.some(request => request.path === "/api/session"), false);
  assert.equal(state.searches[0].data.consentToken, state.quotes[1].consentToken);
  await mobile.close();
  await page.close();
  console.log("Hosted React real-browser PASS: disabled, quote, cancel, execute, details, filters, matrix, saved reload, mobile");
} finally {
  await browser.close();
}
