#!/usr/bin/env node
/**
 * Build a complete, portable read-only Sites SOURCE package without publishing.
 * All inputs are generated or versioned in this repository. This intentionally
 * does not fabricate .openai/hosting.json or an identity-bearing Worker.
 */
import { createHash } from "node:crypto";
import {
  copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync,
  rmSync, writeFileSync,
} from "node:fs";
import { join, relative, resolve, sep } from "node:path";
import { renderHostedReadOnlyPage } from "../sites-runtime/src/hosted-page.mjs";
import { validatePublicSample } from "../sites-runtime/src/public-sample.mjs";

const base = resolve("build");
const output = resolve(process.argv[2] || "build/sites-readonly-package");
if (!output.startsWith(base + sep)) {
  throw new Error("Source package destination must be inside build/");
}
const fixtureSource = "build/sites/clawfare-sample.json";
const htmlSource = "src/main/resources/web/index.html";
const webpackDir = "fare-core/build/kotlin-webpack/js/productionExecutable";
const expectedModules = [
  "hosted-page.mjs", "site-handler.mjs", "read-only-api.mjs",
  "investigations.mjs", "public-sample.mjs", "provider-http-budget.mjs",
  "kotlin-engine.mjs",
];
for (const path of [fixtureSource, htmlSource, ...expectedModules.map(name => "sites-runtime/src/" + name)]) {
  if (!existsSync(path)) throw new Error("Missing required Sites source: " + path);
}
const sample = JSON.parse(readFileSync(fixtureSource, "utf8"));
validatePublicSample(sample);
const webpackFiles = readdirSync(webpackDir).filter(name => name.endsWith(".js") && !name.includes("hot-update"));
if (webpackFiles.length !== 1) throw new Error("Expected exactly one Kotlin/JS webpack production artifact");

rmSync(output, { recursive: true, force: true });
mkdirSync(output, { recursive: true });
const files = [];
function write(file, bytes) {
  const target = join(output, file);
  mkdirSync(resolve(target, ".."), { recursive: true });
  writeFileSync(target, bytes);
  const payload = Buffer.isBuffer(bytes) ? bytes : Buffer.from(bytes);
  files.push({
    path: file,
    sha256: createHash("sha256").update(payload).digest("hex"),
    size: payload.length,
  });
}
const html = renderHostedReadOnlyPage(readFileSync(htmlSource, "utf8"));
write("public/index.html", html);
write("server/fixtures/clawfare-sample.json", JSON.stringify(sample));
for (const name of expectedModules) {
  write("server/src/" + name, readFileSync("sites-runtime/src/" + name));
}
for (const name of [
  "0001_provider_http_budget.sql",
  "0002_investigation_snapshots.sql",
  "0003_public_sample_fixtures.sql",
]) write("server/migrations/" + name, readFileSync("sites-runtime/migrations/" + name));
write("server/kotlin/fare-core.cjs", readFileSync(join(webpackDir, webpackFiles[0])));
write("SITES-HANDOFF.md", readFileSync("docs/SITES-SOURCE-HANDOFF.md"));
const manifest = {
  format: "clawfare-sites-readonly-source-v1",
  readOnly: true,
  publicHttpEntrypoint: false,
  identityBindingRequired: true,
  d1BindingName: "DB",
  sampleSlug: sample.id,
  files: files.sort((a, b) => a.path.localeCompare(b.path)),
};
writeFileSync(join(output, "manifest.json"), JSON.stringify(manifest, null, 2) + "\n");
if (files.some(f => f.path.startsWith(".openai/") || f.path.includes(".env"))) {
  throw new Error("Unexpected private hosting configuration in package");
}
console.log("Clawfare Sites source package: " + relative(resolve("."), output) +
  " (" + files.length + " validated assets)");
