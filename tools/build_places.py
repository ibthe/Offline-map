#!/usr/bin/env python3
"""Builds the offline search index (places.db) from the points inside the vector tiles.
Usage: build_places.py <georgia.mbtiles> <places.db>"""
import sqlite3
import sys

import mvt

src, dst = sys.argv[1], sys.argv[2]
db = sqlite3.connect(src)
meta = dict(db.execute("select name, value from metadata"))
Z = int(meta.get("maxzoom") or 14)
print("reading zoom", Z)

WANT = {"place", "poi", "mountain_peak", "aerodrome_label", "water_name"}
PRIO = {"city": 0, "town": 1, "village": 2, "suburb": 3, "quarter": 3, "hamlet": 3, "neighbourhood": 3,
        "isolated_dwelling": 3, "island": 3, "state": 3, "province": 3}
found = {}
tiles = 0
for zoom, x, row, blob in db.execute("select zoom_level, tile_column, tile_row, tile_data from tiles "
                                     "where zoom_level=?", (Z,)):
    tiles += 1
    y = (1 << zoom) - 1 - row
    raw = mvt.unzip(blob)
    for lname, lb in mvt.layers(raw):
        if lname not in WANT:
            continue
        feats, extent = mvt.point_features(lb)
        for props, px, py in feats:
            name = props.get("name")
            if not name or not isinstance(name, str):
                continue
            ka = props.get("name:ka") or name
            ru = props.get("name:ru") or ""
            en = props.get("name:en") or props.get("name_en") or props.get("name:latin") or ""
            cls = props.get("class") or ""
            sub = props.get("subclass") or ""
            if lname == "place":
                if cls in ("continent", "ocean", "sea", "country"):
                    continue
                kind, prio = cls, PRIO.get(cls, 4)
            elif lname == "mountain_peak":
                kind, prio = "peak", 4
            elif lname == "aerodrome_label":
                kind, prio = "aerodrome", 5
            elif lname == "water_name":
                kind, prio = "water", 4
            else:
                kind, prio = (sub or cls or "poi"), 6
            lat, lon = mvt.tile_to_lonlat(zoom, x, y, px, py, extent)
            ele = props.get("ele")
            try:
                ele = int(ele) if ele not in (None, "") else None
            except (TypeError, ValueError):
                ele = None
            key = (ka, ru, en, round(lat, 3), round(lon, 3))
            if key not in found:
                found[key] = (ka, ru, en, kind, cls, sub, prio, ele, lat, lon)
    if tiles % 5000 == 0:
        print("  tiles:", tiles, "places:", len(found))

out = sqlite3.connect(dst)
out.executescript("""
drop table if exists places;
create table places(id integer primary key, ka text, ru text, en text, s text,
                    kind text, cls text, sub text, prio integer, ele integer, lat real, lon real);
""")
rows = []
for (ka, ru, en, kind, cls, sub, prio, ele, lat, lon) in found.values():
    s = "\n".join(t.lower() for t in (ka, ru, en) if t)
    rows.append((ka, ru, en, s, kind, cls, sub, prio, ele, lat, lon))
out.executemany("insert into places(ka,ru,en,s,kind,cls,sub,prio,ele,lat,lon) values (?,?,?,?,?,?,?,?,?,?,?)", rows)
out.commit()
out.execute("vacuum")
print("tiles scanned:", tiles, "| places indexed:", len(rows))
if len(rows) < 100:
    sys.exit("ERROR: search index is almost empty")
