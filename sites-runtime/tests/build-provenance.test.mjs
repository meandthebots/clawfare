import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import { readFileSync } from "node:fs";
import test from "node:test";
import { handleReadOnlyApi } from "../src/read-only-api.mjs";

test("authenticated build diagnostics identify missing D1 migration tables without writes", async t => {
  const conn = new DatabaseSync(":memory:");
  t.after(() => conn.close());
  const secret = "test-only-build-provenance-owner-secret";
  const buildProvenance = {
    sourceGitSha: "a".repeat(40), sourceDirty: false, kotlinBundleSha256: "a".repeat(64),
    openApiSha256: "b".repeat(64),
    migrations: [{
      file: "0003_public_sample_fixtures.sql", sha256: "c".repeat(64),
      requiredTables: ["public_sample_fixtures"],
    }],
  };
  const db = { prepare(sql) {
    assert.match(sql, /^SELECT/);
    const stmt = conn.prepare(sql);
    return { bind: (...args) => ({
      first: async () => stmt.get(...args),
      all: async () => ({ results: stmt.all(...args) }),
    }) };
  } };
  const invoke = email => handleReadOnlyApi(new Request("https://clawfare.test/api/storage"), {
    db, getVerifiedEmail: async () => email, ownerKeySecret: secret, buildProvenance,
  });
  assert.equal((await invoke(null)).status, 401);
  const missing = await (await invoke("owner@example.com")).json();
  assert.deepEqual(missing.build.missingSchema, [
    { file: "0003_public_sample_fixtures.sql", tables: ["public_sample_fixtures"] },
  ]);
  assert.equal(missing.build.schema, "missing_tables");
  const migration = new URL("../migrations/0003_public_sample_fixtures.sql", import.meta.url);
  conn.exec(readFileSync(migration, "utf8"));
  const present = await (await invoke("owner@example.com")).json();
  assert.equal(present.sampleStored, false);
  assert.deepEqual(present.build, { ...buildProvenance, schema: "tables_present", missingSchema: [] });
});
