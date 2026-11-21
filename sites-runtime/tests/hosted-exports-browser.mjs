#!/usr/bin/env node
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { chromium } from "playwright";

const template = readFileSync(process.argv[2], "utf8");
const sample = JSON.parse(readFileSync(process.argv[3], "utf8"));
const marker = "<script>window.__SNAPSHOT__ = null;</script>";
assert.ok(template.includes(marker));
const html = template.replace(marker,
  "<script>window.__SNAPSHOT__ = null; window.__CLAWFARE_HOSTED_READ_ONLY__ = true;</script>");
const origin = "https://clawfare.test";
const result = {
  ...sample,
  id: "tokyo",
  title: "London to Tokyo",
  scenarioLabel: null,
  query: { market: "uk", tripType: "one_way" },
  suggestedFilters: {},
  itineraries: [{
    ...sample.itineraries[0],
    id: "tokyo-outbound",
    origin: "LHR",
    destination: "NRT",
    returnDate: null,
    returning: null,
    passengers: 2,
    cashRefundProofVerified: false,
    refundEvidence: null,
  }],
  addons: [], cells: [], vendors: [], sellerOptions: [],
};
const browser = await chromium.launch();
try {
  for (const isMobile of [false, true]) {
    const requests = [];
    const unexpected = [];
    const page = await browser.newPage({
      viewport: isMobile ? { width: 390, height: 844 } : { width: 1280, height: 900 },
      isMobile, hasTouch: isMobile, acceptDownloads: true,
    });
    await page.route("**/*", async route => {
      const req = route.request();
      const path = new URL(req.url()).pathname;
      if (!req.url().startsWith(origin + "/")) {
        unexpected.push(req.url());
        return route.abort();
      }
      if (path === "/") return route.fulfill({ contentType: "text/html", body: html });
      if (path === "/api/status") return route.fulfill({
        json: { cacheDir: "d1", searchesDir: "d1", providers: [], hostedExports: true },
      });
      if (path === "/api/sample") return route.fulfill({ json: sample });
      if (path === "/api/searches") return route.fulfill({ json: [{ id: result.id, title: result.title }] });
      if (path === "/api/results/tokyo") return route.fulfill({ json: result });
      if (/^\/api\/results\/tokyo\/export\.(csv|html)$/.test(path)) {
        requests.push(req.postDataJSON());
        assert.equal(req.method(), "POST");
        const ext = path.split(".").at(-1);
        return route.fulfill({
          contentType: ext === "html" ? "text/html" : "text/csv",
          headers: { "Content-Disposition": `attachment; filename="clawfare-tokyo.${ext}"` },
          body: ext === "html" ? "<!doctype html><h1>London to Tokyo</h1>" : "export_version,origin\nclawfare-saved-v1,LHR\n",
        });
      }
      unexpected.push(req.url());
      return route.abort();
    });
    await page.goto(origin);
    await page.locator("#saved option[value='tokyo']").waitFor({ state: "attached" });
    assert.equal(await page.locator("#btnHtml").isDisabled(), true);
    await page.locator("#saved").selectOption("tokyo");
    await page.locator("#btnOpen").click();
    await page.waitForFunction(() => document.querySelector("#title")?.textContent === "London to Tokyo");
    const [download] = await Promise.all([
      page.waitForEvent("download"), page.locator("#btnCsv").click(),
    ]);
    assert.equal(download.suggestedFilename(), "clawfare-tokyo.csv");
    assert.deepEqual(requests[0], { kind: "itineraries", itineraryIds: ["tokyo-outbound"] });
    await page.locator("#fCashRefund").check();
    const [empty] = await Promise.all([
      page.waitForEvent("download"), page.locator("#btnHtml").click(),
    ]);
    assert.equal(empty.suggestedFilename(), "clawfare-tokyo.html");
    assert.deepEqual(requests[1], { kind: "itineraries", itineraryIds: [] });
    assert.deepEqual(unexpected, []);
    await page.close();
  }
  console.log("Hosted export browser PASS: private HTML and CSV with desktop and mobile filters");
} finally {
  await browser.close();
}
