#!/usr/bin/env python3
"""Builds contours.mbtiles: vector tiles (layer "contour") with elevation lines (горизонтали) for a region.

The heights come from the same public AWS Terrain Tiles (Terrarium PNG) that relief.mbtiles uses. Tiles that are
already inside relief.mbtiles are read from there, the rest (z12) are downloaded once and cached.

Every line has two attributes:  ele  (metres, integer)  and  major  (1 for every 5th line: the thick, labelled one).
The interval depends on the zoom of the tile (a dense 20 m grid at zoom 8 would be a black blob):

    tile zoom   8      9      10     11     12 (shown up to the maximum zoom, by over-zooming)
    interval    500 m  500 m  200 m  100 m  20 m
    major       1000   1000   1000   500    100

Usage: build_contours.py <out.mbtiles> [--relief relief.mbtiles] [--like georgia.mbtiles]
                         [--bbox W,S,E,N] [--minzoom 8] [--maxzoom 12] [--workers N] [--cache dem_cache.sqlite]
--like: only make tiles that also exist in that (vector) MBTiles, so the area outside Georgia stays empty.
"""
import argparse
import concurrent.futures as cf
import gzip
import io
import math
import os
import sqlite3
import sys
import time
import urllib.request

import numpy as np
from PIL import Image
from scipy.ndimage import distance_transform_edt, gaussian_filter

import contourpy

URL = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"
EXTENT = 4096
BUFFER = 64            # tile units kept outside the tile edge (lines must reach over it for seamless joins)
DEM_BUF = 12           # DEM pixels read around the tile, so lines continue across tile edges
# tile zoom -> (interval m, major step m)
LEVELS = {8: (500, 1000), 9: (500, 1000), 10: (200, 1000), 11: (100, 500), 12: (20, 100)}
DEFAULT_BBOX = (39.8, 40.9, 47.0, 43.7)   # W S E N

args = None


# ------------------------------------------------------------------ tile maths
def lonlat_to_tile(z, lat, lon):
    n = 2 ** z
    x = int((lon + 180.0) / 360.0 * n)
    y = int((1.0 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2.0 * n)
    return min(max(x, 0), n - 1), min(max(y, 0), n - 1)


def tiles_in_bbox(z, bbox):
    x0, y1 = lonlat_to_tile(z, bbox[1], bbox[0])
    x1, y0 = lonlat_to_tile(z, bbox[3], bbox[2])
    return [(x, y) for x in range(x0, x1 + 1) for y in range(y0, y1 + 1)]


def dem_zoom(zc):
    """DEM zoom read for contour tile zoom zc: one level deeper (twice the pixel density), but never beyond 12
    (the source data is ~30 m, deeper tiles only interpolate)."""
    return min(zc + 1, 12)


def dem_keys_for(zc, x, y):
    zd = dem_zoom(zc)
    scale = 2 ** (zd - zc)
    x0, y0 = x * scale, y * scale
    ring = math.ceil(DEM_BUF / 256)
    n = 2 ** zd
    return zd, [(zd, tx, ty) for tx in range(x0 - ring, x0 + scale + ring) for ty in range(y0 - ring, y0 + scale + ring)
                if 0 <= tx < n and 0 <= ty < n]


# ------------------------------------------------------------------ DEM store (relief.mbtiles + local cache + download)
class Dem:
    def __init__(self, relief, cache):
        self.relief = sqlite3.connect("file:%s?mode=ro" % relief, uri=True) if relief and os.path.exists(relief) else None
        self.cache = sqlite3.connect(cache)
        self.cache.execute("create table if not exists dem(z integer, x integer, y integer, data blob, primary key(z,x,y))")
        self.cache.commit()

    def get(self, z, x, y):
        if self.relief is not None:
            r = self.relief.execute("select tile_data from tiles where zoom_level=? and tile_column=? and tile_row=?",
                                    (z, x, (1 << z) - 1 - y)).fetchone()
            if r:
                return r[0]
        r = self.cache.execute("select data from dem where z=? and x=? and y=?", (z, x, y)).fetchone()
        return r[0] if r else None

    def put(self, z, x, y, data):
        self.cache.execute("insert or replace into dem values (?,?,?,?)", (z, x, y, data))


def download(key):
    z, x, y = key
    for i in range(5):
        try:
            with urllib.request.urlopen(URL.format(z=z, x=x, y=y), timeout=60) as r:
                return key, r.read()
        except Exception:
            time.sleep(1.5 * (i + 1))
    return key, None


def decode(png):
    a = np.asarray(Image.open(io.BytesIO(png)).convert("RGB"), dtype=np.float32)
    return a[..., 0] * 256.0 + a[..., 1] + a[..., 2] / 256.0 - 32768.0


# ------------------------------------------------------------------ geometry helpers
def chaikin(p, closed, iters=2):
    for _ in range(iters):
        if len(p) < 3:
            break
        if closed:
            a, b = p[:-1], p[1:]
            q = np.empty((2 * len(a), 2))
            q[0::2] = 0.75 * a + 0.25 * b
            q[1::2] = 0.25 * a + 0.75 * b
            p = np.vstack([q, q[:1]])
        else:
            a, b = p[:-1], p[1:]
            q = np.empty((2 * len(a), 2))
            q[0::2] = 0.75 * a + 0.25 * b
            q[1::2] = 0.25 * a + 0.75 * b
            p = np.vstack([p[:1], q, p[-1:]])
    return p


def rdp(p, tol):
    """Douglas-Peucker for an open polyline (numpy, iterative)."""
    n = len(p)
    if n < 3:
        return p
    keep = np.zeros(n, bool)
    keep[0] = keep[-1] = True
    stack = [(0, n - 1)]
    while stack:
        i, j = stack.pop()
        if j <= i + 1:
            continue
        a, b = p[i], p[j]
        seg = p[i + 1:j]
        d = b - a
        L = math.hypot(d[0], d[1])
        if L < 1e-9:
            dist = np.hypot(seg[:, 0] - a[0], seg[:, 1] - a[1])
        else:
            dist = np.abs(d[0] * (seg[:, 1] - a[1]) - d[1] * (seg[:, 0] - a[0])) / L
        k = int(np.argmax(dist))
        if dist[k] > tol:
            m = i + 1 + k
            keep[m] = True
            stack.append((i, m))
            stack.append((m, j))
    return p[keep]


def clip_runs(p, lo, hi):
    """Parts of a polyline that touch the box [lo,hi]^2 (one neighbouring point is kept on each side)."""
    inside = (p[:, 0] >= lo) & (p[:, 0] <= hi) & (p[:, 1] >= lo) & (p[:, 1] <= hi)
    if inside.all():
        return [p]
    if not inside.any():
        return []
    near = inside.copy()
    near[1:] |= inside[:-1]
    near[:-1] |= inside[1:]
    runs, start = [], None
    for i, v in enumerate(near):
        if v and start is None:
            start = i
        elif not v and start is not None:
            runs.append(p[start:i])
            start = None
    if start is not None:
        runs.append(p[start:])
    return runs


# ------------------------------------------------------------------ MVT writer
def varint(n):
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def zz(n):
    return (n << 1) ^ (n >> 31)


def tag(field, wire):
    return varint((field << 3) | wire)


def ld(field, data):
    return tag(field, 2) + varint(len(data)) + data


def encode_line(pts):
    g = bytearray()
    g += varint((1 & 7) | (1 << 3))
    g += varint(zz(pts[0][0])) + varint(zz(pts[0][1]))
    g += varint((2 & 7) | ((len(pts) - 1) << 3))
    for i in range(1, len(pts)):
        g += varint(zz(pts[i][0] - pts[i - 1][0])) + varint(zz(pts[i][1] - pts[i - 1][1]))
    return bytes(g)


def encode_tile(features):
    """features: list of (ele, major, [int points]) -> gzip(MVT) with one layer called "contour"."""
    values, vindex = [], {}

    def val(v):
        if v not in vindex:
            vindex[v] = len(values)
            values.append(v)
        return vindex[v]

    feats = b""
    for ele, major, pts in features:
        tags = varint(0) + varint(val(ele)) + varint(1) + varint(val(major))
        feats += ld(2, ld(2, tags) + tag(3, 0) + varint(2) + ld(4, encode_line(pts)))
    layer = ld(1, b"contour") + feats + ld(3, b"ele") + ld(3, b"major")
    for v in values:
        layer += ld(4, tag(4, 0) + varint(v))      # int_value
    layer += tag(5, 0) + varint(EXTENT) + tag(15, 0) + varint(2)
    return gzip.compress(ld(3, layer), 6)


# ------------------------------------------------------------------ one tile
_dem = None


def _init_worker(relief, cache):
    global _dem
    _dem = Dem(relief, cache)


def build_tile(job):
    z, x, y = job
    interval, major = LEVELS[z]
    zd, keys = dem_keys_for(z, x, y)
    scale = 2 ** (zd - z)
    size = 256 * scale                          # the tile edge, in DEM pixels
    ring = math.ceil(DEM_BUF / 256)
    tx0, ty0 = x * scale - ring, y * scale - ring
    n_t = scale + 2 * ring
    H = W = n_t * 256
    mos = np.full((H, W), np.nan, np.float32)
    for (kz, kx, ky) in keys:
        blob = _dem.get(kz, kx, ky)
        if blob is None:
            continue
        mos[(ky - ty0) * 256:(ky - ty0 + 1) * 256, (kx - tx0) * 256:(kx - tx0 + 1) * 256] = decode(blob)
    valid = ~np.isnan(mos)
    if not valid.any():
        return job, None
    if not valid.all():                         # outside the dataset: copy the nearest known height, mask it afterwards
        idx = distance_transform_edt(~valid, return_distances=False, return_indices=True)
        mos = mos[tuple(idx)]
    off = ring * 256                            # pixel where the tile itself starts inside the mosaic
    centre = mos[off:off + size, off:off + size]
    if centre.max() <= 0:
        return job, None
    mos = gaussian_filter(mos, sigma=1.0 if scale == 1 else 1.6, mode="nearest")
    field = np.ma.masked_array(mos, mask=~valid) if not valid.all() else mos

    lo = max(centre.min(), 0.0)
    hi = float(mos[off:off + size, off:off + size].max()) + 1.0
    first = int(math.floor(lo / interval)) * interval + interval
    levels = [lv for lv in range(first, int(hi) + 1, interval) if lv > 0]
    if not levels:
        return job, None
    xs = np.arange(W, dtype=np.float64) + 0.5
    ys = np.arange(H, dtype=np.float64) + 0.5
    gen = contourpy.contour_generator(xs, ys, field, line_type=contourpy.LineType.Separate)
    k = EXTENT / size
    feats = []
    for lv in levels:
        for ln in gen.lines(lv):
            if len(ln) < 3:
                continue
            p = (ln - off) * k                  # tile units
            closed = bool(np.allclose(p[0], p[-1]))
            p = chaikin(p, closed)
            p = rdp(p, 1.2)
            for part in clip_runs(p, -BUFFER, EXTENT + BUFFER):
                q = np.rint(part).astype(np.int64)
                keep = np.ones(len(q), bool)
                keep[1:] = np.any(q[1:] != q[:-1], axis=1)
                q = q[keep]
                if len(q) < 2:
                    continue
                if np.abs(np.diff(q, axis=0)).sum() < 12:      # tiny specks
                    continue
                feats.append((lv, 1 if lv % major == 0 else 0, q.tolist()))
    if not feats:
        return job, None
    return job, encode_tile(feats)


# ------------------------------------------------------------------ main
def main():
    global args
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--relief", default=None)
    ap.add_argument("--like", default=None)
    ap.add_argument("--bbox", default=",".join(map(str, DEFAULT_BBOX)))
    ap.add_argument("--minzoom", type=int, default=8)
    ap.add_argument("--maxzoom", type=int, default=12)
    ap.add_argument("--workers", type=int, default=max(1, (os.cpu_count() or 2)))
    ap.add_argument("--cache", default=None)
    ap.add_argument("--no-download", action="store_true")
    args = ap.parse_args()
    bbox = tuple(float(v) for v in args.bbox.split(","))
    cache = args.cache or (args.out + ".dem-cache.sqlite")

    like = None
    if args.like:
        like = sqlite3.connect("file:%s?mode=ro" % args.like, uri=True)
        lmax = int(dict(like.execute("select name, value from metadata")).get("maxzoom") or 14)

    jobs = []
    for z in range(args.minzoom, args.maxzoom + 1):
        if z not in LEVELS:
            continue
        n = 0
        for (x, y) in tiles_in_bbox(z, bbox):
            if like is not None:
                zz_ = min(z, lmax)
                if not like.execute("select 1 from tiles where zoom_level=? and tile_column=? and tile_row=? limit 1",
                                    (zz_, x, (1 << zz_) - 1 - y)).fetchone():
                    continue
            jobs.append((z, x, y))
            n += 1
        print("zoom %d: %d tiles (interval %d m)" % (z, n, LEVELS[z][0]))
    if not jobs:
        sys.exit("ERROR: no contour tiles to build")

    # 1) make sure all height tiles are here (relief.mbtiles, cache, or download)
    dem = Dem(args.relief, cache)
    need = set()
    for (z, x, y) in jobs:
        need.update(dem_keys_for(z, x, y)[1])
    missing = [k for k in sorted(need) if dem.get(*k) is None]
    print("height tiles needed: %d, missing: %d" % (len(need), len(missing)))
    if missing and args.no_download:
        sys.exit("ERROR: %d height tiles are missing and --no-download is set" % len(missing))
    fail = 0
    if missing:
        t0 = time.time()
        with cf.ThreadPoolExecutor(16) as ex:
            for i, (key, data) in enumerate(ex.map(download, missing), 1):
                if data is None:
                    fail += 1
                else:
                    dem.put(*key, data)
                if i % 500 == 0:
                    dem.cache.commit()
                    print("  downloaded %d / %d (%.0f s)" % (i, len(missing), time.time() - t0))
        dem.cache.commit()
        print("download done, failed: %d" % fail)
        if fail > len(missing) * 0.02:
            sys.exit("ERROR: too many height tiles failed to download")
    dem.cache.close()
    if dem.relief is not None:
        dem.relief.close()

    # 2) contours
    tmp = args.out + ".tmp"
    if os.path.exists(tmp):
        os.remove(tmp)
    db = sqlite3.connect(tmp)
    db.executescript("""
    create table metadata(name text, value text);
    create table tiles(zoom_level integer, tile_column integer, tile_row integer, tile_data blob);
    create unique index tile_index on tiles(zoom_level, tile_column, tile_row);
    """)
    done = empty = size = 0
    t0 = time.time()
    with cf.ProcessPoolExecutor(args.workers, initializer=_init_worker, initargs=(args.relief, cache)) as ex:
        for (z, x, y), blob in ex.map(build_tile, jobs, chunksize=4):
            done += 1
            if blob is None:
                empty += 1
            else:
                db.execute("insert into tiles values (?,?,?,?)", (z, x, (1 << z) - 1 - y, blob))
                size += len(blob)
            if done % 200 == 0:
                db.commit()
                print("  tiles %d / %d (empty %d, %.1f MB, %.0f s)" % (done, len(jobs), empty, size / 1e6, time.time() - t0))
    zs = [j[0] for j in jobs]
    meta = {"name": "contours", "format": "pbf", "minzoom": str(min(zs)), "maxzoom": str(max(zs)),
            "bounds": ",".join(map(str, bbox)), "type": "overlay", "version": "1",
            "description": "Elevation contours from Terrarium DEM",
            "json": '{"vector_layers":[{"id":"contour","minzoom":%d,"maxzoom":%d,"fields":{"ele":"Number","major":"Number"}}]}'
                    % (min(zs), max(zs))}
    db.executemany("insert into metadata values (?,?)", meta.items())
    db.commit()
    n_tiles = db.execute("select count(*) from tiles").fetchone()[0]
    db.execute("vacuum")
    db.close()
    print("tiles written: %d, empty: %d, size: %.1f MB" % (n_tiles, empty, size / 1e6))
    if n_tiles == 0:
        os.remove(tmp)
        sys.exit("ERROR: no contour tile had any line")
    os.replace(tmp, args.out)
    try:
        os.remove(cache)
    except OSError:
        pass


if __name__ == "__main__":
    main()
