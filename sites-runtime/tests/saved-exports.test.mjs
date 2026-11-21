import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";
import test from "node:test";
import { D1Investigations } from "../src/investigations.mjs";
import { handleReadOnlyApi, ownerKeyFromVerifiedEmail } from "../src/read-only-api.mjs";

const origin = "https://clawfare.test";
const ownerEmail = "owner@example.com";
const outsiderEmail = "other@example.com";
const secret = "test-only-saved-export-owner-key-secret";
const slug = "tokyo";
const fetchedAt = "2026-11-20T09:00:00Z";
const initial = { kind: "itineraries", itineraryIds: ["selected"] };

async function fixture(t) {
  const conn = new DatabaseSync(":memory:");
  t.after(() => conn.close());
  for (const filename of ["0002_investigation_snapshots.sql", "0006_hosted_export_limits.sql"]) {
    conn.exec(readFileSync(new URL(`../migrations/${filename}`, import.meta.url), "utf8"));
  }
  const db = {
    prepare(sql) {
      const stmt = conn.prepare(sql);
      return { bind: (...args) => ({
        first: async () => stmt.get(...args),
        run: async () => stmt.run(...args),
      }) };
    },
  };
  const store = new D1Investigations(db);
  const ownerId = await ownerKeyFromVerifiedEmail(ownerEmail, secret);
  await store.saveSnapshot(ownerId, {
    id: slug, title: '=HYPERLINK("https://bad.test") <script>alert(1)</script>',
    fetchedAt, passengers: 2, checkedBags: 1,
    query: { origins: ["LHR"], destinations: ["NRT"], tripType: "one_way", market: "uk" },
    warnings: ["private diagnostic marker"], fx: { asOf: "2026-11-20", perGbp: { USD: 1.25 } },
    itineraries: [{
      id: "selected", origin: "LHR", destination: "NRT", passengers: 2,
      outboundDate: "2027-04-12", priceCurrency: "USD", priceOriginal: 1250, pricePerPerson: 1000,
      source: "serpapi", sourceLabel: "cached", fetchedAt,
      links: [{ url: "https://evil.test/?private-value=secret", label: "Not a vetted link" }],
      notes: "private diagnostic marker", cashRefundProofVerified: true,
    }, { id: "excluded", origin: "LCY", destination: "HND" }],
    sellerOptions: [{
      itineraryId: "selected", provider: "serpapi", seller: " Airline", fareBrand: "+=danger",
      currency: "USD", passengers: 2, priceTotal: 2500, market: "uk", observedAt: fetchedAt,
    }],
  });
  return { db, conn };
}

function request(db, { email = ownerEmail, format = "csv", body = initial, ref = slug, source = origin } = {}) {
  return handleReadOnlyApi(new Request(`${origin}/api/results/${ref}/export.${format}`, {
    method: "POST",
    headers: { origin: source, "content-type": "application/json", "oai-authenticated-user-email": ownerEmail },
    body: JSON.stringify(body),
  }), { db, ownerKeySecret: secret, getVerifiedEmail: async () => email });
}

test("exports selected stored fares with original units, FX context and safe attachment headers", async t => {
  const { db } = await fixture(t);
  const res = await request(db);
  assert.equal(res.status, 200);
  assert.equal(res.headers.get("content-disposition"), 'attachment; filename="clawfare-tokyo.csv"');
  assert.equal(res.headers.get("cache-control"), "private, no-store");
  const csv = await res.text();
  assert.match(csv, /^export_version,row_kind,search_title,/);
  assert.match(csv, /"'=HYPERLINK\(""https:\/\/bad.test""\)/);
  assert.match(csv, /"USD","1250","2500","1000","2000","0.8"/);
  assert.match(csv, /"cached"/);
  assert.doesNotMatch(csv, /LCY|HND|private diagnostic|evil\.test|owner@example|acct_/);
  const seller = await request(db, { body: { ...initial, kind: "seller_options" } });
  assert.match(await seller.text(), /"'\+=danger"," Airline","USD","","2500"/);
  const html = await request(db, { format: "html" });
  assert.equal(html.status, 200);
  assert.match(html.headers.get("content-security-policy"), /default-src 'none'/);
  const report = await html.text();
  assert.match(report, /&lt;script&gt;alert\(1\)&lt;\/script&gt;/);
  assert.match(report, /href="https:\/\/serpapi\.com\/google-flights-api"/);
  assert.doesNotMatch(report, /<script|evil\.test|private diagnostic/);
  const empty = await request(db, { format: "html", body: { ...initial, itineraryIds: [] } });
  assert.match(await empty.text(), /0 selected itineraries/);
});

test("foreign owners, forged snapshots and invalid selections cannot export", async t => {
  const { db } = await fixture(t);
  for (const [options, status] of [
    [{ email: null }, 401],
    [{ email: outsiderEmail }, 404],
    [{ source: "https://other.test" }, 403],
    [{ body: { ...initial, result: { cashRefundProofVerified: true } } }, 400],
    [{ body: { ...initial, itineraryIds: ["foreign-item"] } }, 400],
    [{ body: { ...initial, itineraryIds: ["selected", "selected"] } }, 400],
    [{ ref: "..%2ftokyo" }, 405],
  ]) assert.equal((await request(db, options)).status, status);
});

test("D1 enforces the per-owner export limit across handler instances", async t => {
  const { db, conn } = await fixture(t);
  const statuses = await Promise.all(Array.from({ length: 12 }, async () => (await request(db)).status));
  assert.equal(statuses.filter(status => status === 200).length, 10);
  assert.equal(statuses.filter(status => status === 429).length, 2);
  assert.equal((await request(db, { email: outsiderEmail })).status, 404);
  conn.exec("UPDATE hosted_export_limits SET window_start = window_start - 1");
  assert.equal((await request(db)).status, 200);
});
