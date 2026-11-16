#!/usr/bin/env node
// Verify the exact JVM-exported ResultSet can be loaded by the hosted D1
// public-sample bootstrapper. No database, network, credentials or user data.
import { readFileSync } from "node:fs";
import { validatePublicSample } from "../sites-runtime/src/public-sample.mjs";

const file = process.argv[2];
if (!file) throw new Error("Usage: node scripts/validate-kotlin-public-sample.mjs path/to/clawfare-sample.json");
const json = JSON.parse(readFileSync(file, "utf8"));
const canonical = validatePublicSample(json);
console.log(`Public Kotlin ResultSet verified: ${json.itineraries.length} primary itineraries, ${json.addons.length} add-ons, ${Buffer.byteLength(canonical)} bytes`);
