#!/usr/bin/env node
// Build a standalone, offline-only copy of the REAL compiled React UI.
// Never modify the production HTML consumed by Ktor or the private Site.
import { readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { resolve } from "node:path";
import { GUEST_SAMPLE } from "./guest-fixture.mjs";

const SLOT = "<script>window.__SNAPSHOT__ = null;</script>";
const DATA_TOKEN = "__CLAWFARE_GUEST_SAMPLE_JSON__";

function validateFixture(fixture) {
  if (!fixture || !fixture.id?.startsWith("guest-") ||
      !Array.isArray(fixture.itineraries) || fixture.itineraries.length < 2 ||
      !Array.isArray(fixture.addons) || !Array.isArray(fixture.cells) ||
      !Array.isArray(fixture.vendors) || !Array.isArray(fixture.warnings) ||
      fixture.sellerOptions?.length !== 0) {
    throw new Error("Guest data must be a complete, synthetic ResultSet.");
  }
  for (const it of [...fixture.itineraries, ...fixture.addons]) {
    if (it.source !== "sample" || it.sourceLabel !== "guest" ||
        it.livePrice !== false || it.cashRefundProofVerified !== false ||
        it.links?.length !== 0 || it.refundEvidence != null) {
      throw new Error("Guest itineraries must not imply live prices, refunds or bookings.");
    }
  }
  for (const vendor of fixture.vendors) {
    if (vendor.links?.length !== 0 || vendor.fresh !== false) {
      throw new Error("Guest seller links and fresh offers are forbidden.");
    }
  }
  const forbidden = /^(?:apiKey|token|accessToken|password|authorization|secret|bookingToken)$/i;
  function traverse(value) {
    if (Array.isArray(value)) return value.forEach(traverse);
    if (value && typeof value === "object") {
      for (const [key, item] of Object.entries(value)) {
        if (forbidden.test(key)) throw new Error("Guest data contains a private field: " + key);
        traverse(item);
      }
    }
  }
  traverse(fixture);
}

export function buildGuestHtml(productionHtml, fixture, runtime) {
  validateFixture(fixture);
  if (productionHtml.split(SLOT).length !== 2 ||
      productionHtml.split("<head>").length !== 2 ||
      productionHtml.split("</head>").length !== 2 ||
      productionHtml.split("<body>").length !== 2 ||
      runtime.split(DATA_TOKEN).length !== 2) {
    throw new Error("Unexpected HTML template or guest-runtime placeholder.");
  }
  if (/<script\b[^>]*\bsrc\s*=/i.test(productionHtml) ||
      /<link\b[^>]*rel=["']stylesheet/i.test(productionHtml)) {
    throw new Error("Guest must be self-contained: production bundle has external assets.");
  }
  const data = JSON.stringify(fixture).replaceAll("<", "\\u003c")
    .replaceAll("\u2028", "\\u2028").replaceAll("\u2029", "\\u2029");
  const injectedRuntime = runtime.replace(DATA_TOKEN, data);
  const csp = "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; " +
    "script-src 'unsafe-inline'; style-src 'unsafe-inline'; " +
    "img-src data: blob:; font-src data:; connect-src 'none'; " +
    "base-uri 'none'; form-action 'none'\">";
  const banner = "<div role=\"status\" id=\"guestPreviewNotice\" " +
    "style=\"padding:12px 20px;background:#ffe8a3;color:#18222d;" +
    "font:600 14px system-ui;border-bottom:2px solid #c68d05\">" +
    "GUEST PREVIEW — completely synthetic flights and prices. " +
    "No login, real search, provider calls, bookings or server storage. " +
    "Saved demo searches disappear when the page is reloaded.</div>";
  return productionHtml
    .replace("<head>", "<head>" + csp)
    .replace("</head>", "<script>" + injectedRuntime + "</script></head>")
    .replace("<body>", "<body>" + banner);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const productionPath = new URL("../build/frontend/web/index.html", import.meta.url);
  const outputPath = new URL("../build/guest-preview.html", import.meta.url);
  const runtimePath = new URL("./guest-runtime.js", import.meta.url);
  const original = readFileSync(productionPath, "utf8");
  const result = buildGuestHtml(original, GUEST_SAMPLE, readFileSync(runtimePath, "utf8"));
  writeFileSync(outputPath, result, "utf8");
  // Verify that compiling the guest copy did not mutate the production artifact.
  if (readFileSync(productionPath, "utf8") !== original) {
    throw new Error("The private production bundle was modified.");
  }
  console.log("Offline guest preview: build/guest-preview.html (no credentials or network).");
}
