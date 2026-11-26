#!/usr/bin/env python3
"""Makes a small synthetic relief.mbtiles (Terrarium PNG tiles of an invented mountain) so that build_contours.py
can be checked without internet. Usage: make_test_dem.py <out.mbtiles> [W,S,E,N]"""
import io
import math
import sqlite3
import sys

import numpy as np
from PIL import Image

out = sys.argv[1]
bbox = tuple(float(v) for v in (sys.argv[2] if len(sys.argv) > 2 else "43.0,42.0,43.4,42.3").split(","))
ZMIN, ZMAX = 8, 12


def tile_range(z, pad=2):
    n = 2 ** z
    x0 = int((bbox[0] + 180) / 360 * n)
    x1 = int((bbox[2] + 180) / 360 * n)
    y0 = int((1 - math.asinh(math.tan(math.radians(bbox[3]))) / math.pi) / 2 * n)
    y1 = int((1 - math.asinh(math.tan(math.radians(bbox[1]))) / math.pi) / 2 * n)
    return range(x0 - pad, x1 + pad + 1), range(y0 - pad, y1 + pad + 1)


def height(lat, lon):
    # a main summit, a ridge running north-east and a valley with a river-like depression
    d = lambda la, lo, s: np.exp(-(((lat - la) * 111) ** 2 + ((lon - lo) * 82) ** 2) / (2 * s * s))
    h = 300 + 2300 * d(42.12, 43.20, 9) + 1400 * d(42.20, 43.32, 7) + 900 * d(42.05, 43.08, 6)
    h += 450 * np.sin(lat * 60) * np.cos(lon * 70) * d(42.15, 43.2, 25)
    h -= 250 * np.exp(-((lat - 42.18 + (lon - 43.2) * 0.4) * 111) ** 2 / (2 * 2.5 ** 2))
    return np.maximum(h, 5)


db = sqlite3.connect(out)
db.executescript("""create table metadata(name text, value text);
create table tiles(zoom_level integer, tile_column integer, tile_row integer, tile_data blob);
create unique index tile_index on tiles(zoom_level, tile_column, tile_row);""")
n = 0
for z in range(ZMIN, ZMAX + 1):
    N = 2 ** z
    xr, yr = tile_range(z)
    for x in xr:
        for y in yr:
            px = (x + (np.arange(256) + 0.5) / 256) / N
            py = (y + (np.arange(256) + 0.5) / 256) / N
            lon = px * 360 - 180
            lat = np.degrees(np.arctan(np.sinh(np.pi * (1 - 2 * py))))
            LON, LAT = np.meshgrid(lon, lat)
            v = height(LAT, LON) + 32768.0
            R = np.floor(v / 256)
            G = np.floor(v % 256)
            B = np.floor((v - np.floor(v)) * 256)
            im = Image.fromarray(np.dstack([R, G, B]).astype(np.uint8), "RGB")
            buf = io.BytesIO()
            im.save(buf, "PNG")
            db.execute("insert into tiles values (?,?,?,?)", (z, x, N - 1 - y, buf.getvalue()))
            n += 1
db.executemany("insert into metadata values (?,?)", {"name": "relief", "format": "png", "minzoom": str(ZMIN), "maxzoom": str(ZMAX)}.items())
db.commit()
print("tiles:", n)
