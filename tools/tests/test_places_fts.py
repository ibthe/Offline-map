import os
import sqlite3
import sys
import unittest

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from places_fts import create_fts_index


class PlacesFtsTests(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.execute("create table places(id integer primary key, s text, prio integer, ka text)")
        self.db.executemany("insert into places(id,s,prio,ka) values (?,?,?,?)", [
            (1, "tbilisi თბილისი tbilisi city", 0, "Tbilisi"),
            (2, "kazbek mountain peak", 4, "Kazbek"),
            (3, "station train railway", 5, "Station")
        ])
        create_fts_index(self.db)

    def tearDown(self):
        self.db.close()

    def test_prefix_query_returns_matching_place_ids(self):
        rows = self.db.execute(
            "select p.id from places_fts f join places p on p.id=f.docid where places_fts match ?",
            ("tbi*",)
        ).fetchall()
        self.assertEqual([(1,)], rows)

    def test_multiple_prefix_terms_are_intersected(self):
        rows = self.db.execute(
            "select p.id from places_fts f join places p on p.id=f.docid where places_fts match ?",
            ("tbi* AND city*",)
        ).fetchall()
        self.assertEqual([(1,)], rows)

    def test_index_contains_every_place(self):
        self.assertEqual(3, self.db.execute("select count(*) from places_fts").fetchone()[0])


if __name__ == "__main__":
    unittest.main()
