#!/usr/bin/env node
/**
 * Browser-level regression for the exact original Kotlin HTML and canonical
 * sample JSON. Must run with locally installed Playwright Chromium in CI.
 */
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { chromium } from "playwright";

const htmlPath = process.argv[2] || "build/frontend/web/index.html";
const samplePath = process.argv[3] || "build/sites/clawfare-sample.json";
const original = readFileSync(htmlPath, "utf8");
const sample = JSON.parse(readFileSync(samplePath, "utf8"));
assert.ok(sample.itineraries?.length, "real Kotlin sample itineraries required");
assert.ok(sample.addons?.length, "real Kotlin add-on combinations required");
assert.ok(sample.cells?.length, "real Kotlin comparison cells required");
const marker = "<script>window.__SNAPSHOT__ = null;</script>";
assert.equal(original.split(marker).length, 2);
const html = original.replace(marker,
  "<script>window.__SNAPSHOT__ = " + JSON.stringify(sample).replace(/</g, "\\u003c") + ";</script>");
const browser = await chromium.launch({ headless: true });
try {
  const mobile = await browser.newPage({
    viewport: { width: 390, height: 844 },
    isMobile: true,
    hasTouch: true,
    deviceScaleFactor: 2,
  });
  await mobile.setContent(html, { waitUntil: "load" });
  await mobile.waitForSelector("#mobileGrid .mobile-flight");
  await mobile.waitForSelector("#summary .sumcard");
  assert.ok(await mobile.locator("#summary .sumcard").count() >= 3,
    "React summary must render all cheapest-fare categories");
  assert.match(await mobile.locator("#banners").textContent(), /not a live search/,
    "Historical sample must be labelled as non-live by React");
  const mobileStatus = await mobile.evaluate(() => ({
    table: getComputedStyle(document.querySelector("#grid")).display,
    cards: getComputedStyle(document.querySelector("#mobileGrid")).display,
    available: document.querySelectorAll("#mobileGrid button[data-p][data-a]").length,
    overflows: document.documentElement.scrollWidth > window.innerWidth + 4,
  }));
  assert.equal(mobileStatus.table, "none", "Wide desktop table should be hidden on mobile");
  assert.equal(mobileStatus.cards, "grid", "Mobile combination cards should be visible");
  assert.ok(mobileStatus.available > sample.itineraries.length, "Expected add-on detail buttons");
  assert.equal(mobileStatus.overflows, false, "Mobile layout must not force page-wide horizontal overflow");

  const longHaul = mobile.locator("#mobileGrid button.mobile-flight-main").first();
  await longHaul.tap();
  assert.ok(await mobile.locator("#panel").evaluate(el => el.classList.contains("open")),
    "Tapping the first/long-haul column must open details");
  assert.match(await mobile.locator("#pTitle").textContent(), /→/);
  await mobile.locator("#pClose").tap();
  assert.equal(await mobile.locator("#panel").getAttribute("aria-hidden"), "true");

  const addon = mobile.locator("#mobileGrid button.mobile-addon").first();
  const addonId = await addon.getAttribute("data-a");
  await addon.tap();
  assert.ok(await mobile.locator("#panel").evaluate(el => el.classList.contains("open")));
  assert.ok((await mobile.locator("#pTitle").textContent()).includes(" + " + addonId),
    "Tapping a Mexico add-on must open the selected combination");
  assert.match(await mobile.locator("#pBody").textContent(), /Price/);
  assert.equal(await mobile.locator("#pBody .tl-days").evaluate(el => getComputedStyle(el).display),
    "grid", "The timeline ruler must retain compiled StyleX layout");
  assert.equal(await mobile.locator("#pBody .tl-bar").first().evaluate(el => getComputedStyle(el).height),
    "20px", "A flight bar must keep its visible 20px height");

  assert.match(await mobile.locator("#pBody").textContent(), /Cancellation \/ refund evidence/);
  assert.ok(await mobile.locator("#btnVendorsFor").isVisible(),
    "React details drawer must retain the seller comparison action");
  await mobile.close();

  const desktop = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  await desktop.setContent(html, { waitUntil: "load" });
  await desktop.waitForSelector("#grid td.cell");
  const headerBackground = await desktop.locator("header").evaluate(
    el => getComputedStyle(el).backgroundImage);
  assert.match(headerBackground, /linear-gradient/,
    "Compiled StyleX header background must be present in the standalone browser page");
  assert.notEqual(await desktop.locator("#grid").evaluate(el => getComputedStyle(el).display), "none");
  assert.equal(await desktop.locator("#mobileGrid").evaluate(el => getComputedStyle(el).display), "none");
  await desktop.locator("#grid td.cell[data-p]").last().click();
  assert.ok(await desktop.locator("#panel").evaluate(el => el.classList.contains("open")),
    "Desktop cell click behavior must remain intact");
  await desktop.locator("#pClose").click();
  const keyboardCell = desktop.locator("#grid td.cell button").first();
  await keyboardCell.focus();
  await desktop.keyboard.press("Enter");
  assert.ok(await desktop.locator("#panel").evaluate(el => el.classList.contains("open")),
    "Keyboard selection must open React grid details");
  await desktop.locator("#pClose").click();
  // React filter segments must preserve state and display after CSS migration.
  const nightFilter = desktop.locator("#nightSeg button[data-v]:not([data-v='all'])").first();
  assert.ok(await nightFilter.count(), "Kotlin sample should populate night filters");
  await nightFilter.click();
  assert.equal(await nightFilter.getAttribute("aria-pressed"), "true");
  await desktop.locator("#sortSeg button[data-v='price']").click();
  assert.equal(await desktop.locator("#sortSeg button[data-v='price']").getAttribute("aria-pressed"), "true");
  // An inline-flex item is blockified to flex when its parent is display:flex.
  // Both represent a usable flex group; block means the StyleX rule was lost.
  assert.equal(await desktop.locator("#sortSeg").evaluate(el => getComputedStyle(el).display), "flex");
  await desktop.locator("#btnClear").click();
  assert.equal(await desktop.locator("#sortSeg button[data-v='date']").getAttribute("aria-pressed"), "true");
  await desktop.waitForSelector("#grid td.cell");
  await desktop.locator("#tabs button[data-tab='itineraries']").click();
  await desktop.waitForSelector("#itineraries tbody tr[data-p]");
  await desktop.locator("#itineraries button[aria-label^='View details']").first().click();
  assert.ok(await desktop.locator("#panel").evaluate(el => el.classList.contains("open")),
    "React itinerary Details button must open the drawer");
  await desktop.locator("#pClose").click();
  await desktop.locator("#tabs button[data-tab='grid']").click();
  await desktop.waitForSelector("#grid td.cell");
  await desktop.locator("#tabs button[data-tab='vendors']").click();
  await desktop.waitForSelector("#vendors tbody tr");
  const firstNote = desktop.locator("#vendors textarea.noteedit").first();
  await firstNote.fill("React vendor note regression");
  await firstNote.blur();
  await desktop.locator("#tabs button[data-tab='grid']").click();
  await desktop.locator("#tabs button[data-tab='vendors']").click();
  await desktop.waitForSelector("#vendors textarea.noteedit");
  assert.equal(await desktop.locator("#vendors textarea.noteedit").first().inputValue(),
    "React vendor note regression", "Local vendor notes must survive tab switches");
  await desktop.locator("#tabs button[data-tab='grid']").click();
  await desktop.close();
  console.log("Clawfare mobile grid browser PASS: touchable first-column flights and add-ons open details; desktop remains intact");
} finally {
  await browser.close();
}
