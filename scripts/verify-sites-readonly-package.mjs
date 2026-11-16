#!/usr/bin/env node
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { createRequire } from "node:module";
import { readFileSync, readdirSync } from "node:fs";
import { resolve, join } from "node:path";
import { kotlinEngineFromBundle } from "../sites-runtime/src/kotlin-engine.mjs";
import { validatePublicSample } from "../sites-runtime/src/public-sample.mjs";
const root = resolve(process.argv[2] || "build/sites-readonly-package");
const require = createRequire(import.meta.url);

const manifest = JSON.parse(readFileSync(join(root, "manifest.json"), "utf8"));
assert.equal(manifest.format, "clawfare-sites-readonly-source-v1");
assert.equal(manifest.readOnly, true);
assert.equal(manifest.publicHttpEntrypoint, false);
assert.equal(manifest.identityBindingRequired, true);
assert.equal(manifest.d1BindingName, "DB");
assert.ok(manifest.files.length >= 12);
const paths = new Set(manifest.files.map(x => x.path));
assert.equal(paths.size, manifest.files.length, "Duplicated packaged asset path");
for (const file of manifest.files) {
  assert.match(file.path, /^(public|server|SITES-HANDOFF\.md)/);
  assert.ok(!file.path.startsWith(".openai/") && !file.path.includes(".env"));
  const bytes = readFileSync(join(root, file.path));
  const digest = createHash("sha256").update(bytes).digest("hex");
  assert.equal(digest, file.sha256, "Package SHA-256 mismatch: " + file.path);
  assert.equal(bytes.length, file.size, "Package byte size mismatch: " + file.path);
}
assert.deepEqual(readdirSync(join(root, "public")), ["index.html"]);
const page = readFileSync(join(root, "public/index.html"), "utf8");
assert.ok(page.includes("window.__CLAWFARE_HOSTED_READ_ONLY__ = true"));
assert.ok(page.includes("Hosted read-only preview"));
const sample = JSON.parse(readFileSync(join(root, "server/fixtures/clawfare-sample.json"), "utf8"));
validatePublicSample(sample);
assert.equal(sample.id, manifest.sampleSlug);
const engine = kotlinEngineFromBundle(require(join(root, "server/kotlin/fare-core.cjs")));
const query = JSON.stringify({
  origins: ["LHR"], destinations: ["TPA"], outboundFrom: "2028-02-28",
  tripType: "one_way", providers: ["serpapi"],
});
const result = JSON.parse(engine.planSearchJson(query));
assert.equal(result.error ?? null, null);
assert.deepEqual(result.pairs, [{ outbound: "2028-02-28" }]);
assert.ok(paths.has("server/src/site-handler.mjs"));
assert.ok(paths.has("server/src/public-sample.mjs"));
assert.ok(paths.has("server/migrations/0001_provider_http_budget.sql"));
assert.ok(paths.has("server/migrations/0002_investigation_snapshots.sql"));
assert.ok(paths.has("server/migrations/0003_public_sample_fixtures.sql"));
assert.ok(!paths.has("server/fixtures/private-investigations.json"));
assert.ok(!paths.has("public/clawfare-sample.json"));
console.log("Clawfare Sites source handoff verified: " + paths.size + " SHA-256 checked files; real Kotlin bridge and public fixture present");
