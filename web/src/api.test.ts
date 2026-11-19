import assert from "node:assert/strict";
import { test } from "node:test";
import { createClawfareClient, type HttpClient } from "./api.ts";
import { isEstimate, isResultSet } from "./domain.ts";

const sampleQuery = {
  origins: ["LHR"],
  destinations: ["TPA"],
  outboundFrom: "2026-12-16",
  tripType: "round_trip",
  adults: 2,
  cabin: "premium_economy",
  cabinShortHaul: "economy",
  checkedBags: 1,
  currency: "GBP",
  providers: ["serpapi"],
  nonstopOnly: false,
  returnChoices: 3,
  fetchReturns: true,
  cacheOnly: false,
  confirmed: false,
} as const;

test("hosted pages never request JVM session or send an identity header", async () => {
  const requests: Array<{ path: string; init?: RequestInit }> = [];
  const fetcher: HttpClient = async (path, init) => {
    requests.push({ path: String(path), init });
    return new Response(
      JSON.stringify({
        liveCalls: 2,
        cachedCalls: 0,
        skippedCalls: 1,
        returnLookupsUpperBound: 3,
        warnings: [],
        lines: [],
        canRun: false,
      }),
      { status: 200, headers: { "Content-Type": "application/json" } },
    );
  };
  const client = createClawfareClient({ hostedReadOnly: true, hostedEstimateOnly: true }, fetcher);
  const estimate = await client.estimate({
    ...sampleQuery,
    origins: [...sampleQuery.origins],
    destinations: [...sampleQuery.destinations],
    providers: [...sampleQuery.providers],
  });
  assert.equal(estimate.ok, true);
  assert.deepEqual(
    requests.map((request) => request.path),
    ["/api/estimate"],
  );
  assert.equal(requests[0]?.init?.method, "POST");
  assert.deepEqual(Object.keys(requests[0]?.init?.headers ?? {}).sort(), [
    "Accept",
    "Content-Type",
  ]);
  assert.equal((await client.request("/api/search", { confirmed: true })).status, 405);
  assert.equal((await client.request("/api/results/slug/vendors", {})).status, 405);
  assert.equal(requests.length, 1, "hosted mutation is blocked before fetch");
  await assert.rejects(
    client.download(
      "/api/export/html",
      { result: {} as never, filters: {}, kind: "itineraries" },
      "file.html",
    ),
    /disabled/,
  );
});

test("hosted no-estimate mode blocks even informational POST", async () => {
  let requests = 0;
  const client = createClawfareClient(
    { hostedReadOnly: true, hostedEstimateOnly: false },
    async () => {
      requests++;
      return new Response("{}");
    },
  );
  assert.equal((await client.request("/api/estimate", {})).status, 405);
  assert.equal(requests, 0);
});

test("loopback session token is fetched once, then only attached to local requests", async () => {
  const paths: string[] = [];
  const fetcher: HttpClient = async (input, init) => {
    paths.push(String(input));
    if (String(input) === "/api/session") return Response.json({ token: "session-test" });
    const headers = init?.headers as Record<string, string>;
    assert.equal(headers["X-Clawfare-Session"], "session-test");
    return Response.json([{ id: "demo", title: "Saved investigation" }]);
  };
  const client = createClawfareClient(
    { hostedReadOnly: false, hostedEstimateOnly: false },
    fetcher,
  );
  const a = await client.searches();
  const b = await client.searches();
  assert.equal(a.ok, true);
  assert.equal(b.ok, true);
  assert.deepEqual(paths, ["/api/session", "/api/searches", "/api/searches"]);
});

test("rejects unexpected routes and malformed responses without inventing missing values", async () => {
  let calls = 0;
  const client = createClawfareClient(
    { hostedReadOnly: true, hostedEstimateOnly: true },
    async () => {
      calls++;
      return Response.json({ itineraries: [], id: "incomplete" });
    },
  );
  const bad = await client.sample();
  assert.equal(bad.ok, false);
  if (!bad.ok) assert.match(bad.error, /shape/);
  await assert.rejects(client.request("/api/results/..%2Fsecret" as never), /Unsupported/);
  assert.equal(calls, 1);
  assert.equal(
    isResultSet({ id: "x", title: "x", itineraries: [], addons: [], cells: [], vendors: [] }),
    true,
  );
  assert.equal(isResultSet({ id: "x", title: "x", itineraries: [], addons: [], cells: [] }), false);
  assert.equal(
    isEstimate({
      liveCalls: 0,
      cachedCalls: 0,
      skippedCalls: 0,
      returnLookupsUpperBound: 0,
      warnings: [],
      lines: [],
    }),
    true,
  );
});

test("request preserves HTTP errors and never turns missing prices into zero", async () => {
  const client = createClawfareClient(
    { hostedReadOnly: true, hostedEstimateOnly: true },
    async () => Response.json({ error: "Sign in with ChatGPT required." }, { status: 401 }),
  );
  const saved = await client.searches();
  assert.equal(saved.ok, false);
  if (!saved.ok) {
    assert.equal(saved.status, 401);
    assert.match(saved.error, /Sign in/);
  }
  assert.equal(
    isResultSet({
      id: "x",
      title: "x",
      itineraries: [{ id: "one", pricePerPerson: null }],
      addons: [],
      cells: [],
      vendors: [],
    }),
    true,
  );
});

test("guest mode uses only synthetic in-memory fixtures, without credentials or HTTP", async () => {
  let calls = 0;
  const client = createClawfareClient(
    { hostedReadOnly: false, hostedEstimateOnly: true, guest: true },
    async () => {
      calls++;
      throw new Error("Guest mode must not fetch");
    },
  );
  const sample = await client.sample();
  assert.equal(sample.ok, true);
  if (!sample.ok) throw new Error("Guest sample missing");
  assert.equal(sample.data.id, "guest-florida-demo");
  assert.equal(sample.data.itineraries.length, 3);
  assert.equal(sample.data.cells.length, 6);
  assert.ok(
    sample.data.itineraries.every(
      (it) => it.sourceLabel === "sample" && it.livePrice === false && it.links.length === 0,
    ),
  );
  const searches = await client.searches();
  assert.equal(searches.ok, true);
  if (!searches.ok) throw new Error("Guest investigation missing");
  assert.equal(searches.data[0]?.id, sample.data.id);
  const selected = await client.request("/api/results/guest-florida-demo");
  assert.equal(selected.status, 200);
  assert.equal((selected.body as { id: string }).id, sample.data.id);
  assert.equal((await client.status()).ok, true);
  assert.equal((await client.quota()).ok, true);
  assert.equal((await client.storage()).ok, false);
  assert.equal(
    (
      await client.estimate({
        ...sampleQuery,
        origins: [...sampleQuery.origins],
        destinations: [...sampleQuery.destinations],
        providers: [...sampleQuery.providers],
      })
    ).ok,
    false,
  );
  assert.equal((await client.request("/api/search", {})).status, 405);
  assert.equal((await client.request("/api/results/guest-florida-demo/vendors", {})).status, 405);
  assert.equal((await client.request("/api/results/guest-florida-demo/track", {})).status, 405);
  await assert.rejects(
    client.download(
      "/api/export/csv",
      { result: sample.data, filters: {}, kind: "itineraries" },
      "test.csv",
    ),
    /Guest exports are disabled/,
  );
  await assert.rejects(client.request("/api/results/..%2Fprivate" as never), /Unsupported/);
  assert.equal(calls, 0, "no session, API or provider requests may occur in guest mode");
});

test("hosted execution requires a fresh trusted capability; default OFF never POSTs", async () => {
  const calls: string[] = [];
  const client = createClawfareClient(
    { hostedReadOnly: true, hostedEstimateOnly: true },
    async (input) => {
      calls.push(String(input));
      return Response.json({
        cacheDir: "d1",
        searchesDir: "d1",
        providers: [],
        hostedSearch: { canExecuteOneWayGbpSerpApi: false },
      });
    },
  );
  const query = {
    ...sampleQuery,
    tripType: "one_way" as const,
    fetchReturns: false,
    origins: ["LHR"],
    destinations: ["NRT"],
    providers: ["serpapi"],
  };
  const prepared = await client.prepareHostedSearch(query);
  assert.equal(prepared.ok, false);
  assert.deepEqual(calls, ["/api/status"]);
  assert.equal((await client.request("/api/prepare-search", query)).status, 405);
  assert.equal((await client.request("/api/search", { confirmed: true })).status, 405);
  assert.deepEqual(calls, ["/api/status"]);
});

test("hosted explicit consent posts the unmodified server canonical query, without session or identity header", async () => {
  const calls: { path: string; init?: RequestInit }[] = [];
  const q = {
    ...sampleQuery,
    tripType: "one_way" as const,
    fetchReturns: false,
    outboundFrom: "2027-04-12",
    origins: ["LHR"],
    destinations: ["NRT"],
    providers: ["serpapi"],
  };
  const canonical = { ...q, outboundTo: "2027-04-12", market: "uk", maxLiveCalls: 1 };
  const quote = {
    canonicalQuery: canonical,
    consentToken: "server-signed-once-only-consent-value",
    expiresAt: "2027-04-01T12:00:00Z",
    totalCallsUpperBound: 1,
    baseCallsUpperBound: 1,
    returnLookupsUpperBound: 0,
    canExecuteHere: true,
    cost: { vendorBillingKnown: false, message: "May consume provider quota." },
  };
  const result = {
    id: "search-real",
    title: "LHR → NRT",
    itineraries: [
      {
        id: "serpapi-one",
        pricePerPerson: 1220,
        sourceLabel: "live",
        livePrice: true,
        outboundDate: "2027-04-12",
      },
    ],
    addons: [],
    cells: [],
    vendors: [],
    warnings: [],
  };
  const client = createClawfareClient(
    { hostedReadOnly: true, hostedEstimateOnly: true },
    async (input, init) => {
      const path = String(input);
      calls.push({ path, init });
      if (path === "/api/status")
        return Response.json({
          cacheDir: "d1",
          searchesDir: "d1",
          providers: [],
          hostedSearch: { canExecuteOneWayGbpSerpApi: true },
        });
      if (path === "/api/prepare-search") return Response.json(quote);
      if (path === "/api/search") {
        assert.deepEqual(JSON.parse(String(init?.body)), {
          canonicalQuery: canonical,
          consentToken: quote.consentToken,
        });
        return Response.json({
          ok: true,
          result,
          providerObservations: [
            {
              provider: "serpapi",
              state: "completed",
              baseCallsLoaded: 1,
            },
          ],
        });
      }
      throw new Error("Unexpected API request: " + path);
    },
  );
  const prepared = await client.prepareHostedSearch(q);
  assert.equal(prepared.ok, true);
  if (!prepared.ok) throw new Error("Expected server quote");
  assert.deepEqual(prepared.data, quote);
  const executed = await client.executeHostedSearch(prepared.data);
  assert.equal(executed.ok, true);
  if (!executed.ok) throw new Error("Expected completed search");
  assert.equal(executed.data.result.itineraries.length, 1);
  assert.equal(executed.data.result.id, "search-real");
  assert.deepEqual(
    calls.map((c) => c.path),
    ["/api/status", "/api/prepare-search", "/api/status", "/api/search"],
  );
  for (const call of calls) {
    assert.equal(String(call.init?.headers).includes("X-Clawfare-Session"), false);
    assert.equal(String(call.init?.headers).includes("oai-authenticated-user-email"), false);
  }
  assert.equal(calls[1]?.init?.method, "POST");
  assert.equal(calls[3]?.init?.method, "POST");
  assert.equal((await client.request("/api/search", { confirmed: true })).status, 405);
  assert.equal(calls.length, 4);
});

test("hosted quote is rejected if backend returns a malformed or non-executable approval", async () => {
  let posts = 0;
  const client = createClawfareClient(
    { hostedReadOnly: true, hostedEstimateOnly: true },
    async (input) => {
      if (String(input) === "/api/status")
        return Response.json({
          cacheDir: "d1",
          searchesDir: "d1",
          providers: [],
          hostedSearch: { canExecuteOneWayGbpSerpApi: true },
        });
      posts++;
      return Response.json({ canonicalQuery: {}, canExecuteHere: false });
    },
  );
  const quote = await client.prepareHostedSearch({
    ...sampleQuery,
    origins: ["LHR"],
    destinations: ["NRT"],
    providers: ["serpapi"],
  });
  assert.equal(quote.ok, false);
  assert.equal(posts, 1);
});

test("capability revoked between quote and execution prevents the second POST", async () => {
  let enabled = true;
  let requests = 0;
  const query = {
    ...sampleQuery,
    tripType: "one_way" as const,
    fetchReturns: false,
    origins: ["LHR"],
    destinations: ["NRT"],
    providers: ["serpapi"],
  };
  const mockQuote = {
    canonicalQuery: query,
    consentToken: "signed-revoked-consent-unique",
    expiresAt: "2027-01-01T00:00:00Z",
    canExecuteHere: true,
    totalCallsUpperBound: 1,
    baseCallsUpperBound: 1,
    returnLookupsUpperBound: 0,
    cost: { vendorBillingKnown: false, message: "unknown billing" },
  };
  const client = createClawfareClient(
    { hostedReadOnly: true, hostedEstimateOnly: true },
    async (input) => {
      const path = String(input);
      if (path === "/api/status")
        return Response.json({
          cacheDir: "d1",
          searchesDir: "d1",
          providers: [],
          hostedSearch: { canExecuteOneWayGbpSerpApi: enabled },
        });
      requests++;
      if (path === "/api/prepare-search") return Response.json(mockQuote);
      throw new Error("Execution was attempted after capability revocation");
    },
  );
  const prepared = await client.prepareHostedSearch(query);
  assert.equal(prepared.ok, true);
  if (!prepared.ok) throw new Error("Quote unavailable");
  enabled = false;
  const result = await client.executeHostedSearch(prepared.data);
  assert.equal(result.ok, false);
  assert.equal(requests, 1, "Only the preparation request may be sent");
});
