#!/usr/bin/env python3
"""Generic validation for OpenMapTiles MBTiles that are not the Georgia base map."""
import sqlite3
import sys

import mvt
from build_glyphs import fields

path = sys.argv[1]
db = sqlite3.connect(path)
try:
    meta = dict(db.execute("select name,value from metadata"))
    maxzoom = int(meta.get("maxzoom") or 14)
    total = db.execute("select count(*) from tiles where zoom_level=?", (maxzoom,)).fetchone()[0]
    if total == 0:
        maxzoom = db.execute("select max(zoom_level) from tiles").fetchone()[0]
        total = db.execute("select count(*) from tiles where zoom_level=?", (maxzoom,)).fetchone()[0]
    if not total:
        sys.exit("ERROR: regional tileset is empty")

    layers = {}
    named_points = 0
    for index, (blob,) in enumerate(db.execute(
        "select tile_data from tiles where zoom_level=? order by tile_column,tile_row limit 10000",
        (maxzoom,)
    ), 1):
        raw = mvt.unzip(blob)
        for name, layer in mvt.layers(raw):
            layers[name] = layers.get(name, 0) + sum(1 for field, _, _ in fields(layer) if field == 2)
            if name in ("place", "poi", "mountain_peak", "aerodrome_label", "water_name"):
                features, _ = mvt.point_features(layer)
                named_points += sum(1 for props, _, _ in features
                                    if isinstance(props.get("name"), str) and props["name"].strip())
        if index % 2000 == 0:
            print("sampled tiles:", index, "| named points:", named_points)

    missing = [name for name in ("transportation", "place") if not layers.get(name)]
    if missing:
        sys.exit("ERROR: regional tileset is missing required layers: " + ", ".join(missing))
    if named_points == 0:
        sys.exit("ERROR: no named place/POI points found in sampled regional tiles")
    print("regional tiles OK:", {"maxzoom": maxzoom, "tiles": total, "layers": layers,
                                "named_points_sampled": named_points})
finally:
    db.close()


