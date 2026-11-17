import assert from "node:assert/strict";
import { test } from "node:test";
import { parseTrustLevel } from "./trustLevel.ts";

test("seller trust parses to a closed, fail-safe set", () => {
  assert.equal(parseTrustLevel("airline"), "airline");
  assert.equal(parseTrustLevel("trusted"), "trusted");
  assert.equal(parseTrustLevel("caution"), "caution");
  assert.equal(parseTrustLevel("risky"), "risky");
  assert.equal(parseTrustLevel("unverified"), "risky");
  assert.equal(parseTrustLevel(undefined), "risky");
  assert.equal(parseTrustLevel(null), "risky");
  assert.equal(parseTrustLevel({ trust: "airline" }), "risky");
});
