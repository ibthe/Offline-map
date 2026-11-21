#!/usr/bin/env python3
"""Generates the map style (OpenMapTiles schema, fully offline).
Usage: gen_style.py <out.json> [--dark]"""
import json, sys

DARK = "--dark" in sys.argv
args = [a for a in sys.argv[1:] if not a.startswith("--")]
SRC = "openmaptiles"
FONT = ["NotoSans"]
L = []


def c(light, dark):
    return dark if DARK else light


def zi(*stops, base=1.4):
    out = ["interpolate", ["exponential", base], ["zoom"]]
    for z, v in stops:
        out += [z, v]
    return out


def cls(*names):
    return ["match", ["get", "class"], list(names), True, False]


def layer(id, type, sl=None, filter=None, minzoom=None, maxzoom=None, paint=None, layout=None, source=None):
    d = {"id": id, "type": type}
    if type != "background":
        d["source"] = source or SRC
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
layer("background", "background", paint={"background-color": c("#f3f0e8", "#1c1f24")})

LC = {"grass": ("#e2ecd0", "#26302a"), "wood": ("#cde1b6", "#223024"), "farmland": ("#eef0d8", "#2a2d27"),
      "ice": ("#f7fbfe", "#3a4350"), "rock": ("#ddd8cf", "#34322f"), "sand": ("#f2e9cb", "#3a362b"),
      "wetland": ("#d9e9dd", "#243229")}
for k, (lt, dk) in LC.items():
    layer(f"landcover-{k}", "fill", "landcover", ["==", ["get", "class"], k],
          paint={"fill-color": c(lt, dk), "fill-opacity": 0.9})

layer("landuse-residential", "fill", "landuse", cls("residential", "suburb", "neighbourhood"), minzoom=6,
      paint={"fill-color": c("#e9e5dc", "#22262b"), "fill-opacity": zi((6, 0.4), (12, 0.9))})
layer("landuse-commercial", "fill", "landuse", cls("commercial", "retail"), minzoom=10,
      paint={"fill-color": c("#efe0d6", "#2a2523")})
layer("landuse-industrial", "fill", "landuse", cls("industrial", "railway", "quarry"), minzoom=10,
      paint={"fill-color": c("#e3e0da", "#25272a")})
layer("landuse-green", "fill", "landuse", cls("cemetery", "pitch", "playground", "stadium", "garden", "track"),
      minzoom=11, paint={"fill-color": c("#d5e6c7", "#26332a")})
layer("landuse-institution", "fill", "landuse", cls("school", "university", "college", "kindergarten"), minzoom=12,
      paint={"fill-color": c("#ece4d2", "#2a2a26")})
layer("landuse-hospital", "fill", "landuse", cls("hospital"), minzoom=12,
      paint={"fill-color": c("#f3dcdc", "#33262a")})

layer("park", "fill", "park", minzoom=5,
      paint={"fill-color": c("#c9e2b0", "#2a3d2c"), "fill-opacity": zi((5, 0.25), (10, 0.5), base=1)})
layer("park-outline", "line", "park", minzoom=8, layout={"line-join": "round"},
      paint={"line-color": c("#8dbb78", "#4a6b4c"), "line-width": zi((8, 0.6), (14, 1.4)),
             "line-dasharray": [4, 2]})

# relief shading (raster-dem built from terrain tiles); removed at runtime if relief.mbtiles is absent
layer("relief-hillshade", "hillshade", source="relief",
      paint={"hillshade-exaggeration": 0.55, "hillshade-illumination-direction": 315,
             "hillshade-shadow-color": c("#4f4a3c", "#000000"),
             "hillshade-highlight-color": c("#ffffff", "#3a3f46"),
             "hillshade-accent-color": c("#6b6454", "#000000")})

WATER = c("#a9cdee", "#12243a")
layer("water", "fill", "water", paint={"fill-color": WATER})
layer("waterway-river", "line", "waterway", ["==", ["get", "class"], "river"], minzoom=8,
      layout={"line-cap": "round", "line-join": "round"},
      paint={"line-color": WATER, "line-width": zi((8, 0.5), (12, 1.6), (14, 3), (18, 12))})
layer("waterway-minor", "line", "waterway", cls("stream", "canal", "drain", "ditch"), minzoom=12,
      layout={"line-cap": "round", "line-join": "round"},
      paint={"line-color": WATER, "line-width": zi((12, 0.4), (16, 2), (18, 4))})

layer("aeroway-area", "fill", "aeroway", ["==", ["geometry-type"], "Polygon"], minzoom=11,
      paint={"fill-color": c("#e6e2db", "#2a2d31")})
layer("aeroway-runway", "line", "aeroway", ["all", ["==", ["geometry-type"], "LineString"], cls("runway")],
      minzoom=10, paint={"line-color": c("#d2cec6", "#3a3e44"), "line-width": zi((10, 1), (14, 6), (18, 40))})
layer("aeroway-taxiway", "line", "aeroway", ["all", ["==", ["geometry-type"], "LineString"], cls("taxiway")],
      minzoom=12, paint={"line-color": c("#d9d5cd", "#33373c"), "line-width": zi((12, 0.5), (16, 3), (18, 10))})

layer("building", "fill", "building", minzoom=13,
      paint={"fill-color": c("#dad4c8", "#2c3036"), "fill-opacity": zi((13, 0), (14.5, 1), base=1)})
layer("building-outline", "line", "building", minzoom=15,
      paint={"line-color": c("#c8c1b3", "#3a3f46"), "line-width": 0.5})

# ------------------------------------------------------------------ roads
LINE = {"line-cap": "round", "line-join": "round"}
ROADS = [  # name, classes, minzoom, fill(light,dark), casing(light,dark), width stops
    ("service", ["service"], 14, ("#ffffff", "#3a3f46"), ("#d0cabe", "#16191d"), [(14, 1), (16, 3), (18, 9)]),
    ("minor", ["minor"], 12, ("#ffffff", "#454b54"), ("#cfc9bd", "#16191d"), [(12, 0.8), (14, 2.5), (16, 7), (18, 22)]),
    ("tertiary", ["tertiary"], 10, ("#ffffff", "#555c66"), ("#c9c2b5", "#16191d"),
     [(10, 0.8), (12, 1.8), (14, 4), (16, 10), (18, 28)]),
    ("secondary", ["secondary"], 8, ("#fff1b8", "#7a6a3a"), ("#d4c68a", "#2a2412"),
     [(8, 0.8), (12, 2.2), (14, 5), (16, 12), (18, 32)]),
    ("primary", ["primary"], 6, ("#fddc8a", "#9b7f3c"), ("#d2b268", "#2a2412"),
     [(6, 0.6), (10, 1.6), (12, 3), (14, 6), (16, 13), (18, 34)]),
    ("trunk", ["trunk"], 5, ("#fbc975", "#b3823a"), ("#cf9c4c", "#2a2412"),
     [(5, 0.5), (10, 1.8), (12, 3.2), (14, 6.5), (16, 14), (18, 36)]),
    ("motorway", ["motorway"], 5, ("#f6a96b", "#c0713a"), ("#c9783a", "#2a1608"),
     [(5, 0.6), (10, 2), (12, 3.5), (14, 7), (16, 15), (18, 38)]),
]
for name, classes, mz, fill, casing, st in ROADS:
    layer(f"road-{name}-casing", "line", "transportation", cls(*classes), minzoom=mz, layout=LINE,
          paint={"line-color": c(*casing),
                 "line-width": zi(*[(z, round(w * 1.35 + 0.7, 2)) for z, w in st])})
layer("road-track", "line", "transportation", cls("track"), minzoom=12, layout=LINE,
      paint={"line-color": c("#b09462", "#8a7a55"), "line-dasharray": [3, 1.5],
             "line-width": zi((12, 0.6), (15, 1.4), (18, 3))})
layer("road-path", "line", "transportation", cls("path"), minzoom=12, layout=LINE,
      paint={"line-color": c("#a0744a", "#b08860"), "line-dasharray": [1.5, 1.5],
             "line-width": zi((12, 0.6), (15, 1.2), (18, 2.4))})
for name, classes, mz, fill, casing, st in ROADS:
    layer(f"road-{name}", "line", "transportation", cls(*classes), minzoom=mz, layout=LINE,
          paint={"line-color": c(*fill), "line-width": zi(*st)})
layer("rail", "line", "transportation", cls("rail"), minzoom=8, layout={"line-join": "round"},
      paint={"line-color": c("#8c8c98", "#6c6c78"), "line-dasharray": [4, 3],
             "line-width": zi((8, 0.6), (14, 1.2), (18, 2))})
layer("ferry", "line", "transportation", cls("ferry"), minzoom=6,
      paint={"line-color": c("#6f9fcf", "#4f7fb0"), "line-dasharray": [3, 3], "line-width": 1.2})

# ------------------------------------------------------------------ boundaries
NOMARITIME = ["!=", ["get", "maritime"], 1]
B2 = c("#7a6e92", "#a99cc6")
layer("boundary-2", "line", "boundary",
      ["all", ["==", ["get", "admin_level"], 2], NOMARITIME, ["!=", ["get", "disputed"], 1]],
      layout={"line-join": "round"},
      paint={"line-color": B2, "line-width": zi((2, 0.6), (6, 1.4), (10, 2.4))})
layer("boundary-2-disputed", "line", "boundary",
      ["all", ["==", ["get", "admin_level"], 2], NOMARITIME, ["==", ["get", "disputed"], 1]],
      paint={"line-color": B2, "line-dasharray": [3, 2], "line-width": zi((2, 0.6), (6, 1.4), (10, 2.4))})
layer("boundary-4", "line", "boundary", ["all", ["==", ["get", "admin_level"], 4], NOMARITIME], minzoom=5,
      paint={"line-color": c("#a898bf", "#7d7096"), "line-dasharray": [3, 2],
             "line-width": zi((5, 0.6), (10, 1.6))})
layer("boundary-6", "line", "boundary", ["all", ["==", ["get", "admin_level"], 6], NOMARITIME], minzoom=9,
      paint={"line-color": c("#c3b9d2", "#5d5470"), "line-dasharray": [2, 2], "line-width": 0.9})

# ------------------------------------------------------------------ dots
layer("peak-dot", "circle", "mountain_peak", ["has", "name"], minzoom=8,
      paint={"circle-radius": 2.6, "circle-color": c("#8c6a4a", "#c49a6c"), "circle-stroke-width": 0.8,
             "circle-stroke-color": c("#ffffff", "#1c1f24")})
layer("poi-dot", "circle", "poi", ["has", "name"], minzoom=15,
      paint={"circle-radius": 3, "circle-color": c("#8793a5", "#9aa7bb"), "circle-stroke-width": 1,
             "circle-stroke-color": c("#ffffff", "#1c1f24")})

# ------------------------------------------------------------------ labels
# Symbol layers whose id starts with "lbl-" get their text-field replaced at runtime (LabelExpr.kt).
HALO = c("rgba(255,255,255,0.9)", "rgba(20,22,26,0.88)")
WHALO = c("rgba(200,226,246,0.9)", "rgba(10,24,40,0.88)")


def label(id, sl, filter, minzoom, size, color, halo=None, maxzoom=None, line=False, anchor=None,
          offset=None, sort=None, width=8, halo_w=1.5):
    lo = {"text-field": ["to-string", ["get", "name"]], "text-font": FONT, "text-size": size,
          "text-max-width": width, "text-padding": 2}
    if line:
        lo.update({"symbol-placement": "line", "symbol-spacing": 280, "text-max-angle": 35})
    if anchor:
        lo["text-anchor"] = anchor
    if offset:
        lo["text-offset"] = offset
    if sort is not None:
        lo["symbol-sort-key"] = sort
    layer(id, "symbol", sl, filter, minzoom, maxzoom, layout=lo, source="openmaptiles-labels",
          paint={"text-color": color, "text-halo-color": halo or HALO, "text-halo-width": halo_w})


RANK = ["to-number", ["get", "rank"], 99]
REF = ["any", ["has", "name"], ["has", "ref"]]
BLUE = c("#3f7fb5", "#7fb2e0")

label("lbl-water", "water_name", ["has", "name"], 0, zi((0, 10), (8, 12), (14, 14)), BLUE, halo=WHALO)
label("lbl-waterway", "waterway", ["all", ["has", "name"], cls("river")], 10, zi((10, 10), (16, 13)), BLUE,
      halo=WHALO, line=True)
label("lbl-road-major", "transportation_name", ["all", REF, cls("motorway", "trunk", "primary", "secondary")], 9,
      zi((9, 10), (16, 13)), c("#4a4a4a", "#d0d0d0"), line=True)
label("lbl-road-minor", "transportation_name",
      ["all", REF, cls("tertiary", "minor", "service", "track", "path")], 13,
      zi((13, 10), (17, 12)), c("#555555", "#bbbbbb"), line=True)
label("lbl-peak", "mountain_peak", ["has", "name"], 8, zi((8, 10), (14, 12)), c("#7a5a3a", "#d3a574"),
      anchor="top", offset=[0, 0.5], sort=["-", 0, ["to-number", ["get", "ele"], 0]], width=7)
label("lbl-poi", "poi", ["has", "name"], 15, 11, c("#5b6574", "#aab6c8"), anchor="top", offset=[0, 0.6], width=7)
label("lbl-aerodrome", "aerodrome_label", ["has", "name"], 8, 11, c("#556070", "#a0acc0"), anchor="top",
      offset=[0, 0.6])
label("lbl-hamlet", "place", cls("hamlet", "neighbourhood", "isolated_dwelling"), 13, zi((13, 10), (16, 13)),
      c("#666666", "#b5b5b5"), sort=RANK, width=7)
label("lbl-suburb", "place", cls("suburb", "quarter"), 11, zi((11, 10), (15, 14)), c("#555555", "#c0c0c0"),
      sort=RANK)
label("lbl-village", "place", cls("village"), 10, zi((10, 10), (14, 13)), c("#444444", "#cfcfcf"), sort=RANK,
      width=7)
label("lbl-town", "place", cls("town"), 8, zi((8, 11), (12, 15)), c("#333333", "#dedede"), sort=RANK)
label("lbl-city", "place", cls("city"), 4, zi((4, 12), (8, 16), (12, 20)), c("#222222", "#f0f0f0"), sort=RANK)
label("lbl-island", "place", cls("island"), 9, zi((9, 10), (13, 13)), c("#5a6b4a", "#a3b894"), sort=RANK)
label("lbl-state", "place", cls("state", "province"), 4, zi((4, 10), (8, 14)), c("#7b6f93", "#b3a6cf"),
      maxzoom=10, sort=RANK, width=7)
label("lbl-country", "place", cls("country"), 2, zi((2, 11), (5, 15), (8, 22)), c("#4a4458", "#d6cfe6"),
      maxzoom=9, sort=RANK, width=7)

style = {
    "version": 8,
    "name": "Georgia Offline" + (" Dark" if DARK else ""),
    "sources": {
        SRC: {"type": "vector", "url": "__MBTILES_URL__",
              "attribution": "© OpenStreetMap contributors, © OpenMapTiles, © Natural Earth"},
        # same tiles, second source: symbol layers wait for glyphs; keeping them apart means a glyph
        # problem can never hide the roads/water/landcover of a tile
        "openmaptiles-labels": {"type": "vector", "url": "__MBTILES_URL__"},
        "relief": {"type": "raster-dem", "url": "__RELIEF_URL__", "tileSize": 256, "encoding": "terrarium",
                   "attribution": "Terrain: Mapzen/AWS Terrain Tiles (SRTM, ASTER, others)"},
    },
    "glyphs": "asset://glyphs/{fontstack}/{range}.pbf",
    "layers": L,
}

out = args[0] if args else "app/src/main/assets/style.json"
with open(out, "w", encoding="utf-8") as f:
    json.dump(style, f, ensure_ascii=False, indent=1)
print("wrote", out, len(L), "layers", "(dark)" if DARK else "")
