#!/usr/bin/env node
/**
 * Inline Vite's finished assets AFTER the StyleX unplugin has appended its
 * atomic CSS. vite-plugin-singlefile performs inlining inside generateBundle
 * and can run before StyleX writes its generated CSS, silently dropping rules.
 *
 * Both Kotlin's embedded resource and private Sites require one offline HTML
 * document with no external scripts, stylesheets, provider calls or CDN links.
 */
import assert from "node:assert/strict";
import { existsSync, readFileSync, readdirSync, unlinkSync, writeFileSync } from "node:fs";
import { dirname, isAbsolute, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const outDir = fileURLToPath(new URL("../build/frontend/web/", import.meta.url));
const htmlPath = join(outDir, "index.html");
let html = readFileSync(htmlPath, "utf8");

function assets(dir) {
  return readdirSync(dir, { withFileTypes: true }).flatMap(item => {
    const file = join(dir, item.name);
    return item.isDirectory() ? assets(file) : [file];
  });
}
function localAsset(url) {
  const clean = url.split(/[?#]/, 1)[0] ?? "";
  assert.ok(!/^(?:https?:|data:|file:|\/\/)/i.test(clean), "Remote asset forbidden: " + clean);
  const file = resolve(outDir, clean.replace(/^\//, ""));
  assert.ok(relative(outDir, file) !== ".." && !relative(outDir, file).startsWith("../"),
    "Build asset escapes output directory: " + clean);
  assert.ok(existsSync(file), "Missing Vite asset: " + clean);
  return file;
}
function attribute(tag, key) {
  const match = new RegExp("\\b" + key + "=(?:\"([^\"]+)\"|'([^']+)')", "i").exec(tag);
  return match?.[1] ?? match?.[2] ?? null;
}

// Resolve all CSS assets, not just those linked by Vite. StyleX can emit a
// fallback stylesheet after Vite constructs index.html.
const linkedCss = new Set();
html = html.replace(/<link\b[^>]*>/gi, tag => {
  if (attribute(tag, "rel") !== "stylesheet") return tag;
  const href = attribute(tag, "href");
  assert.ok(href, "Stylesheet link missing href");
  linkedCss.add(localAsset(href));
  return "";
});
const allCss = assets(outDir).filter(path => path.endsWith(".css")).sort();
assert.ok(allCss.length > 0, "Vite/StyleX produced no CSS assets");
const css = allCss.map(path => readFileSync(path, "utf8")).join("\n");
assert.ok(css.length > 100, "Vite/StyleX CSS is unexpectedly empty");
assert.ok(html.includes("</head>"), "Vite HTML head missing");
html = html.replace("</head>", "<style>" + css.replace(/<\/style/gi, "<\\/style") + "</style></head>");

let scripts = 0;
const jsFiles = new Set();
html = html.replace(/<script\b[^>]*\bsrc=(["'])([^"']+)\1[^>]*><\/script>/gi, (tag, _q, url) => {
  const path = localAsset(url);
  assert.ok(path.endsWith(".js"), "Only compiled JS can be inlined");
  jsFiles.add(path);
  scripts++;
  const code = readFileSync(path, "utf8").replace(/<\/script/gi, "<\\/script");
  return "<script type=\"module\">" + code + "</script>";
});
assert.equal(scripts, 1, "Expected one bundled Vite JS entry");
assert.ok(html.includes("<script>window.__SNAPSHOT__ = null;</script>"),
  "Report export marker must remain byte-identical");
assert.doesNotMatch(html, /(?:src|href)=(["'])\/?assets\//,
  "Standalone HTML must contain no external Vite assets");
writeFileSync(htmlPath, html);
for (const path of new Set([...allCss, ...jsFiles])) unlinkSync(path);
console.log("Clawfare offline HTML: inlined", jsFiles.size, "JS and", allCss.length,
  "CSS assets after StyleX CSS extraction");
