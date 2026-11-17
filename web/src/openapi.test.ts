import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { createGeneratedClient } from "./generated/openapi-client.ts";

test("each OpenAPI operation has a generated browser method", () => {
  const spec = JSON.parse(readFileSync(new URL("../../api/openapi.json", import.meta.url), "utf8"));
  const names = Object.values(spec.paths).flatMap((methods: unknown) =>
    Object.values(methods as Record<string, { operationId: string }>).map((op) => op.operationId),
  );
  const client = createGeneratedClient(async () => ({
    status: 200,
    body: {},
    text: "{}",
    consent: null,
  }));
  assert.deepEqual(Object.keys(client).sort(), names.sort());
  assert.equal(spec.components.schemas.ResultSet.properties.itineraries.type, "array");
  assert.equal(spec.components.schemas.SearchQuery.properties.origins.type, "array");
});

test("generated calls bind parameters, JSON bodies and consent", async () => {
  const calls: Array<{ path: string; body: unknown; consent: string | null | undefined }> = [];
  const client = createGeneratedClient(async (path, body, consent) => {
    calls.push({ path, body, consent });
    return { status: 200, body: {}, text: "{}", consent: null };
  });
  await client.getResult("trip-2026");
  await client.getHistory("trip-2026", "AA:123", 5);
  const query = {
    origins: ["LHR"],
    destinations: ["TPA"],
    outboundFrom: "2026-12-16",
    tripType: "round_trip",
    adults: 2,
    cabin: "business",
    cabinShortHaul: "economy",
    checkedBags: 1,
    currency: "GBP",
    providers: [],
    nonstopOnly: false,
    returnChoices: 3,
    fetchReturns: true,
    cacheOnly: true,
    confirmed: false,
  } as const;
  const body = {
    ...query,
    origins: [...query.origins],
    destinations: [...query.destinations],
    providers: [...query.providers],
  };
  await client.estimate(body);
  await client.search(body, "consent-test");
  assert.deepEqual(
    calls.map((c) => c.path),
    [
      "/api/results/trip-2026",
      "/api/history/trip-2026/AA%3A123?limit=5",
      "/api/estimate",
      "/api/search",
    ],
  );
  assert.equal(calls[2]?.consent, undefined);
  assert.equal(calls[3]?.consent, "consent-test");
  assert.deepEqual(calls[2]?.body, body);
});
