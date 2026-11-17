import assert from "node:assert/strict";
import test from "node:test";
import { readFileSync } from "node:fs";
import { renderHostedReadOnlyPage } from "../src/hosted-page.mjs";

const template = readFileSync("build/frontend/web/index.html", "utf8");

test("original UI uses JVM loopback session unless explicit hosted read-only flag", () => {
  assert.ok(template.includes("__CLAWFARE_HOSTED_READ_ONLY__"));
  assert.ok(template.includes("/api/session"));
  assert.ok(template.includes("X-Clawfare-Session"));
  assert.ok(template.includes("Paid search and mutations are disabled in hosted mode."));
});

test("hosted build reuses actual Kotlin HTML UI and explicitly disables mutations", () => {
  const result = renderHostedReadOnlyPage(template);
  assert.ok(result.length > 70000);
  assert.ok(result.includes("window.__CLAWFARE_HOSTED_READ_ONLY__ = true"));
  assert.ok(result.includes("/api/sample"));
  assert.ok(result.includes("/api/searches"));
  assert.ok(result.includes("btnRun"));
  assert.ok(!template.includes("window.__CLAWFARE_HOSTED_READ_ONLY__ = true"));
});

test("unknown templates cannot silently remove the host mode injection", () => {
  assert.throws(() => renderHostedReadOnlyPage("<html></html>"), /snapshot marker/);
  assert.throws(() => renderHostedReadOnlyPage(null), /HTML template/);
  const duplicate = '<script>window.__SNAPSHOT__ = null;</script>'.repeat(2);
  assert.throws(() => renderHostedReadOnlyPage(duplicate), /exactly one/);
});

test("estimate-only hosted pages expose a separate flag without enabling run/search", () => {
  const output = renderHostedReadOnlyPage(template, { allowEstimate: true });
  assert.ok(output.includes("window.__CLAWFARE_HOSTED_ESTIMATE_ONLY__ = true"));
  assert.ok(output.includes("window.__CLAWFARE_HOSTED_READ_ONLY__ = true"));
  assert.ok(output.includes('["btnRun", "btnHtml", "btnCsv"]'));
  assert.ok(!renderHostedReadOnlyPage(template).includes("window.__CLAWFARE_HOSTED_ESTIMATE_ONLY__ = true"));
});
