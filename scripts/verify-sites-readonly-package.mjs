#!/usr/bin/env node
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { createRequire } from "node:module";
import { readFileSync, readdirSync } from "node:fs";
import { resolve, join } from "node:path";
import { pathToFileURL } from "node:url";
import { kotlinEngineFromBundle } from "../sites-runtime/src/kotlin-engine.mjs";
import { validatePublicSample } from "../sites-runtime/src/public-sample.mjs";
const root = resolve(process.argv[2] || "build/sites-readonly-package");
const require = createRequire(import.meta.url);

const manifest = JSON.parse(readFileSync(join(root, "manifest.json"), "utf8"));
assert.equal(manifest.format, "clawfare-sites-readonly-source-v1");
assert.equal(manifest.readOnly, true);
assert.equal(manifest.publicHttpEntrypoint, false);
assert.equal(manifest.identityBindingRequired, true);
assert.equal(manifest.workerModule, "server/worker.mjs");
assert.equal(manifest.workerRequiresTrustedFactory, true);
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
const provenance = JSON.parse(readFileSync(join(root, "server/build-provenance.json"), "utf8"));
assert.deepEqual(manifest.provenance, provenance);
assert.match(provenance.sourceGitSha, /^[a-f0-9]{40}$/);
assert.equal(typeof provenance.sourceDirty, "boolean");
const digest = path => manifest.files.find(file => file.path === path)?.sha256;
assert.equal(provenance.kotlinBundleSha256, digest("server/kotlin/fare-core.cjs"));
assert.equal(provenance.openApiSha256, digest("server/openapi.json"));
assert.deepEqual(provenance.migrations.map(migration => migration.file),
  readdirSync(join(root, "server/migrations")).sort());
for (const migration of provenance.migrations) {
  assert.equal(migration.sha256, digest("server/migrations/" + migration.file));
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
assert.ok(paths.has("server/worker-source.mjs"));
assert.ok(paths.has("server/worker.mjs"));
assert.ok(paths.has("server/src/worker-factory.mjs"));
const workerSource = readFileSync(join(root, "server/worker-source.mjs"), "utf8");
assert.ok(workerSource.includes("createClawfareWorkerFactory"));
assert.ok(workerSource.includes("bundledPublicSample"));
assert.ok(!workerSource.includes("export default"));
const workerModule = await import(pathToFileURL(join(root, "server/worker.mjs")).href);
assert.equal(typeof workerModule.createTrustedWorker, "function");
const workerCore = workerModule.clawfareSitesWorker.kotlin;
assert.equal(workerCore.toGbpOrNull(250, 1.25), 200);
assert.throws(() => workerModule.createTrustedWorker({}), /Verified Sites identity callback/);
const worker = workerModule.createTrustedWorker({
  getVerifiedEmail: async () => null,
});
const fakeD1 = { prepare() {
  return { bind() {
    return { async first() { return null; }, async all() { return { results: [] }; } };
  } };
} };
const env = { DB: fakeD1, OWNER_KEY_SECRET: "long-unpredictable-secret-for-tests-only-not-deployment" };
const rendered = await worker.fetch(new Request("https://example.com/"), env);
assert.equal(rendered.status, 200);
const renderedHtml = await rendered.text();
assert.ok(renderedHtml.includes("window.__CLAWFARE_HOSTED_READ_ONLY__ = true"));
assert.equal(renderedHtml.match(/window\.__CLAWFARE_HOSTED_READ_ONLY__ = true/g)?.length, 1);
assert.equal((await worker.fetch(new Request("https://example.com/api/sample"), {})).status, 503);
assert.equal((await worker.fetch(new Request("https://example.com/api/searches", {
  headers: { "oai-authenticated-user-email": "forged@example.com" },
}), env)).status, 401);
assert.equal((await worker.fetch(new Request("https://example.com/api/search", {
  method: "POST",
}), env)).status, 405);
const authenticated = workerModule.createTrustedWorker({
  getVerifiedEmail: async () => "owner@example.com",
});
const storagePath = "https://example.com/api/storage";
assert.equal((await worker.fetch(new Request(storagePath), env)).status, 401);
const storage = await authenticated.fetch(new Request(storagePath), env);
assert.equal(storage.status, 200);
const { build } = await storage.json();
assert.equal(build.sourceGitSha, provenance.sourceGitSha);
assert.equal(build.kotlinBundleSha256, provenance.kotlinBundleSha256);
assert.equal(build.schema, "missing_tables");
assert.ok(build.missingSchema.some(migration => migration.tables.includes("public_sample_fixtures")));
const estimateResponse = await authenticated.fetch(new Request("https://example.com/api/estimate", {
  method: "POST",
  headers: { "content-type": "application/json" },
  body: JSON.stringify({
    origins: ["LHR"], destinations: ["TPA"],
    outboundFrom: "2028-02-28", outboundTo: "2028-02-28",
    nights: 2, tripType: "round_trip", returnChoices: 3,
    providers: ["serpapi"],
  }),
}), env);
assert.equal(estimateResponse.status, 200);
const estimate = await estimateResponse.json();
assert.equal(estimate.liveCalls, 1);
assert.equal(estimate.returnLookupsUpperBound, 3);
assert.equal(estimate.canRun, false);
assert.equal(estimateResponse.headers.get("cache-control"), "private, no-store");
assert.ok(paths.has("server/src/site-handler.mjs"));
assert.ok(paths.has("server/src/public-sample.mjs"));
assert.ok(paths.has("server/migrations/0001_provider_http_budget.sql"));
assert.ok(paths.has("server/migrations/0002_investigation_snapshots.sql"));
assert.ok(paths.has("server/migrations/0003_public_sample_fixtures.sql"));
assert.ok(!paths.has("server/fixtures/private-investigations.json"));
assert.ok(!paths.has("public/clawfare-sample.json"));
console.log("Clawfare Sites source handoff verified: " + paths.size + " SHA-256 checked files; real Kotlin bridge and public fixture present");
