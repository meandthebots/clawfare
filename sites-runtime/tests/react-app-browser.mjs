#!/usr/bin/env node
/** Browser regression for the authenticated, estimate-only React app. */
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { chromium } from "playwright";

const template = readFileSync(process.argv[2] || "build/frontend/web/index.html", "utf8");
const sample = JSON.parse(readFileSync(process.argv[3] || "build/sites/clawfare-sample.json", "utf8"));
const marker = "<script>window.__SNAPSHOT__ = null;</script>";
assert.ok(template.includes(marker), "Standalone snapshot marker must survive Vite");
const html = template.replace(marker,
  "<script>window.__SNAPSHOT__ = null;" +
  "window.__CLAWFARE_HOSTED_READ_ONLY__ = true;" +
  "window.__CLAWFARE_HOSTED_ESTIMATE_ONLY__ = true;</script>");
const requests = [];
const browser = await chromium.launch({ headless: true });
try {
  const page = await browser.newPage({ viewport: { width: 1300, height: 850 } });
  const errors = [];
  page.on("pageerror", error => errors.push(error.message));
  await page.route("**/*", async route => {
    const request = route.request(), url = new URL(request.url());
    if (url.origin !== "https://clawfare.test") {
      errors.push("Unexpected outbound request: " + request.url());
      return route.abort();
    }
    if (url.pathname === "/") return route.fulfill({
      status: 200, body: html, contentType: "text/html",
    });
    requests.push({ path: url.pathname, method: request.method(),
      headers: request.headers(), body: request.postData() });
    const reply = (value, status = 200) => route.fulfill({
      status, body: JSON.stringify(value), contentType: "application/json",
    });
    if (url.pathname === "/api/status") return reply({
      cacheDir: "d1", searchesDir: "d1", providers: [],
    });
    if (url.pathname === "/api/sample") return reply(sample);
    if (url.pathname === "/api/searches") return reply([{
      id: sample.id, title: "Saved " + sample.title,
    }]);
    if (url.pathname === "/api/results/" + sample.id) return reply(sample);
    if (url.pathname === "/api/quota") return reply([]);
    if (url.pathname === "/api/storage") return reply({
      backend: "d1", sampleStored: true,
      firstImportedAt: "2026-11-17T00:00:00Z", sha256: "a".repeat(64),
    });
    if (url.pathname === "/api/estimate" && request.method() === "POST") {
      const body = JSON.parse(request.postData() || "{}");
      assert.equal(body.confirmed, false);
      assert.deepEqual(body.origins, ["LHR", "LGW"]);
      assert.deepEqual(body.providers, ["serpapi"]);
      return reply({ liveCalls: 2, cachedCalls: 0, skippedCalls: 0,
        returnLookupsUpperBound: 2, quota: [], lines: ["No calls executed."],
        warnings: [], canRun: false });
    }
    errors.push("Unexpected backend request: " + request.method() + " " + url.pathname);
    return reply({ error: "Unexpected API request" }, 405);
  });
  await page.goto("https://clawfare.test/");
  await page.waitForSelector("#grid td.cell");

  // Dark is the first-paint default, regardless of the operating system theme.
  const theme = () => page.evaluate(() => ({
    name: document.documentElement.dataset.theme,
    scheme: getComputedStyle(document.documentElement).colorScheme,
    background: getComputedStyle(document.body).backgroundColor,
  }));
  const dark = await theme();
  assert.equal(dark.name, "dark");
  assert.equal(dark.scheme, "dark");
  assert.equal(await page.locator("#themeToggle").getAttribute("aria-pressed"), "true");

  // Light mode must update every surface and survive a full reload.
  await page.locator("#themeToggle").click();
  const light = await theme();
  assert.equal(light.name, "light");
  assert.equal(light.scheme, "light");
  assert.notEqual(light.background, dark.background);
  assert.equal(await page.locator("#themeToggle").getAttribute("aria-pressed"), "false");
  await page.reload();
  await page.waitForSelector("#grid td.cell");
  assert.deepEqual(await theme(), light, "Saved light preference should apply before React mounts");
  await page.locator("#themeToggle").click();
  assert.deepEqual(await theme(), dark, "Switching back restores the original dark palette");
  await page.reload();
  await page.waitForSelector("#grid td.cell");
  assert.deepEqual(await theme(), dark, "Dark preference survives reload");

  assert.ok(await page.locator("#btnRun").isDisabled());
  assert.ok(await page.locator("#btnHtml").isDisabled());
  assert.ok(await page.locator("#btnCsv").isDisabled());
  assert.ok(await page.locator("#btnEstimate").isEnabled());

  await page.locator("#fCashRefund").check();
  await page.waitForSelector("#grid tbody td.empty");
  assert.match(await page.locator("#grid tbody td.empty").textContent(), /No itineraries/);
  await page.locator("#btnClear").click();
  await page.waitForSelector("#grid td.cell");
  assert.equal(await page.locator("#fCashRefund").isChecked(), false);

  // Start a generic estimate using the provider supported by this Site.
  assert.equal(await page.locator("#origins").inputValue(), "");
  assert.equal(await page.locator("#destinations").inputValue(), "");
  await page.locator("#origins").fill("LHR, LGW");
  await page.locator("#destinations").fill("TPA");
  await page.locator("#outFrom").fill("2027-04-12");
  await page.locator("#outTo").fill("2027-04-12");
  await page.locator("#nights").fill("8");
  await page.locator("#pSerp").check();
  for (const id of ["pSearch", "pTp", "pDuffel"]) {
    assert.equal(await page.locator("#" + id).isDisabled(), true);
  }
  await page.locator("#btnEstimate").click();
  await page.waitForFunction(() =>
    document.querySelector("#estimate")?.textContent?.includes("potential provider requests"));
  assert.match(await page.locator("#estimate").textContent(), /No calls executed/);
  assert.ok(await page.locator("#btnRun").isDisabled(), "Hosted estimate must not unlock paid search");

  await page.locator("#btnStorage").click();
  await page.waitForFunction(() =>
    document.querySelector("#statusLine")?.textContent?.includes("D1 sample stored since"));
  assert.match(await page.locator("#statusLine").textContent(), /2026-11-17T00:00:00Z/);

  await page.locator("#saved option").last().waitFor({ state: "attached" });
  await page.locator("#saved").selectOption(sample.id);
  await page.locator("#btnOpen").click();
  await page.waitForSelector("#grid td.cell");
  assert.match(await page.locator("#title").textContent(), /Florida|Tampa|Mexico/i);

  assert.equal(requests.some(call => call.path === "/api/session"), false);
  assert.equal(requests.some(call => call.path === "/api/search" ||
    call.path.startsWith("/api/export/") ||
    call.path.endsWith("/vendors") || call.path.endsWith("/track")), false);
  assert.equal(requests.filter(call => call.method === "POST").length, 1);
  assert.ok(!requests.some(call => "x-clawfare-session" in call.headers));
  assert.deepEqual(errors, [], "No unhandled browser errors or unexpected egress");
  await page.close();
  console.log("React browser PASS: hosted estimate, D1, saved results, filters, no provider calls");
} finally {
  await browser.close();
}
