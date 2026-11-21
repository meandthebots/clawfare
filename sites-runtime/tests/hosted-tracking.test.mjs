import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";
import test from "node:test";
import { D1Investigations } from "../src/investigations.mjs";
import { handleReadOnlyApi, ownerKeyFromVerifiedEmail } from "../src/read-only-api.mjs";

const secret = "test-only-hosted-tracking-secret-32bytes";
const origin = "https://clawfare.test";
const fareId = "serpapi:BA-7";
const at = new Date().toISOString();
const earlier = new Date(Date.parse(at) - 60_000).toISOString();
const path = "/api/results/search-a/track";

function database(t) {
  const conn = new DatabaseSync(":memory:");
  t.after(() => conn.close());
  conn.exec("PRAGMA foreign_keys = ON");
  conn.exec(readFileSync(new URL("../migrations/0002_investigation_snapshots.sql", import.meta.url), "utf8"));
  return {
    prepare(sql) {
      const stmt = conn.prepare(sql);
      return { bind: (...args) => ({
        first: async () => stmt.get(...args),
        all: async () => ({ results: stmt.all(...args) }),
        run: () => stmt.run(...args),
      }) };
    },
    async batch(statements) {
      conn.exec("BEGIN");
      try {
        const results = statements.map(statement => statement.run());
        conn.exec("COMMIT");
        return results;
      } catch (error) {
        conn.exec("ROLLBACK");
        throw error;
      }
    },
  };
}

async function seed(db, email, id, { capturedAt = at, amountDecimal = "850.25", market = "uk", sourceLabel = "live" } = {}) {
  const owner = await ownerKeyFromVerifiedEmail(email, secret);
  const store = new D1Investigations(db);
  await store.saveResultWithPrices(owner, {
    id, title: "London to Tokyo", fetchedAt: capturedAt, passengers: 2, checkedBags: 1,
    currency: "GBP", fx: { asOf: capturedAt.slice(0, 10), perGbp: { GBP: 1 } },
    query: { market, tripType: "one_way" },
    itineraries: [{
      id: fareId, origin: "LHR", destination: "NRT", outboundDate: "2027-04-12",
      cabinKey: "business", passengers: 2, outbound: { legs: [{ flightNumber: "BA 7" }] },
      source: "serpapi", sourceLabel, livePrice: sourceLabel === "live", fetchedAt: capturedAt,
      priceCurrency: "GBP", pricePerPerson: Number(amountDecimal),
    }, { id: "unselected-fare" }],
  }, [{
    itineraryId: fareId, provider: "serpapi", sourceLabel, livePrice: sourceLabel === "live",
    capturedAt, originalCurrency: "GBP", amountDecimal,
  }]);
}

function api(db, email, path, options) {
  return handleReadOnlyApi(new Request(origin + path, options), {
    db, ownerKeySecret: secret, getVerifiedEmail: async () => email,
  });
}

function post(body = { itineraryId: fareId, tripSlug: "tokyo-move" }, sourceOrigin = origin) {
  return { method: "POST", body: JSON.stringify(body), headers: {
    origin: sourceOrigin, "content-type": "application/json",
    "oai-authenticated-user-email": "a@example.com",
  } };
}

test("tracking atomically keeps the first snapshot and adds distinct same-context observations", async t => {
  const db = database(t);
  const email = "a@example.com";
  await seed(db, email, "search-a", { capturedAt: earlier });
  assert.equal((await (await api(db, email, "/api/status")).json()).hostedTracking, true);
  const response = await api(db, email, path, post());
  assert.equal(response.status, 200);
  const { slug } = await response.json();
  assert.match(slug, /^tracked-[a-f0-9]{40}$/);
  await seed(db, email, "search-b", { amountDecimal: "900.50" });
  const repeats = await Promise.all([
    api(db, email, path, post()),
    api(db, email, "/api/results/search-b/track", post()),
    api(db, email, "/api/results/search-b/track", post()),
  ]);
  for (const repeat of repeats) assert.deepEqual(await repeat.json(), { slug });

  const details = await (await api(db, email, "/api/results/" + slug)).json();
  assert.match(details.title, /tokyo-move/);
  assert.equal(details.itineraries.length, 1);
  assert.equal(details.itineraries[0].pricePerPerson, 850.25);
  assert.equal(details.fetchedAt, earlier);
  const historyPath = "/api/history/" + slug + "/" + encodeURIComponent(fareId);
  const history = await (await api(db, email, historyPath)).json();
  assert.deepEqual(history.map(observation => [observation.captured_at, observation.amount_decimal]),
    [[at, "900.50"], [earlier, "850.25"]]);
  assert.deepEqual(await (await api(db, "b@example.com", historyPath)).json(), []);
  assert.equal((await api(db, "b@example.com", "/api/results/" + slug)).status, 404);
  assert.equal((await api(db, email, historyPath + "?limit=1")).status, 200);
  assert.equal((await (await api(db, email, historyPath + "?limit=1")).json()).length, 1);
  await seed(db, email, "search-us", { market: "us", amountDecimal: "910" });
  const us = await (await api(db, email, "/api/results/search-us/track", post())).json();
  assert.notEqual(us.slug, slug);
});

test("tracking rejects untrusted access, malformed data, and untrackable fares", async t => {
  const db = database(t);
  await seed(db, "a@example.com", "search-a", { capturedAt: new Date(Date.now() - 9_000_000).toISOString() });
  await seed(db, "a@example.com", "search-mock", { sourceLabel: "mock" });
  for (const [email, route, init, status] of [
    [null, path, post(), 401],
    ["b@example.com", path, post(), 404],
    ["a@example.com", path, post(undefined, "https://other.test"), 403],
    ["a@example.com", path, post({ itineraryId: fareId, tripSlug: "../bad" }), 400],
    ["a@example.com", path, post({ itineraryId: fareId, tripSlug: "trip", price: 1 }), 400],
    ["a@example.com", path, post({ itineraryId: "foreign-itinerary", tripSlug: "trip" }), 404],
    ["a@example.com", path, post(), 409],
    ["a@example.com", "/api/results/search-mock/track", post(), 409],
    ["a@example.com", "/api/results/florida-dec-2026/track", post(), 404],
  ]) {
    assert.equal((await api(db, email, route, init)).status, status, route);
  }
  const listed = await (await api(db, "a@example.com", "/api/searches")).json();
  assert.equal(listed.some(item => item.id.startsWith("tracked-")), false);
});
