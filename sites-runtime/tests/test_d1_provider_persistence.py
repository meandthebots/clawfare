"""Run the production D1 cache and attempt statements against real SQLite."""
import json
import pathlib
import sqlite3
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
MIGRATION = ROOT / "sites-runtime/migrations/0005_hosted_provider_cache.sql"
SQL = json.loads(subprocess.check_output(
    [
        "node", "--input-type=module", "-e",
        "import * as m from './sites-runtime/src/provider-persistence.mjs';"
        "process.stdout.write(JSON.stringify({"
        "begin:m.INSERT_ATTEMPT_SQL, finish:m.FINISH_ATTEMPT_SQL,"
        "attempts:m.LIST_ATTEMPTS_SQL, write:m.WRITE_PROVIDER_CACHE_SQL,"
        "read:m.READ_PROVIDER_CACHE_SQL}));",
    ], cwd=ROOT, text=True,
))
OWNER_A = "acct_" + "a" * 64
OWNER_B = "acct_" + "b" * 64
NONCE = "e" * 32
QUERY = "c" * 64
ROUTE = "d" * 64
DAY = "2026-11-18T19:00:00.000Z"
LATER = "2026-11-18T19:01:00.000Z"
EXPIRY = "2026-11-18T19:05:00.000Z"
OFFERS = '[{"price":1500.75,"legs":[]}]'


class ProviderPersistenceSqlTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.file = pathlib.Path(self.tmp.name) / "hosted.sqlite"
        self.a = sqlite3.connect(self.file, timeout=5, isolation_level=None)
        self.b = sqlite3.connect(self.file, timeout=5, isolation_level=None)
        self.addCleanup(self.a.close)
        self.addCleanup(self.b.close)
        self.a.executescript(MIGRATION.read_text())

    def begin(self, db, owner=OWNER_A):
        return db.execute(SQL["begin"], (
            owner, NONCE, QUERY, "serpapi", ROUTE, "tokyo-2027", DAY
        )).fetchone()

    def finish(self, db, state="complete", owner=OWNER_A, returned=2, eligible=1,
               attempted=1, loaded=1, error=None):
        return db.execute(SQL["finish"], (
            state, attempted, loaded, 0, returned, eligible, error, LATER,
            owner, NONCE, "serpapi", ROUTE
        )).fetchone()

    def write(self, db, owner=OWNER_A, observed=DAY, expires=EXPIRY,
              outcome="offers", offers=OFFERS):
        return db.execute(SQL["write"], (
            owner, "serpapi", QUERY, 1, "uk", "GBP", outcome,
            offers, observed, expires
        ))

    def read(self, db, owner=OWNER_A, at=DAY):
        return db.execute(SQL["read"], (
            owner, "serpapi", QUERY, 1, at
        )).fetchone()

    def test_two_users_are_isolated_and_expiry_is_strict(self):
        self.write(self.a)
        self.write(self.b, OWNER_B, offers="[]", outcome="verified_empty")
        self.assertEqual("offers", self.read(self.a)[0])
        self.assertEqual("verified_empty", self.read(self.b, OWNER_B)[0])
        self.assertIsNone(self.read(self.a, "acct_" + "c" * 64))
        self.assertIsNone(self.read(self.a, at=EXPIRY))
        self.assertIsNone(self.read(self.a, at="2026-11-18T19:05:01.000Z"))

    def test_cache_upsert_is_monotonic_across_workers(self):
        self.write(self.a)
        self.write(self.b, observed="2026-11-18T18:00:00.000Z",
                   expires="2026-11-18T18:05:00.000Z",
                   outcome="verified_empty", offers="[]")
        self.assertEqual("offers", self.read(self.a)[0])
        self.write(self.b, observed=LATER, expires=EXPIRY,
                   outcome="verified_empty", offers="[]")
        self.assertEqual("verified_empty", self.read(self.a)[0])
        self.assertEqual(1, self.a.execute(
            "SELECT count(*) FROM hosted_provider_offer_cache"
        ).fetchone()[0])

    def test_attempt_is_insert_before_dispatch_and_terminal_once(self):
        self.assertEqual((ROUTE,), self.begin(self.a))
        self.assertIsNone(self.begin(self.b))
        self.assertIsNone(self.finish(self.b, owner=OWNER_B))
        self.assertEqual((ROUTE,), self.finish(self.b))
        self.assertIsNone(self.finish(self.a, "failed", error="timeout"))
        rows = self.a.execute(SQL["attempts"], (OWNER_A, NONCE, 50)).fetchall()
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0][2], "complete")
        self.assertEqual(rows[0][6], 2)
        self.assertEqual(rows[0][7], 1)
        self.assertEqual(self.a.execute(
            SQL["attempts"], (OWNER_B, NONCE, 50)
        ).fetchall(), [])

    def test_partial_worker_crash_not_recorded_as_no_flights(self):
        self.begin(self.a)
        pending = self.b.execute(SQL["attempts"], (OWNER_A, NONCE, 50)).fetchone()
        self.assertEqual("attempted", pending[2])
        self.assertIsNone(pending[-1])
        self.assertEqual((ROUTE,), self.finish(
            self.a, state="partial", returned=1, eligible=0,
            attempted=2, loaded=1, error="timeout"
        ))
        self.assertEqual("partial", self.b.execute(
            SQL["attempts"], (OWNER_A, NONCE, 50)
        ).fetchone()[2])

    def test_database_rejects_illegal_evidence_combinations(self):
        self.begin(self.a)
        for state, returned, eligible, attempted, loaded, error in [
            ("empty", 1, 0, 1, 1, None),
            ("failed", 0, 0, 1, 0, None),
            ("complete", 1, 0, 0, 1, None),
            ("complete", 1, 2, 1, 1, None),
            ("complete", 1, 0, 1, 1, "network"),
        ]:
            with self.assertRaises(sqlite3.IntegrityError):
                self.finish(self.a, state, returned=returned, eligible=eligible,
                            attempted=attempted, loaded=loaded, error=error)
        self.assertEqual("attempted", self.a.execute(
            SQL["attempts"], (OWNER_A, NONCE, 50)
        ).fetchone()[2])
        with self.assertRaises(sqlite3.IntegrityError):
            self.write(self.a, offers="{bad}")
        with self.assertRaises(sqlite3.IntegrityError):
            self.write(self.a, expires=DAY)
        self.assertEqual(0, self.a.execute(
            "SELECT count(*) FROM hosted_provider_offer_cache"
        ).fetchone()[0])


if __name__ == "__main__":
    unittest.main()
