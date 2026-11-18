"""Exercise production D1 consent SQL against real SQLite, including atomic replay denial."""
import pathlib
import re
import sqlite3
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
JS = (ROOT / "src/search-consent.mjs").read_text()


def extract(name):
    match = re.search(r"export const " + re.escape(name) +
                      r" = \[(.*?)\]\.join\(\"\\n\"\);", JS, re.S)
    if not match:
        raise AssertionError("Production SQL statement not found: " + name)
    return "\n".join(re.findall(r'\"((?:[^\"\\]|\\.)*)\"', match.group(1)))


INSERT = extract("INSERT_CONSENT_SQL")
CONSUME = extract("CONSUME_CONSENT_SQL")
DAY = "2026-11-18T10:00:00.000Z"
LATER = "2026-11-18T10:02:00.000Z"
EXPIRED = "2026-11-18T10:05:01.000Z"
OWNER = "acct_" + "a" * 64
DIGEST = "b" * 64
NONCE = "c" * 32
LIMITS = '[{"provider":"serpapi","category":"base_search","count":1}]'


class HostedSearchConsentSqlTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = pathlib.Path(self.tmp.name) / "d1.db"
        self.a = sqlite3.connect(self.path, timeout=5, isolation_level=None)
        self.b = sqlite3.connect(self.path, timeout=5, isolation_level=None)
        self.a.executescript((ROOT / "migrations/0004_search_consents.sql").read_text())

    def tearDown(self):
        self.b.close()
        self.a.close()
        self.tmp.cleanup()

    def issue(self):
        return self.a.execute(INSERT, (NONCE, OWNER, DIGEST, LIMITS, DAY,
                                       "2026-11-18T10:05:00.000Z"))

    def consume(self, connection, owner=OWNER, query=DIGEST, time=LATER):
        return connection.execute(CONSUME, (
            time, NONCE, owner, query, LIMITS, time
        )).fetchone()

    def test_atomic_one_shot_token_consumption_across_connections(self):
        self.issue()
        self.assertEqual(self.consume(self.a), (NONCE,))
        self.assertIsNone(self.consume(self.b))
        self.assertEqual(
            self.a.execute("SELECT state, consumed_at FROM hosted_search_consents").fetchone(),
            ("consumed", LATER)
        )

    def test_identity_query_expiry_and_duplicate_nonce_fail_closed(self):
        self.issue()
        with self.assertRaises(sqlite3.IntegrityError):
            self.issue()
        self.assertIsNone(self.consume(self.a, owner="acct_" + "d" * 64))
        self.assertIsNone(self.consume(self.a, query="e" * 64))
        self.assertIsNone(self.consume(self.a, time=EXPIRED))
        self.assertEqual(self.consume(self.b), (NONCE,))

    def test_schema_rejects_malformed_owner_nonce_query_and_json(self):
        for nonce, owner, digest, limits in [
            ("not-a-nonce", OWNER, DIGEST, LIMITS),
            (NONCE, "raw@example.com", DIGEST, LIMITS),
            (NONCE, OWNER, "bad-digest", LIMITS),
            (NONCE, OWNER, DIGEST, "{"),
        ]:
            with self.assertRaises(sqlite3.IntegrityError):
                self.a.execute(INSERT, (nonce, owner, digest, limits, DAY, EXPIRED))
        self.assertEqual(
            self.a.execute("SELECT COUNT(*) FROM hosted_search_consents").fetchone()[0], 0
        )


if __name__ == "__main__":
    unittest.main()
