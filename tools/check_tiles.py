#!/usr/bin/env python3
"""Sanity check: the tiles must carry Georgian and Russian names, otherwise the language switch is useless."""
import gzip
import sqlite3
import sys

path = sys.argv[1]
db = sqlite3.connect(path)
cur = db.execute("select tile_data from tiles where zoom_level between 5 and 10 limit 3000")
keys = {b"name:ka": 0, b"name:ru": 0, b"name:en": 0, b"name_en": 0}
tiles = 0
for (blob,) in cur:
    tiles += 1
    try:
        blob = gzip.decompress(blob)
    except OSError:
        pass
    for k in keys:
        if k in blob:
            keys[k] += 1
print("tiles scanned:", tiles)
for k, v in keys.items():
    print("  %-8s found in %d tiles" % (k.decode(), v))
meta = dict(db.execute("select name, value from metadata"))
print("metadata:", {k: meta.get(k) for k in ("name", "minzoom", "maxzoom", "bounds", "format")})
if tiles == 0:
    sys.exit("ERROR: tileset is empty")
if keys[b"name:ka"] == 0 or keys[b"name:ru"] == 0:
    sys.exit("ERROR: tiles have no name:ka / name:ru. Add --languages=... to the Planetiler call "
             "(see tools/README.md) or check the Planetiler version.")
if keys[b"name:en"] == 0 and keys[b"name_en"] == 0:
    sys.exit("ERROR: tiles have no English names")
