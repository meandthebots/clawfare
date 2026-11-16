"""SQLite parity tests for the exact SQL used by hosted D1 investigation adapter."""
import json
import pathlib
import sqlite3
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
MIGRATION = ROOT / "sites-runtime/migrations/0002_investigation_snapshots.sql"
SQL = json.loads(subprocess.check_output(
    [
        "node",
        "--input-type=module",
        "-e",
        "import * as m from './sites-runtime/src/investigations.mjs';"
        "process.stdout.write(JSON.stringify({upsert:m.UPSERT_INVESTIGATION_SQL,"
        "get:m.GET_INVESTIGATION_SQL,list:m.LIST_INVESTIGATIONS_SQL,"
        "observe:m.INSERT_PRICE_OBSERVATION_SQL,history:m.LIST_PRICE_OBSERVATIONS_SQL}));",
    ],
    cwd=ROOT,
    text=True,
))


class InvestigationSqlTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = pathlib.Path(self.tmp.name) / "investigations.sqlite"
        self.db = sqlite3.connect(self.path)
        self.addCleanup(self.db.close)
        self.db.execute("PRAGMA foreign_keys = ON")
        self.db.executescript(MIGRATION.read_text())
        self.owner = "account_a1234"
        self.other = "account_b5678"
        self.slug = "december-flight"
        for owner in (self.owner, self.other):
            self.save(owner, title="Example")

    def save(self, owner, title):
        result = json.dumps({"id": self.slug, "title": title, "itineraries": []})
        self.db.execute(
            SQL["upsert"],
            (owner, self.slug, result, title, "2026-11-16T12:30:00Z", "2026-11-16T12:30:00Z"),
        )
        self.db.commit()

    def test_same_slug_belongs_to_distinct_users(self):
        self.save(self.owner, "New title")
        one = self.db.execute(SQL["get"], (self.owner, self.slug)).fetchone()
        other = self.db.execute(SQL["get"], (self.other, self.slug)).fetchone()
        self.assertEqual("New title", json.loads(one[0])["title"])
        self.assertEqual("Example", json.loads(other[0])["title"])
        self.assertIsNone(self.db.execute(SQL["get"], ("account_c9999", self.slug)).fetchone())
        listings = self.db.execute(SQL["list"], (self.owner, 10)).fetchall()
        self.assertEqual([(self.slug, "New title", "2026-11-16T12:30:00Z", "2026-11-16T12:30:00Z")], listings)

    def insert_observation(self, owner, observation_id, label="cached", live=0, amount="123.45"):
        self.db.execute(
            SQL["observe"],
            (owner, self.slug, observation_id, "lh-1", "serpapi", label,
             live, "2026-11-16T12:30:00Z", "GBP", amount),
        )
        self.db.commit()

    def test_immutable_deduplicated_observations(self):
        oid = "a" * 64
        self.insert_observation(self.owner, oid)
        self.insert_observation(self.owner, oid)
        self.insert_observation(self.other, oid, label="mock")
        rows = self.db.execute(SQL["history"], (self.owner, self.slug, "lh-1", 10)).fetchall()
        self.assertEqual(1, len(rows))
        self.assertEqual("cached", rows[0][3])
        self.assertEqual("123.45", rows[0][7])
        other = self.db.execute(SQL["history"], (self.other, self.slug, "lh-1", 10)).fetchall()
        self.assertEqual("mock", other[0][3])
        self.assertIsNone(self.db.execute(
            SQL["history"], ("account_c9999", self.slug, "lh-1", 10)
        ).fetchone())

    def test_invalid_provenance_and_missing_parent_are_rejected(self):
        with self.assertRaises(sqlite3.IntegrityError):
            self.insert_observation(self.owner, "b" * 64, label="cached", live=1)
        with self.assertRaises(sqlite3.IntegrityError):
            self.insert_observation(self.owner, "c" * 64, label="paid", live=0)
        with self.assertRaises(sqlite3.IntegrityError):
            self.insert_observation("account_missing99", "d" * 64)
        self.assertEqual(0, self.db.execute(
            "SELECT COUNT(*) FROM fare_price_observations"
        ).fetchone()[0])

    def test_invalid_json_and_tenant_does_not_get_inserted(self):
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute(SQL["upsert"], (
                self.owner, "bad-json", "{", "Bad", None, "2026-11-16T12:30:00Z"
            ))
        self.assertIsNone(self.db.execute(SQL["get"], (self.owner, "bad-json")).fetchone())

    def test_deleting_investigation_cascades_only_in_the_same_tenant(self):
        oid = "e" * 64
        self.insert_observation(self.owner, oid)
        self.insert_observation(self.other, oid)
        self.db.execute("DELETE FROM investigation_snapshots WHERE owner_id=? AND slug=?", (self.owner, self.slug))
        self.db.commit()
        self.assertEqual(0, len(self.db.execute(SQL["history"], (self.owner, self.slug, "lh-1", 10)).fetchall()))
        self.assertEqual(1, len(self.db.execute(SQL["history"], (self.other, self.slug, "lh-1", 10)).fetchall()))


if __name__ == "__main__":
    unittest.main()
