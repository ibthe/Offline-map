#!/usr/bin/env python3
"""Writes a tiny synthetic OSM map (4x4 street grid, one-way street, footpath diagonal, roundabout, disconnected island)
used to check build_routing.py + Router.kt end to end.  Usage: make_test_osm.py test.osm

Check:  python tools/build_routing.py test.osm /tmp/test.bin
        kotlinc app/src/main/java/app/georgiamap/Router.kt app/src/main/java/app/georgiamap/NavSession.kt \
                tools/tests/RouterCheck.kt -include-runtime -d check.jar && java -jar check.jar   (path /tmp/rt/test.bin inside)
Expected: car (0,0)->(3,3) 1337 m; one-way detour 891 m; foot diagonal 945 m via the footway; roundabout exits 1st/2nd/3rd."""
import sys
LAT0, LON0, DLA, DLO = 41.7000, 44.8000, 0.002, 0.0027
nodes, ways = {}, []
def N(i, la, lo): nodes[i] = (la, lo)
def nid(r, c): return 100 + r * 10 + c
for r in range(4):
    for c in range(4): N(nid(r, c), LAT0 + r * DLA, LON0 + c * DLO)
def W(i, refs, tags): ways.append((i, refs, tags))
for r in range(4):
    W(1000 + r, [nid(r, c) for c in range(4)], {"highway": "residential", "name": f"Row{r}", "name:ru": f"Улица {r}"})
for c in range(4):
    t = {"highway": "residential", "name": f"Col{c}", "name:ru": f"Проспект {c}"}
    if c == 3: t["oneway"] = "yes"
    W(1100 + c, [nid(r, c) for r in range(4)], t)
N(200, LAT0 + 1.5 * DLA, LON0 + 1.5 * DLO)
W(1200, [nid(0, 0), 200, nid(3, 3)], {"highway": "footway", "name": "Tropinka"})
cx, cy, rad = LON0 + 1 * DLO, LAT0 + 3 * DLA + 0.0045 + 0.0005, 0.0005
N(300, cy, cx - rad); N(301, cy - rad, cx); N(302, cy, cx + rad); N(303, cy + rad, cx)
W(1300, [300, 301, 302, 303, 300], {"highway": "residential", "junction": "roundabout"})
N(310, cy, cx - 0.003); N(312, cy, cx + 0.003); N(313, cy + 0.003, cx)
W(1310, [300, 310], {"highway": "residential", "name": "WestOut", "name:ru": "Западная"})
W(1311, [301, nid(3, 1)], {"highway": "residential", "name": "SouthSpoke", "name:ru": "Южная"})
W(1312, [302, 312], {"highway": "residential", "name": "EastOut", "name:ru": "Восточная"})
W(1313, [303, 313], {"highway": "residential", "name": "NorthOut", "name:ru": "Северная"})
N(400, LAT0 + 0.0035, LON0 + 0.0040); N(401, LAT0 + 0.0035, LON0 + 0.0044); N(402, LAT0 + 0.0039, LON0 + 0.0044)
W(1400, [400, 401, 402], {"highway": "residential", "name": "Island"})
out = ['<?xml version="1.0" encoding="UTF-8"?>', '<osm version="0.6" generator="test">']
for i, (la, lo) in nodes.items(): out.append(f'<node id="{i}" version="1" lat="{la:.7f}" lon="{lo:.7f}"/>')
for i, refs, tags in ways:
    out.append(f'<way id="{i}" version="1">' + "".join(f'<nd ref="{r}"/>' for r in refs) +
               "".join(f'<tag k="{k}" v="{v}"/>' for k, v in tags.items()) + '</way>')
out.append('</osm>')
open(sys.argv[1], "w", encoding="utf-8").write("\n".join(out))
