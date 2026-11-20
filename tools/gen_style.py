#!/usr/bin/env python3
"""Generates app/src/main/assets/style.json (OpenMapTiles schema, fully offline)."""
import json, sys

SRC = "openmaptiles"
FONT = ["NotoSans"]
L = []


def zi(*stops, base=1.4):
    out = ["interpolate", ["exponential", base], ["zoom"]]
    for z, v in stops:
        out += [z, v]
    return out


def cls(*names):
    return ["match", ["get", "class"], list(names), True, False]


def layer(id, type, sl=None, filter=None, minzoom=None, maxzoom=None, paint=None, layout=None):
    d = {"id": id, "type": type}
    if type != "background":
        d["source"] = SRC
    if sl:
        d["source-layer"] = sl
    if filter is not None:
        d["filter"] = filter
    if minzoom is not None:
        d["minzoom"] = minzoom
    if maxzoom is not None:
        d["maxzoom"] = maxzoom
    if layout:
        d["layout"] = layout
    if paint:
        d["paint"] = paint
    L.append(d)


# ------------------------------------------------------------------ base
layer("background", "background", paint={"background-color": "#f3f0e8"})

for c, col in {"grass": "#e2ecd0", "wood": "#cde1b6", "farmland": "#eef0d8", "ice": "#f7fbfe",
               "rock": "#ddd8cf", "sand": "#f2e9cb", "wetland": "#d9e9dd"}.items():
    layer(f"landcover-{c}", "fill", "landcover", ["==", ["get", "class"], c],
          paint={"fill-color": col, "fill-opacity": 0.9})

layer("landuse-residential", "fill", "landuse", cls("residential", "suburb", "neighbourhood"), minzoom=6,
      paint={"fill-color": "#e9e5dc", "fill-opacity": zi((6, 0.4), (12, 0.9))})
layer("landuse-commercial", "fill", "landuse", cls("commercial", "retail"), minzoom=10,
      paint={"fill-color": "#efe0d6"})
layer("landuse-industrial", "fill", "landuse", cls("industrial", "railway", "quarry"), minzoom=10,
      paint={"fill-color": "#e3e0da"})
layer("landuse-green", "fill", "landuse", cls("cemetery", "pitch", "playground", "stadium", "garden", "track"), minzoom=11,
      paint={"fill-color": "#d5e6c7"})
layer("landuse-institution", "fill", "landuse", cls("school", "university", "college", "kindergarten"), minzoom=12,
      paint={"fill-color": "#ece4d2"})
layer("landuse-hospital", "fill", "landuse", cls("hospital"), minzoom=12, paint={"fill-color": "#f3dcdc"})

layer("park", "fill", "park", minzoom=5,
      paint={"fill-color": "#c9e2b0", "fill-opacity": zi((5, 0.25), (10, 0.5), base=1)})
layer("park-outline", "line", "park", minzoom=8,
      layout={"line-join": "round"},
      paint={"line-color": "#8dbb78", "line-width": zi((8, 0.6), (14, 1.4)), "line-dasharray": [4, 2]})

layer("water", "fill", "water", paint={"fill-color": "#a9cdee"})

layer("waterway-river", "line", "waterway", ["==", ["get", "class"], "river"], minzoom=8,
      layout={"line-cap": "round", "line-join": "round"},
      paint={"line-color": "#a9cdee", "line-width": zi((8, 0.5), (12, 1.6), (14, 3), (18, 12))})
layer("waterway-minor", "line", "waterway", cls("stream", "canal", "drain", "ditch"), minzoom=12,
      layout={"line-cap": "round", "line-join": "round"},
      paint={"line-color": "#a9cdee", "line-width": zi((12, 0.4), (16, 2), (18, 4))})

layer("aeroway-area", "fill", "aeroway", ["==", ["geometry-type"], "Polygon"], minzoom=11,
      paint={"fill-color": "#e6e2db"})
layer("aeroway-runway", "line", "aeroway",
      ["all", ["==", ["geometry-type"], "LineString"], cls("runway")], minzoom=10,
      paint={"line-color": "#d2cec6", "line-width": zi((10, 1), (14, 6), (18, 40))})
layer("aeroway-taxiway", "line", "aeroway",
      ["all", ["==", ["geometry-type"], "LineString"], cls("taxiway")], minzoom=12,
      paint={"line-color": "#d9d5cd", "line-width": zi((12, 0.5), (16, 3), (18, 10))})

layer("building", "fill", "building", minzoom=13,
      paint={"fill-color": "#dad4c8", "fill-opacity": zi((13, 0), (14.5, 1), base=1)})
layer("building-outline", "line", "building", minzoom=15,
      paint={"line-color": "#c8c1b3", "line-width": 0.5})

# ------------------------------------------------------------------ roads
LINE = {"line-cap": "round", "line-join": "round"}


def widths(*stops):
    return list(stops)


ROADS = [  # name, classes, minzoom, fill, casing, width stops
    ("service", ["service"], 14, "#ffffff", "#d0cabe", [(14, 1), (16, 3), (18, 9)]),
    ("minor", ["minor"], 12, "#ffffff", "#cfc9bd", [(12, 0.8), (14, 2.5), (16, 7), (18, 22)]),
    ("tertiary", ["tertiary"], 10, "#ffffff", "#c9c2b5", [(10, 0.8), (12, 1.8), (14, 4), (16, 10), (18, 28)]),
    ("secondary", ["secondary"], 8, "#fff1b8", "#d4c68a", [(8, 0.8), (12, 2.2), (14, 5), (16, 12), (18, 32)]),
    ("primary", ["primary"], 6, "#fddc8a", "#d2b268", [(6, 0.6), (10, 1.6), (12, 3), (14, 6), (16, 13), (18, 34)]),
    ("trunk", ["trunk"], 5, "#fbc975", "#cf9c4c", [(5, 0.5), (10, 1.8), (12, 3.2), (14, 6.5), (16, 14), (18, 36)]),
    ("motorway", ["motorway"], 5, "#f6a96b", "#c9783a", [(5, 0.6), (10, 2), (12, 3.5), (14, 7), (16, 15), (18, 38)]),
]
for name, classes, mz, fill, casing, st in ROADS:
    layer(f"road-{name}-casing", "line", "transportation", cls(*classes), minzoom=mz, layout=LINE,
          paint={"line-color": casing,
                 "line-width": zi(*[(z, round(w * 1.35 + 0.7, 2)) for z, w in st])})
layer("road-track", "line", "transportation", cls("track"), minzoom=12, layout=LINE,
      paint={"line-color": "#b09462", "line-dasharray": [3, 1.5],
             "line-width": zi((12, 0.6), (15, 1.4), (18, 3))})
layer("road-path", "line", "transportation", cls("path"), minzoom=12, layout=LINE,
      paint={"line-color": "#a0744a", "line-dasharray": [1.5, 1.5],
             "line-width": zi((12, 0.6), (15, 1.2), (18, 2.4))})
for name, classes, mz, fill, casing, st in ROADS:
    layer(f"road-{name}", "line", "transportation", cls(*classes), minzoom=mz, layout=LINE,
          paint={"line-color": fill, "line-width": zi(*st)})
layer("rail", "line", "transportation", cls("rail"), minzoom=8, layout={"line-join": "round"},
      paint={"line-color": "#8c8c98", "line-dasharray": [4, 3], "line-width": zi((8, 0.6), (14, 1.2), (18, 2))})
layer("ferry", "line", "transportation", cls("ferry"), minzoom=6,
      paint={"line-color": "#6f9fcf", "line-dasharray": [3, 3], "line-width": 1.2})

# ------------------------------------------------------------------ boundaries
NOMARITIME = ["!=", ["get", "maritime"], 1]
layer("boundary-2", "line", "boundary",
      ["all", ["==", ["get", "admin_level"], 2], NOMARITIME, ["!=", ["get", "disputed"], 1]],
      layout={"line-join": "round"},
      paint={"line-color": "#7a6e92", "line-width": zi((2, 0.6), (6, 1.4), (10, 2.4))})
layer("boundary-2-disputed", "line", "boundary",
      ["all", ["==", ["get", "admin_level"], 2], NOMARITIME, ["==", ["get", "disputed"], 1]],
      paint={"line-color": "#7a6e92", "line-dasharray": [3, 2], "line-width": zi((2, 0.6), (6, 1.4), (10, 2.4))})
layer("boundary-4", "line", "boundary", ["all", ["==", ["get", "admin_level"], 4], NOMARITIME], minzoom=5,
      paint={"line-color": "#a898bf", "line-dasharray": [3, 2], "line-width": zi((5, 0.6), (10, 1.6))})
layer("boundary-6", "line", "boundary", ["all", ["==", ["get", "admin_level"], 6], NOMARITIME], minzoom=9,
      paint={"line-color": "#c3b9d2", "line-dasharray": [2, 2], "line-width": 0.9})

# ------------------------------------------------------------------ dots
layer("peak-dot", "circle", "mountain_peak", ["has", "name"], minzoom=8,
      paint={"circle-radius": 2.6, "circle-color": "#8c6a4a", "circle-stroke-width": 0.8,
             "circle-stroke-color": "#ffffff"})
layer("poi-dot", "circle", "poi", ["has", "name"], minzoom=15,
      paint={"circle-radius": 3, "circle-color": "#8793a5", "circle-stroke-width": 1,
             "circle-stroke-color": "#ffffff"})

# ------------------------------------------------------------------ labels
# Every symbol layer whose id starts with "lbl-" gets its text-field replaced at runtime
# (see LabelExpr.kt), according to the languages the user switched on.
def label(id, sl, filter, minzoom, size, color, halo="rgba(255,255,255,0.9)", maxzoom=None,
          line=False, anchor=None, offset=None, sort=None, width=8, halo_w=1.5):
    lo = {
        "text-field": ["to-string", ["get", "name"]],
        "text-font": FONT,
        "text-size": size,
        "text-max-width": width,
        "text-padding": 2,
    }
    if line:
        lo["symbol-placement"] = "line"
        lo["symbol-spacing"] = 280
        lo["text-max-angle"] = 35
    if anchor:
        lo["text-anchor"] = anchor
    if offset:
        lo["text-offset"] = offset
    if sort is not None:
        lo["symbol-sort-key"] = sort
    layer(id, "symbol", sl, filter, minzoom, maxzoom, layout=lo,
          paint={"text-color": color, "text-halo-color": halo, "text-halo-width": halo_w})


RANK = ["to-number", ["get", "rank"], 99]
HASNAME_OR_REF = ["any", ["has", "name"], ["has", "ref"]]

label("lbl-water", "water_name", ["has", "name"], 0, zi((0, 10), (8, 12), (14, 14)), "#3f7fb5",
      halo="rgba(200,226,246,0.9)")
label("lbl-waterway", "waterway", ["all", ["has", "name"], cls("river")], 10,
      zi((10, 10), (16, 13)), "#3f7fb5", halo="rgba(200,226,246,0.9)", line=True)
label("lbl-road-major", "transportation_name",
      ["all", HASNAME_OR_REF, cls("motorway", "trunk", "primary", "secondary")], 9,
      zi((9, 10), (16, 13)), "#4a4a4a", line=True)
label("lbl-road-minor", "transportation_name",
      ["all", HASNAME_OR_REF, cls("tertiary", "minor", "service", "track", "path")], 13,
      zi((13, 10), (17, 12)), "#555555", line=True)
label("lbl-peak", "mountain_peak", ["has", "name"], 8, zi((8, 10), (14, 12)), "#7a5a3a",
      anchor="top", offset=[0, 0.5], sort=["-", 0, ["to-number", ["get", "ele"], 0]], width=7)
label("lbl-poi", "poi", ["has", "name"], 15, 11, "#5b6574", anchor="top", offset=[0, 0.6], width=7)
label("lbl-aerodrome", "aerodrome_label", ["has", "name"], 8, 11, "#556070", anchor="top", offset=[0, 0.6])

label("lbl-hamlet", "place", cls("hamlet", "neighbourhood", "isolated_dwelling"), 13,
      zi((13, 10), (16, 13)), "#666666", sort=RANK, width=7)
label("lbl-suburb", "place", cls("suburb", "quarter"), 11, zi((11, 10), (15, 14)), "#555555", sort=RANK)
label("lbl-village", "place", cls("village"), 10, zi((10, 10), (14, 13)), "#444444", sort=RANK, width=7)
label("lbl-town", "place", cls("town"), 8, zi((8, 11), (12, 15)), "#333333", sort=RANK)
label("lbl-city", "place", cls("city"), 4, zi((4, 12), (8, 16), (12, 20)), "#222222", sort=RANK)
label("lbl-island", "place", cls("island"), 9, zi((9, 10), (13, 13)), "#5a6b4a", sort=RANK)
label("lbl-state", "place", cls("state", "province"), 4, zi((4, 10), (8, 14)), "#7b6f93", maxzoom=10, sort=RANK, width=7)
label("lbl-country", "place", cls("country"), 2, zi((2, 11), (5, 15), (8, 22)), "#4a4458", maxzoom=9, sort=RANK, width=7)

style = {
    "version": 8,
    "name": "Georgia Offline",
    "sources": {
        SRC: {
            "type": "vector",
            "url": "__MBTILES_URL__",
            "attribution": "© OpenStreetMap contributors, © OpenMapTiles, © Natural Earth",
        }
    },
    "glyphs": "asset://glyphs/{fontstack}/{range}.pbf",
    "layers": L,
}

out = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/assets/style.json"
with open(out, "w", encoding="utf-8") as f:
    json.dump(style, f, ensure_ascii=False, indent=1)
print("wrote", out, len(L), "layers")
