import os
import sqlite3
import sys
import unittest

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from places_fts import create_fts_index


class PlacesFtsTests(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.execute("create table places(id integer primary key, s text, prio integer, ka text, kind text)")
        self.db.execute("create index places_kind_idx on places(kind)")
        self.db.executemany("insert into places(id,s,prio,ka,kind) values (?,?,?,?,?)", [
            (1, "tbilisi თბილისი tbilisi city", 0, "Tbilisi", "city"),
            (2, "kazbek mountain peak", 4, "Kazbek", "peak"),
            (3, "station train railway", 5, "Station", "hospital")
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

    def test_kind_index_supports_nearby_emergency_place_filter(self):
        rows = self.db.execute(
            "select id from places where kind in (?,?) order by id",
            ("hospital", "police")
        ).fetchall()
        self.assertEqual([(3,)], rows)
        indexes = {row[1] for row in self.db.execute("pragma index_list(places)")}
        self.assertIn("places_kind_idx", indexes)

    def test_index_contains_every_place(self):
        self.assertEqual(3, self.db.execute("select count(*) from places_fts").fetchone()[0])


if __name__ == "__main__":
    unittest.main()
