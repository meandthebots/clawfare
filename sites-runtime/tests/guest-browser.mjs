#!/usr/bin/env node
/** Credential-free browser regression: the fixture must never touch an API. */
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { chromium } from "playwright";

const html = readFileSync(process.argv[2] || "build/frontend/web/index.html", "utf8");
assert.match(html, /window.__SNAPSHOT__ = null/);
const browser = await chromium.launch({ headless: true });
try {
  const page = await browser.newPage({ viewport: { width: 1100, height: 800 } });
  const errors = [];
  const requests = [];
  page.on("pageerror", error => errors.push(error.message));
  await page.route("**/*", async route => {
    const url = new URL(route.request().url());
    requests.push(route.request().method() + " " + url.pathname + url.search);
    if (url.origin === "https://clawfare.test" && url.pathname === "/" &&
        url.searchParams.get("guest") === "1") {
      return route.fulfill({ status: 200, body: html, contentType: "text/html" });
    }
    errors.push("Unexpected network request: " + route.request().url());
    return route.abort();
  });

  await page.goto("https://clawfare.test/?guest=1");
  await page.waitForSelector("#grid td.cell");
  assert.match(await page.locator("#guestMode").textContent(), /no account or credentials/i);
  assert.match(await page.locator("#title").textContent(), /Guest demo/);
  assert.ok(await page.locator("#btnRun").isDisabled());
  assert.ok(await page.locator("#btnEstimate").isDisabled());
  assert.ok(await page.locator("#btnStorage").isDisabled());
  assert.ok(await page.locator("#btnTrack").count() === 0);
  assert.ok(await page.locator("#btnHtml").isDisabled());
  assert.ok(await page.locator("#btnCsv").isDisabled());

  await page.locator("#grid td.cell").first().click();
  await page.waitForSelector("#panel.open");
  assert.match(await page.locator("#pBody").textContent(), /Synthetic guest-test itinerary/);
  assert.ok(await page.locator("#btnTrack").isDisabled());
  await page.locator("#pClose").click();
  await page.locator("#fCashRefund").check();
  await page.waitForSelector("#grid tbody td.empty");
  await page.locator("#btnClear").click();
  await page.waitForSelector("#grid td.cell");

  await page.locator("#saved option").last().waitFor({ state: "attached" });
  await page.locator("#saved").selectOption("guest-florida-demo");
  await page.locator("#btnOpen").click();
  await page.waitForSelector("#grid td.cell");
  await page.locator("#btnSample").click();
  await page.waitForSelector("#grid td.cell");
  await page.locator('[data-tab="vendors"]').click();
  assert.match(await page.locator("#view").textContent(), /Demo Airline/);
  assert.deepEqual(requests, ["GET /?guest=1"]);
  assert.deepEqual(errors, []);
  console.log("Guest browser PASS: sample, grid, details, filters, vendors, no HTTP");
} finally {
  await browser.close();
}
