#!/usr/bin/env python3
"""Builds the offline road graph (routing.bin) used by the in-app navigator from an OSM extract.

Usage: build_routing.py <in.osm.pbf> <out.bin>

The graph keeps only junctions as nodes; every road piece between two junctions is one undirected edge that stores
its real geometry, length, name, class and per-direction access for three profiles (car / foot / bike).
A coarse grid index (0.01 deg cells) is stored too, so the phone can snap a point to the nearest road quickly.
Format (little endian) is read by app/src/main/java/app/georgiamap/Router.kt - keep both in sync."""
import math
import re
import struct
import sys
from collections import Counter

import numpy as np
import osmium

# ---------------------------------------------------------------- tag knowledge
CLS = {"motorway": 0, "motorway_link": 0, "trunk": 1, "trunk_link": 1, "primary": 2, "primary_link": 2,
       "secondary": 3, "secondary_link": 3, "tertiary": 4, "tertiary_link": 4, "unclassified": 5,
       "residential": 5, "road": 5, "service": 6, "track": 7, "path": 8, "footway": 8, "cycleway": 8,
       "pedestrian": 8, "bridleway": 8, "steps": 9, "living_street": 10}
NO_CAR = {"path", "footway", "cycleway", "pedestrian", "bridleway", "steps"}
CAR_KMH = {"motorway": 90, "motorway_link": 50, "trunk": 80, "trunk_link": 50, "primary": 70, "primary_link": 45,
           "secondary": 60, "secondary_link": 40, "tertiary": 50, "tertiary_link": 35, "unclassified": 40,
           "residential": 30, "road": 30, "service": 20, "track": 15, "living_street": 15}
UNPAVED = {"unpaved", "gravel", "dirt", "ground", "earth", "mud", "sand", "grass", "fine_gravel", "compacted",
           "pebblestone", "dirt/sand"}
TRACK_GRADE = {"grade1": 1, "grade2": 2, "grade3": 3, "grade4": 4, "grade5": 5}
SMOOTHNESS = {"excellent": 1, "good": 2, "intermediate": 3, "bad": 4,
              "very_bad": 5, "horrible": 6, "very_horrible": 7, "impassable": 7}
DENY = {"no", "private", "customers", "delivery", "destination", "agricultural", "forestry", "discouraged"}
YES = {"yes", "designated", "permissive", "official"}


def kmh(v):
    if not v:
        return None
    m = re.match(r"^\s*(\d+(?:\.\d+)?)\s*(mph)?\s*$", v.lower())
    if not m:
        return None
    x = float(m.group(1)) * (1.609 if m.group(2) else 1.0)
    return x if 5 <= x <= 140 else None


def allowed(t, keys, default):
    """First of the access keys that is present decides (most specific first)."""
    for k in keys:
        v = t.get(k)
        if v is not None:
            return v in YES if v in YES else (False if v in DENY else default)
    return default


def direction_access(access, oneway):
    """Return (forward, backward) access, honoring OSM's directional values."""
    if not access:
        return False, False
    value = str(oneway or "").strip().lower()
    if value in ("yes", "true", "1"):
        return True, False
    if value in ("-1", "reverse"):
        return False, True
    return True, True


def first_tag(tags, keys, default=None):
    for key in keys:
        value = tags.get(key)
        if value is not None:
            return value
    return default


def way_props(t):
    hw = t.get("highway")
    rb = t.get("junction") in ("roundabout", "circular")
    car = hw not in NO_CAR and allowed(t, ("motorcar", "motor_vehicle", "vehicle", "access"), True)
    foot = hw not in ("motorway", "motorway_link") and allowed(t, ("foot", "access"), True)
    bike_default = hw not in ("motorway", "motorway_link", "steps")
    if hw in ("footway", "pedestrian"):
        bike_default = False
    bike = bike_default and allowed(t, ("bicycle", "vehicle", "access"), bike_default)
    if hw == "steps":
        bike = False

    general_oneway = t.get("oneway")
    if general_oneway is None and (rb or hw in ("motorway", "motorway_link")):
        general_oneway = "yes"
    car_oneway = first_tag(t, ("oneway:motorcar", "oneway:motor_vehicle"), general_oneway)
    bike_oneway = t.get("oneway:bicycle")
    if bike_oneway is None:
        bike_oneway = "no" if str(t.get("cycleway", "")).startswith("opposite") else general_oneway
    foot_oneway = t.get("oneway:foot", "no")

    car_f, car_b = direction_access(car, car_oneway)
    foot_f, foot_b = direction_access(foot, foot_oneway)
    bike_f, bike_b = direction_access(bike, bike_oneway)

    unpaved = t.get("surface") in UNPAVED or (hw == "track" and t.get("tracktype", "grade3") not in ("grade1", "grade2"))
    sp = CAR_KMH.get(hw, 30)
    ms = kmh(t.get("maxspeed"))
    if ms:
        sp = ms * 0.9
    if unpaved:
        sp = min(sp, 20 if hw == "track" else 30)
    sp = int(max(5, min(130, round(sp))))
    track_grade = TRACK_GRADE.get(str(t.get("tracktype", "")).lower(), 0)
    smoothness = SMOOTHNESS.get(str(t.get("smoothness", "")).lower(), 0)
    toll = str(t.get("toll", "")).lower() in ("yes", "true", "1")
    flags = (int(car_f) | int(foot_f) << 1 | int(bike_f) << 2 |
             int(car_b) << 3 | int(foot_b) << 4 | int(bike_b) << 5 |
             sp << 6 | CLS[hw] << 14 | int(rb) << 18 | int(unpaved) << 19 |
             track_grade << 20 | smoothness << 23 | int(toll) << 26)
    if not (car or foot or bike):
        return None
    name = t.get("name:ru") or t.get("name") or t.get("name:en") or t.get("ref") or ""
    return flags, name


class Ways(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.ways = []   # (refs, lat[], lon[], flags, name)

    def way(self, w):
        t = w.tags
        hw = t.get("highway")
        if hw not in CLS or t.get("area") == "yes":
            return
        props = way_props(t)
        if props is None:
            return
        try:
            refs, lat, lon = [], [], []
            for n in w.nodes:
                refs.append(n.ref)
                lat.append(int(round(n.lat * 1e6)))
                lon.append(int(round(n.lon * 1e6)))
        except osmium.InvalidLocationError:
            return
        if len(refs) >= 2:
            self.ways.append((refs, lat, lon, props[0], props[1]))


def dist_m(la1, lo1, la2, lo2):
    x = (lo2 - lo1) * 1e-6 * 111320.0 * math.cos(math.radians((la1 + la2) * 0.5e-6))
    y = (la2 - la1) * 1e-6 * 110574.0
    return math.hypot(x, y)


def main(src, out):
    h = Ways()
    h.apply_file(src, locations=True, idx="flex_mem")
    ways = h.ways
    print(f"ways with highway tag: {len(ways)}")

    # junctions: way ends and nodes shared by two or more way occurrences
    allrefs = np.fromiter((r for w in ways for r in w[0]), dtype=np.int64)
    uniq, cnt = np.unique(allrefs, return_counts=True)
    junction = set(uniq[cnt >= 2].tolist())
    for w in ways:
        junction.add(w[0][0])
        junction.add(w[0][-1])

    node_id, node_lat, node_lon = {}, [], []
    names, name_idx = [""], {"": 0}
    eA, eB, eLen, eFlags, eName, geoms = [], [], [], [], [], []

    def node(ref, la, lo):
        i = node_id.get(ref)
        if i is None:
            i = node_id[ref] = len(node_lat)
            node_lat.append(la)
            node_lon.append(lo)
        return i

    for refs, lat, lon, flags, name in ways:
        ni = name_idx.get(name)
        if ni is None:
            ni = name_idx[name] = len(names)
            names.append(name)
        start = 0
        for i in range(1, len(refs)):
            if refs[i] in junction:
                seg = range(start, i + 1)
                if i > start:
                    g = [(lat[j], lon[j]) for j in seg]
                    length = sum(dist_m(g[j][0], g[j][1], g[j + 1][0], g[j + 1][1]) for j in range(len(g) - 1))
                    a = node(refs[start], lat[start], lon[start])
                    b = node(refs[i], lat[i], lon[i])
                    if length >= 0.5 and not (a == b and len(g) < 4):
                        eA.append(a); eB.append(b); eLen.append(length); eFlags.append(flags); eName.append(ni)
                        geoms.append(g)
                start = i

    # Remove only likely one-edge parking/driveway fragments. The previous >=8-edge
    # threshold also discarded real short streets, named access roads and hiking paths.
    parent = list(range(len(node_lat)))

    def find(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    for a, b in zip(eA, eB):
        ra, rb_ = find(a), find(b)
        if ra != rb_:
            parent[ra] = rb_
    roots = [find(a) for a in eA]
    ec = Counter(roots)
    named_components = {roots[i] for i, name in enumerate(eName) if name != 0}
    keep = [
        i for i, root in enumerate(roots)
        if ec[root] >= 2 or root in named_components or ((eFlags[i] >> 14) & 0xF) in (7, 8, 9)
    ]
    print(f"edges: {len(eA)} -> kept {len(keep)} after filtering isolated unnamed minor fragments")
    used = sorted({eA[i] for i in keep} | {eB[i] for i in keep})
    remap = {old: new for new, old in enumerate(used)}
    nlat = np.array([node_lat[o] for o in used], dtype="<i4")
    nlon = np.array([node_lon[o] for o in used], dtype="<i4")
    M = len(keep)
    A = np.array([remap[eA[i]] for i in keep], dtype="<i4")
    B = np.array([remap[eB[i]] for i in keep], dtype="<i4")
    L = np.array([eLen[i] for i in keep], dtype="<f4")
    F = np.array([eFlags[i] for i in keep], dtype="<i4")
    NM = np.array([eName[i] for i in keep], dtype="<i4")
    gk = [geoms[i] for i in keep]
    counts = np.array([len(g) for g in gk], dtype=np.int64)
    goff = np.zeros(M + 1, dtype="<i4")
    goff[1:] = np.cumsum(counts)
    glat = np.array([p[0] for g in gk for p in g], dtype="<i4")
    glon = np.array([p[1] for g in gk for p in g], dtype="<i4")

    # ---- grid index: every edge is listed in each 0.01 deg cell it passes through
    CELL = 10000
    lat0, lon0 = int(glat.min()) - CELL, int(glon.min()) - CELL
    rows = (int(glat.max()) - lat0) // CELL + 2
    cols = (int(glon.max()) - lon0) // CELL + 2
    pe = np.repeat(np.arange(M, dtype=np.int64), counts)
    ok = pe[:-1] == pe[1:]
    s_la, s_lo = glat[:-1][ok].astype(np.float64), glon[:-1][ok].astype(np.float64)
    d_la, d_lo = (glat[1:][ok] - glat[:-1][ok]).astype(np.float64), (glon[1:][ok] - glon[:-1][ok]).astype(np.float64)
    s_e = pe[:-1][ok]
    n = np.maximum(1, np.ceil(np.maximum(np.abs(d_la), np.abs(d_lo)) / (CELL / 2))).astype(np.int64)
    tot = int(n.sum())
    first = np.cumsum(n) - n
    t = (np.arange(tot) - np.repeat(first, n)) / np.repeat(n, n)
    sl = np.repeat(s_la, n) + np.repeat(d_la, n) * t
    so = np.repeat(s_lo, n) + np.repeat(d_lo, n) * t
    se = np.repeat(s_e, n)
    last = goff[1:] - 1
    sl = np.concatenate([sl, glat[last]]); so = np.concatenate([so, glon[last]])
    se = np.concatenate([se, np.arange(M, dtype=np.int64)])
    cell = ((sl - lat0) // CELL).astype(np.int64) * cols + ((so - lon0) // CELL).astype(np.int64)
    key = np.unique(cell * M + se)
    g_cell, g_edge = key // M, (key % M).astype("<i4")
    goffs = np.zeros(rows * cols + 1, dtype="<i4")
    np.add.at(goffs, g_cell + 1, 1)
    goffs = np.cumsum(goffs).astype("<i4")

    nm_bytes = [s.encode("utf-8") for s in names]
    nm_off = np.zeros(len(names) + 1, dtype="<i4")
    nm_off[1:] = np.cumsum([len(b) for b in nm_bytes])

    with open(out, "wb") as f:
        f.write(b"GRT1")
        f.write(struct.pack("<11i", len(used), M, len(glat), len(names), int(nm_off[-1]), lat0, lon0, CELL, rows,
                            cols, len(g_edge)))
        for arr in (nlat, nlon, A, B, L, F, NM, goff, glat, glon, goffs, g_edge, nm_off):
            f.write(arr.astype(arr.dtype).tobytes())
        for b in nm_bytes:
            f.write(b)
    print(f"nodes {len(used)}, edges {M}, geometry points {len(glat)}, grid {rows}x{cols} ({len(g_edge)} entries), "
          f"{len(names)} names -> {out}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
