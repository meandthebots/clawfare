#!/usr/bin/env node
// End-to-end browser test of the separate credential-free, network-denying
// guest artifact. Uses the REAL React bundle, not a mocked DOM.
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { chromium } from "playwright";

const path = process.argv[2] || "build/guest-preview.html";
const html = readFileSync(path, "utf8");
assert.match(html, /guestPreviewNotice/);
assert.match(html, /connect-src 'none'/);
const browser = await chromium.launch({ headless: true });
const requests = [];
const errors = [];
async function createPage(options) {
  const page = await browser.newPage(options);
  page.on("pageerror", (error) => errors.push(error.message));
  await page.route("**/*", async (route) => {
    if (route.request().url() === "https://offline-guest.test/") {
      return route.fulfill({ status: 200, body: html, contentType: "text/html" });
    }
    requests.push(route.request().method() + " " + route.request().url());
    return route.abort();
  });
  await page.goto("https://offline-guest.test/");
  await page.waitForSelector(options?.isMobile
    ? "#mobileGrid button.mobile-flight-main" : "#grid td.cell");
  return page;
}
try {
  const page = await createPage({ viewport: { width: 1440, height: 900 } });
  assert.match(await page.locator("#guestPreviewNotice").textContent(), /synthetic/i);
  assert.match(await page.locator("#banners").textContent(), /GUEST PREVIEW ONLY/);
  assert.doesNotMatch(await page.locator("#banners").textContent(), /saved from Google Flights/);
  assert.equal(await page.locator("#grid td.cell").count(), 6);
  await page.locator("#grid td.cell[data-a='guest-mx-1']").first().click();
  assert.equal(await page.locator("#panel").getAttribute("aria-hidden"), "false");
  await page.locator("#pClose").click();

  // Exercise the real React query form and single-use guest estimate/confirm.
  // The generic form is intentionally blank, even though the guest fixture
  // is visible. Explicit inputs must still be parsed before an estimate.
  assert.equal(await page.locator("#btnRun").isDisabled(), true);
  await page.locator("#origins").fill("LHR, LGW");
  await page.locator("#destinations").fill("TPA, SRQ");
  await page.locator("#outFrom").fill("2027-04-12");
  await page.locator("#nights").fill("8");
  await page.locator("#pSerp").check();
  await page.locator("#btnEstimate").click();
  await page.waitForFunction(() => !document.querySelector("#btnRun")?.disabled);
  assert.match(await page.locator("#estimate").textContent(), /Zero real provider calls/);
  await page.locator("#btnRun").click();
  await page.waitForSelector("#invList button[data-inv^='guest-search-']");
  assert.equal(await page.locator("#invList button").count(), 1);
  await page.locator("#tabs button[data-tab='vendors']").click();
  await page.waitForSelector("#vendors textarea.noteedit");
  await page.locator("#vendors textarea.noteedit").first().fill("Offline test note");
  const vendorDownloadPromise = page.waitForEvent("download");
  await page.locator("#btnCsv").click();
  const vendorDownload = await vendorDownloadPromise;
  assert.equal(vendorDownload.suggestedFilename(), "guest-vendors.csv");
  const vendorChunks = [];
  for await (const chunk of await vendorDownload.createReadStream()) vendorChunks.push(Buffer.from(chunk));
  const vendorCsv = Buffer.concat(vendorChunks).toString("utf8");
  assert.match(vendorCsv, /Guest Air Direct/);
  assert.match(vendorCsv, /Offline test note/);
  assert.doesNotMatch(vendorCsv, /guest-lh-1/);

  await page.locator("#tabs button[data-tab='itineraries']").click();
  await page.locator("#originSeg button").filter({ hasText: /^LHR$/ }).click();
  const flightDownloadPromise = page.waitForEvent("download");
  await page.locator("#btnCsv").click();
  const flightDownload = await flightDownloadPromise;
  assert.equal(flightDownload.suggestedFilename(), "guest-itineraries.csv");
  const flightChunks = [];
  for await (const chunk of await flightDownload.createReadStream()) flightChunks.push(Buffer.from(chunk));
  const flightCsv = Buffer.concat(flightChunks).toString("utf8");
  assert.match(flightCsv, /guest-lh-1/);
  assert.doesNotMatch(flightCsv, /guest-lh-2/);
  await page.waitForSelector("#itineraries button[aria-label^='View details']");
  await page.locator("#itineraries button[aria-label^='View details']").first().click();
  assert.equal(await page.locator("#panel").getAttribute("aria-hidden"), "false");
  await page.locator("#pClose").click();

  // In-memory investigations must disappear on refresh, unlike the page UI.
  await page.reload();
  await page.waitForSelector("#grid td.cell");
  assert.equal(await page.locator("#invList button").count(), 0);
  await page.close();

  const mobile = await createPage({
    viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true,
  });
  const flightButton = mobile.locator("#mobileGrid button.mobile-flight-main").first();
  await flightButton.tap();
  assert.equal(await mobile.locator("#panel").getAttribute("aria-hidden"), "false");
  await mobile.locator("#pClose").tap();
  const addonButton = mobile.locator("#mobileGrid button.mobile-addon").first();
  await addonButton.tap();
  assert.equal(await mobile.locator("#panel").getAttribute("aria-hidden"), "false");
  await mobile.close();
  assert.deepEqual(errors, [], "Guest preview must not raise browser errors");
  assert.deepEqual(requests, [], "Guest preview must never make a real network request");
  console.log("Clawfare offline guest React browser interaction PASS");
} finally {
  await browser.close();
}
