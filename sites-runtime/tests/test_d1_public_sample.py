"""Check D1 public Kotlin sample storage schema and exact adapter SQL on SQLite."""
import json
import pathlib
import sqlite3
import subprocess
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
MIGRATION = ROOT / "sites-runtime/migrations/0003_public_sample_fixtures.sql"
QUERIES = json.loads(subprocess.check_output(
    ["node", "--input-type=module", "-e",
     "import * as p from './sites-runtime/src/public-sample.mjs'; "
     "process.stdout.write(JSON.stringify({upsert:p.UPSERT_PUBLIC_SAMPLE_SQL, "
     "get:p.GET_PUBLIC_SAMPLE_SQL, insertMissing:p.INSERT_PUBLIC_SAMPLE_IF_MISSING_SQL}));"],
    cwd=ROOT, text=True,
))


class PublicSampleSqlTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.addCleanup(self.db.close)
        self.db.executescript(MIGRATION.read_text())

    def test_sql_roundtrip_is_idempotent_and_refuses_other_slugs(self):
        original = '{"id":"florida-dec-2026","title":"Fixture","itineraries":[]}'
        digest = "a" * 64
        fields = ("florida-dec-2026", original, digest, "2026-11-16T12:00:00.000Z")
        self.db.execute(QUERIES["upsert"], fields)
        self.db.execute(QUERIES["upsert"], fields)
        self.assertEqual((original, digest), self.db.execute(
            QUERIES["get"], ("florida-dec-2026",)
        ).fetchone())
        self.assertEqual(1, self.db.execute(
            "SELECT COUNT(*) FROM public_sample_fixtures"
        ).fetchone()[0])
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute(QUERIES["upsert"],
                            ("private-search", original, digest, "2026-11-16T12:00:00Z"))

    def test_first_request_insert_is_idempotent_and_never_overwrites(self):
        first = '{"id":"florida-dec-2026","title":"First","itineraries":[]}'
        second = '{"id":"florida-dec-2026","title":"Other","itineraries":[]}'
        sql = QUERIES["insertMissing"]
        self.db.execute(sql, ("florida-dec-2026", first, "a" * 64, "2026-11-16T12:00:00Z"))
        self.db.execute(sql, ("florida-dec-2026", second, "b" * 64, "2026-11-17T12:00:00Z"))
        self.assertEqual(
            (first, "a" * 64),
            self.db.execute(QUERIES["get"], ("florida-dec-2026",)).fetchone(),
        )
        self.assertEqual(1, self.db.execute(
            "SELECT COUNT(*) FROM public_sample_fixtures"
        ).fetchone()[0])

    def test_invalid_json_or_digest_cannot_be_stored(self):
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute(QUERIES["upsert"],
                            ("florida-dec-2026", "{", "b" * 64, "2026-11-16T12:00:00Z"))
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute(QUERIES["upsert"],
                            ("florida-dec-2026", "{}", "not-a-digest", "2026-11-16T12:00:00Z"))
        self.assertIsNone(self.db.execute(
            QUERIES["get"], ("florida-dec-2026",)
        ).fetchone())


if __name__ == "__main__":
    unittest.main()
