#!/usr/bin/env node
// Exercise the actual webpack production artifact, not just Kotlin/JS unit
// tests. This is the module that a compatible hosted JS runtime would import.
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { existsSync, readdirSync, readFileSync } from "node:fs";
import { resolve } from "node:path";

const require = createRequire(import.meta.url);
const directory = process.argv[2] || "fare-core/build/kotlin-webpack/js/productionExecutable";
assert.ok(existsSync(directory), `Kotlin/JS output directory does not exist: ${directory}`);
const candidates = readdirSync(directory).filter(name => name.endsWith(".js") && !name.includes("hot-update"));
assert.equal(candidates.length, 1, "Expected exactly one bundled executable JS file");
const bundle = resolve(directory, candidates[0]);
const source = readFileSync(bundle, "utf8");
assert.ok(source.length > 1000, "Bundled Kotlin artifact is unexpectedly small");
for (const name of ["SERPAPI_API_KEY", "SEARCHAPI_API_KEY", "DUFFEL_ACCESS_TOKEN"]) {
  assert.ok(!source.includes(name), `Provider credential variable in browser bundle: ${name}`);
}
const exports = require(bundle);
// Kotlin's namespace layout may change between compiler versions, but the
// public exported object and functions must remain callable without a JVM.
function locateBridge(obj, depth = 0) {
  if (!obj || depth > 8 || (typeof obj !== "object" && typeof obj !== "function")) return null;
  if (typeof obj.normalizeSearchJson === "function" && typeof obj.planSearchJson === "function" &&
      typeof obj.projectBudgetJson === "function") return obj;
  for (const key of Object.keys(obj)) {
    const result = locateBridge(obj[key], depth + 1);
    if (result) return result;
  }
  return null;
}
console.log("Kotlin/JS bundle inspection:", JSON.stringify({
  byteSize: source.length,
  hasExportName: source.includes("ClawfareJs"),
  exportKeys: Object.keys(exports).slice(0, 20),
  defaultExportKeys: Object.keys(exports?.default ?? {}).slice(0, 20),
  namespaceKeys: Object.keys(exports?.["fare-core"] ?? {}).slice(0, 20),
  exportType: typeof exports,
}));
function describeNamespace(root, path = "", depth = 0, out = []) {
  if (!root || depth > 6 || out.length >= 20) return out;
  if (typeof root !== "object" && typeof root !== "function") return out;
  const keys = Object.getOwnPropertyNames(root).filter(key => !["prototype", "length", "name", "caller", "arguments"].includes(key));
  out.push({ path: path || "<root>", type: typeof root, keys: keys.slice(0, 12) });
  for (const key of keys.slice(0, 8)) {
    if (out.length >= 20) break;
    let value;
    try { value = root[key]; } catch { continue; }
    describeNamespace(value, (path ? path + "." : "") + key, depth + 1, out);
  }
  return out;
}
console.log("Kotlin/JS namespace tree:", JSON.stringify(describeNamespace(exports)));
const bridge = locateBridge(exports);
assert.ok(bridge, "Kotlin/JS ClawfareJs public exports missing from bundled module");
const raw = JSON.stringify({
  origins: [" lhr, LGW ", "LHR"],
  destinations: ["tpa"],
  outboundFrom: "2028-02-28",
  nights: 2,
  providers: ["serpapi"],
});
const normalized = JSON.parse(bridge.normalizeSearchJson(raw));
assert.deepEqual(normalized.origins, ["LHR", "LGW"]);
assert.deepEqual(normalized.destinations, ["TPA"]);
const plan = JSON.parse(bridge.planSearchJson(raw));
assert.equal(plan.error ?? null, null);
assert.deepEqual(plan.pairs, [{ outbound: "2028-02-28", returning: "2028-03-01" }]);
const projected = JSON.parse(bridge.projectBudgetJson(raw, JSON.stringify([
  { id: "serpapi", configured: true, calls: [{ description: "LHR to TPA", cached: false }] },
])));
assert.equal(projected.liveCalls, 1);
assert.equal(projected.returnLookupsUpperBound, 3);
assert.equal(bridge.toGbpOrNull(250, 1.25), 200);
console.log("Kotlin/JS production bundle: contract verified for normalization, planning, budget and FX");
