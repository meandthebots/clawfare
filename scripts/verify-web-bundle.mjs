#!/usr/bin/env node
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
const html = readFileSync("build/frontend/web/index.html", "utf8");
const marker = "<script>window.__SNAPSHOT__ = null;</script>";
assert.equal(html.split(marker).length, 2, "HTML export and Sites must have exactly one snapshot slot");
assert.match(html, /__CLAWFARE_HOSTED_READ_ONLY__/, "hosted safety mode must survive compilation");
assert.match(html, /\/api\/sample/, "API sample loader must survive compilation");
assert.match(html, /mobile-flight/, "mobile cards must survive compilation");
assert.match(html, /id="root"/, "React mount point must be present");
assert.doesNotMatch(html, /src="\/assets\//, "external assets break standalone reports");
console.log("Clawfare self-contained React HTML verification PASS");
