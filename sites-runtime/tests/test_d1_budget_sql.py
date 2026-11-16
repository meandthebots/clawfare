"""Run production D1 reservation SQL against SQLite under cap/concurrency tests.

Requires only Python 3 and Node; no provider access, credentials, cloud DB or npm.
"""
import concurrent.futures
import pathlib
import sqlite3
import subprocess
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
MIGRATION = ROOT / "sites-runtime/migrations/0001_provider_http_budget.sql"
RESERVE_SQL = subprocess.check_output(
    [
        "node",
        "--input-type=module",
        "-e",
        "import { RESERVE_SQL } from './sites-runtime/src/provider-http-budget.mjs'; process.stdout.write(RESERVE_SQL);",
    ],
    cwd=ROOT,
    text=True,
)


class D1ProviderHttpBudgetSqlTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.db_path = pathlib.Path(self.tmp.name) / "budget.sqlite"
        with self.db() as connection:
            connection.executescript(MIGRATION.read_text(encoding="utf-8"))
            connection.executemany(
                "INSERT INTO provider_http_limits (provider, daily_cap, monthly_cap) VALUES (?, ?, ?)",
                [
                    ("serpapi", 1, 2),
                    ("searchapi", 3, 3),
                    ("duffel", 0, None),
                ],
            )

    def db(self):
        connection = sqlite3.connect(self.db_path, timeout=30)
        connection.execute("PRAGMA foreign_keys = ON")
        connection.execute("PRAGMA busy_timeout = 10000")
        return connection

    def reserve(self, rid, provider, stamp, category="base_search"):
        day = stamp[:10]
        month = stamp[:7]
        with self.db() as connection:
            result = connection.execute(
                RESERVE_SQL, (rid, category, stamp, day, month, provider, day, month)
            ).fetchone()
            return result[0] if result is not None else None

    def count(self, provider):
        with self.db() as connection:
            return connection.execute(
                "SELECT COUNT(*) FROM provider_http_reservations WHERE provider = ?",
                (provider,),
            ).fetchone()[0]

    def test_missing_disabled_and_reused_reservation_fail_closed(self):
        stamp = "2026-11-16T12:00:00.000Z"
        self.assertIsNone(self.reserve("unconfigured", "travelpayouts", stamp))
        self.assertIsNone(self.reserve("disabled", "duffel", stamp))
        self.assertEqual("accepted", self.reserve("accepted", "serpapi", stamp))
        self.assertIsNone(self.reserve("accepted", "serpapi", stamp))
        self.assertIsNone(self.reserve("next", "serpapi", stamp))
        self.assertEqual(1, self.count("serpapi"))

    def test_monthly_limit_survives_day_rollover_and_completion(self):
        self.assertEqual("day1", self.reserve("day1", "serpapi", "2026-11-16T23:59:00Z"))
        with self.db() as connection:
            completed = connection.execute(
                """UPDATE provider_http_reservations SET
                    phase = 'completed', http_status = 200, outcome = 'response'
                    WHERE id = 'day1' AND phase = 'reserved' RETURNING id"""
            ).fetchone()
            self.assertEqual(("day1",), completed)
        self.assertIsNone(self.reserve("too-early", "serpapi", "2026-11-16T23:59:59Z"))
        self.assertEqual("day2", self.reserve("day2", "serpapi", "2026-11-17T00:01:00Z"))
        self.assertIsNone(self.reserve("month-exceeded", "serpapi", "2026-11-18T00:01:00Z"))
        self.assertEqual("next-month", self.reserve("next-month", "serpapi", "2026-12-01T00:01:00Z"))
        self.assertEqual(3, self.count("serpapi"))

    def test_concurrent_writers_never_exceed_one_provider_cap(self):
        stamp = "2026-11-16T12:00:00Z"
        with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
            results = list(pool.map(
                lambda i: self.reserve(f"search-{i}", "searchapi", stamp),
                range(18),
            ))
        self.assertEqual(3, sum(result is not None for result in results))
        self.assertEqual(3, self.count("searchapi"))

    def test_invalid_category_and_fake_provider_cannot_create_ledger_row(self):
        with self.assertRaises(sqlite3.IntegrityError):
            self.reserve("bad-category", "serpapi", "2026-11-16T12:00:00Z", "order")
        self.assertIsNone(self.reserve("fake-provider", "unknown", "2026-11-16T12:00:00Z"))
        self.assertEqual(0, self.count("serpapi"))


if __name__ == "__main__":
    unittest.main()
