#!/usr/bin/env python3
"""Validate cached generated assets before deciding whether the build may reuse them."""
import os
import sqlite3
import struct
import sys


def check_sqlite(path, table, minimum):
    if not os.path.isfile(path) or os.path.getsize(path) == 0:
        return False
    try:
        db = sqlite3.connect("file:%s?mode=ro" % path, uri=True)
        try:
            if db.execute("pragma quick_check").fetchone()[0] != "ok":
                return False
            names = {r[0] for r in db.execute("select name from sqlite_master where type='table'")}
            if table not in names:
                return False
            count = db.execute("select count(*) from " + table).fetchone()[0]
            return count >= minimum
        finally:
            db.close()
    except Exception as exc:
        print("invalid SQLite asset:", path, repr(exc))
        return False


def check_places(path):
    if not check_sqlite(path, "places", 100):
        return False
    try:
        db = sqlite3.connect("file:%s?mode=ro" % path, uri=True)
        try:
            cols = {r[1] for r in db.execute("pragma table_info(places)")}
            names = {r[0] for r in db.execute("select name from sqlite_master where type='table'")}
            if not {"ka", "ru", "en", "s", "kind", "lat", "lon"}.issubset(cols) or "places_fts" not in names:
                return False
            place_count = db.execute("select count(*) from places").fetchone()[0]
            fts_count = db.execute("select count(*) from places_fts").fetchone()[0]
            return fts_count == place_count
        finally:
            db.close()
    except Exception:
        return False


def check_osm(path):
    if not os.path.isfile(path) or os.path.getsize(path) <= (10 << 20):
        return False
    try:
        with open(path, "rb") as f:
            return b"OSMHeader" in f.read(200)
    except Exception:
        return False


def check_routing(path):
    if not os.path.isfile(path):
        return False
    try:
        size = os.path.getsize(path)
        with open(path, "rb") as f:
            header = f.read(48)
        if len(header) != 48 or header[:4] != b"GRT1":
            return False
        n_nodes, n_edges, n_points, n_names, name_len, lat0, lon0, cell, rows, cols, n_grid = struct.unpack("<11i", header[4:])
        if min(n_nodes, n_edges, n_points, n_names, cell, rows, cols, n_grid) <= 0 or name_len < 0:
            return False
        expected = (48 + 8*n_nodes + 20*n_edges + 4*(n_edges+1) + 8*n_points
                    + 4*(rows*cols+1) + 4*n_grid + 4*(n_names+1) + name_len)
        return size == expected
    except Exception as exc:
        print("invalid routing asset:", repr(exc))
        return False


def main():
    if len(sys.argv) != 3:
        sys.exit("usage: check_cached_assets.py places|relief|contours|routing PATH")
    kind, path = sys.argv[1:]
    if kind == "osm":
        ok = check_osm(path)
    elif kind == "places":
        ok = check_places(path)
    elif kind == "relief":
        ok = check_sqlite(path, "tiles", 1)
    elif kind == "contours":
        ok = check_sqlite(path, "tiles", 1)
    elif kind == "routing":
        ok = check_routing(path)
    else:
        sys.exit("unknown asset type: " + kind)
    print(("OK " if ok else "INVALID ") + kind + ": " + path)
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
