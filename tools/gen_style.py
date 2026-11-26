#!/usr/bin/env python3
"""Generates the map style (OpenMapTiles schema, fully offline).
Usage: gen_style.py <out.json> [--dark] [--palette=ocean|forest|dusk|lavender|graphite|contrast|neon]
"Ocean" is the base look; the other palettes re-colour it (vegetation, water, warm tones, neutrals) so the map
matches the colour theme chosen in the app."""
import colorsys, json, re, sys

DARK = "--dark" in sys.argv
PALETTE = next((a.split("=", 1)[1] for a in sys.argv if a.startswith("--palette=")), "ocean")
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
layer("background", "background", paint={"background-color": c("#eef0ea", "#14181d")})

# landcover: class first, then subclass details (vineyards, orchards, scrub, scree ...)
LC = {"grass": ("#dfeacd", "#1f2b24"), "wood": ("#c4dcb0", "#1a2a21"), "farmland": ("#ecefd6", "#232a25"),
      "ice": ("#f4f9fd", "#33404e"), "rock": ("#dcd8d0", "#2d2f31"), "sand": ("#f1e8c8", "#34322a"),
      "wetland": ("#d3e6dc", "#1d2e2b")}
for k, (lt, dk) in LC.items():
    layer(f"landcover-{k}", "fill", "landcover", ["==", ["get", "class"], k],
          paint={"fill-color": c(lt, dk), "fill-opacity": 0.92, "fill-antialias": False})


def sub(id, names, light, dark, minzoom=10, opacity=0.95):
    layer(id, "fill", "landcover", ["match", ["get", "subclass"], list(names), True, False], minzoom=minzoom,
          paint={"fill-color": c(light, dark), "fill-opacity": opacity})


sub("landcover-meadow", ["meadow", "grassland", "grass"], "#d8e8c0", "#22302a")
sub("landcover-scrub", ["scrub", "heath", "shrubbery", "fell", "tundra"], "#cddcb0", "#1f2c24")
sub("landcover-vineyard", ["vineyard"], "#dfe3b4", "#2b3022", 11)
sub("landcover-orchard", ["orchard", "plant_nursery", "garden"], "#d4e4b0", "#232f22", 11)
sub("landcover-bare-rock", ["bare_rock", "scree", "rock", "cliff", "stone"], "#d6d1c7", "#34363a", 9)
sub("landcover-glacier", ["glacier", "ice"], "#f6fbff", "#3a4858", 6)

layer("landuse-residential", "fill", "landuse", cls("residential", "suburb", "neighbourhood"), minzoom=6,
      paint={"fill-color": c("#e6e3dc", "#1f242a"), "fill-opacity": zi((6, 0.4), (12, 0.95))})
layer("landuse-commercial", "fill", "landuse", cls("commercial", "retail"), minzoom=10,
      paint={"fill-color": c("#efe0d6", "#2a2523")})
layer("landuse-industrial", "fill", "landuse", cls("industrial", "railway", "quarry", "dam"), minzoom=10,
      paint={"fill-color": c("#e1dfda", "#25282b")})
layer("landuse-military", "fill", "landuse", cls("military"), minzoom=9,
      paint={"fill-color": c("#e6dcd9", "#2b2626"), "fill-opacity": 0.8})
layer("landuse-green", "fill", "landuse",
      cls("cemetery", "pitch", "playground", "stadium", "garden", "track", "theme_park", "zoo"),
      minzoom=11, paint={"fill-color": c("#d2e5c2", "#22322a")})
layer("landuse-institution", "fill", "landuse", cls("school", "university", "college", "kindergarten", "library"),
      minzoom=12, paint={"fill-color": c("#ebe3d3", "#292925")})
layer("landuse-hospital", "fill", "landuse", cls("hospital"), minzoom=12,
      paint={"fill-color": c("#f3dcdc", "#33262a")})
layer("landuse-station", "fill", "landuse", cls("bus_station"), minzoom=13,
      paint={"fill-color": c("#e7e3dc", "#262a30")})

layer("park", "fill", "park", minzoom=5,
      paint={"fill-color": c("#bfdca6", "#25382b"), "fill-opacity": zi((5, 0.22), (10, 0.42), base=1)})
layer("park-outline", "line", "park", minzoom=8, layout={"line-join": "round"},
      paint={"line-color": c("#86b872", "#4f7a58"), "line-width": zi((8, 0.6), (14, 1.4)),
             "line-dasharray": [4, 2]})

# relief shading (raster-dem built from terrain tiles); removed at runtime if relief.mbtiles is absent.
# Softer than before: low exaggeration at close zoom so the noise of the DEM stays out of the picture.
layer("relief-hillshade", "hillshade", source="relief",
      paint={"hillshade-exaggeration": zi((5, 0.34), (9, 0.26), (12, 0.14), (14, 0.08), base=1),
             "hillshade-illumination-direction": 315,
             "hillshade-shadow-color": c("#6f6a5c", "#000000"),
             "hillshade-highlight-color": c("#ffffff", "#3a4350"),
             "hillshade-accent-color": c("#8d8774", "#000000")})

# ---- water. Adjacent OSM water polygons (reservoirs, fish ponds, split lakes) leave hairline gaps; the
# fill is drawn without anti-aliasing and a same-coloured stroke on top closes every seam, so a pond
# complex reads as one body of water instead of a chequerboard.
WATER = c("#a6cbf0", "#10253d")
layer("water", "fill", "water", paint={"fill-color": WATER, "fill-antialias": False})
layer("water-seam", "line", "water", minzoom=7, layout={"line-join": "round", "line-cap": "round"},
      paint={"line-color": WATER, "line-width": zi((7, 0.8), (11, 2.2), (13, 3.2), (15, 4.5), (18, 8), base=1.3)})
layer("waterway-river", "line", "waterway", ["==", ["get", "class"], "river"], minzoom=8,
      layout={"line-cap": "round", "line-join": "round"},
      paint={"line-color": WATER, "line-width": zi((8, 0.6), (12, 1.8), (14, 3.2), (18, 14))})
layer("waterway-minor", "line", "waterway", cls("stream", "canal", "drain", "ditch"), minzoom=11,
      layout={"line-cap": "round", "line-join": "round"},
      paint={"line-color": WATER, "line-width": zi((11, 0.4), (14, 1.2), (16, 2.2), (18, 4.5))})

# ---- elevation contours (горизонтали): own vector source (contours.mbtiles), removed at runtime when the file is
# absent, switched on/off together with the peak marks by the "Высоты" layer in the app (see MainActivity.applyHeights).
# Tile zoom 12 carries a 20 m grid, so between map zoom 12 and 13 only the 100 m lines are drawn (minor-lo stops at 12,
# minor-hi starts at 13); below 12 every line of the tile is meant to be seen.
CONTOUR = "contours"
CT_MINOR = c("#b3916c", "#8a7255")
CT_MAJOR = c("#8c6743", "#b8956b")
NOT_MAJOR = ["!=", ["get", "major"], 1]
IS_MAJOR = ["==", ["get", "major"], 1]
layer("contour-minor-lo", "line", "contour", NOT_MAJOR, minzoom=8, maxzoom=12, source=CONTOUR,
      layout={"line-join": "round", "line-cap": "round"},
      paint={"line-color": CT_MINOR, "line-opacity": 0.7, "line-width": zi((8, 0.4), (11.9, 0.8), base=1)})
layer("contour-minor-hi", "line", "contour", NOT_MAJOR, minzoom=13, source=CONTOUR,
      layout={"line-join": "round", "line-cap": "round"},
      paint={"line-color": CT_MINOR, "line-opacity": 0.75, "line-width": zi((13, 0.6), (16, 0.9), (18, 1.3), base=1)})
layer("contour-major", "line", "contour", IS_MAJOR, minzoom=8, source=CONTOUR,
      layout={"line-join": "round", "line-cap": "round"},
      paint={"line-color": CT_MAJOR, "line-opacity": 0.9, "line-width": zi((8, 0.7), (12, 1.2), (16, 1.9), (18, 2.4), base=1)})

layer("aeroway-area", "fill", "aeroway", ["==", ["geometry-type"], "Polygon"], minzoom=11,
      paint={"fill-color": c("#e3e0da", "#262a2f")})
layer("aeroway-runway", "line", "aeroway", ["all", ["==", ["geometry-type"], "LineString"], cls("runway")],
      minzoom=10, paint={"line-color": c("#cfcbc3", "#3a3e44"), "line-width": zi((10, 1), (14, 6), (18, 40))})
layer("aeroway-taxiway", "line", "aeroway", ["all", ["==", ["geometry-type"], "LineString"], cls("taxiway")],
      minzoom=12, paint={"line-color": c("#d6d2ca", "#33373c"), "line-width": zi((12, 0.5), (16, 3), (18, 10))})

layer("building", "fill", "building", minzoom=13,
      paint={"fill-color": c("#d9d3c7", "#2a2f36"), "fill-opacity": zi((13, 0), (14.5, 1), base=1)})
layer("building-outline", "line", "building", minzoom=15,
      paint={"line-color": c("#c4bdaf", "#3b414a"), "line-width": 0.5})

# ------------------------------------------------------------------ roads
LINE = {"line-cap": "round", "line-join": "round"}
BUTT = {"line-cap": "butt", "line-join": "round"}
NOTUNNEL = ["!=", ["get", "brunnel"], "tunnel"]
BRIDGE = ["==", ["get", "brunnel"], "bridge"]
TUNNEL = ["==", ["get", "brunnel"], "tunnel"]
RAMP = ["==", ["get", "ramp"], 1]


def rd(cl, extra=None):
    f = cls(*cl)
    return ["all", f, extra] if extra is not None else f


ROADS = [  # name, classes, minzoom, fill(light,dark), casing(light,dark), width stops
    ("service", ["service"], 13, ("#ffffff", "#3b414a"), ("#d3cdc1", "#12151a"), [(13, 0.8), (16, 3), (18, 9)]),
    ("minor", ["minor"], 11, ("#ffffff", "#464d57"), ("#d0cabe", "#12151a"), [(11, 0.6), (14, 2.5), (16, 7), (18, 22)]),
    ("tertiary", ["tertiary"], 9, ("#ffffff", "#58606b"), ("#cbc4b7", "#12151a"),
     [(9, 0.6), (12, 1.8), (14, 4), (16, 10), (18, 28)]),
    ("secondary", ["secondary"], 7, ("#fff0b5", "#80703f"), ("#d6c98f", "#251f10"),
     [(7, 0.6), (12, 2.2), (14, 5), (16, 12), (18, 32)]),
    ("primary", ["primary"], 5, ("#fdd98a", "#a2843f"), ("#d4b46c", "#251f10"),
     [(5, 0.5), (10, 1.6), (12, 3), (14, 6), (16, 13), (18, 34)]),
    ("trunk", ["trunk"], 4, ("#fbc577", "#b98640"), ("#d19f50", "#251f10"),
     [(4, 0.5), (10, 1.8), (12, 3.2), (14, 6.5), (16, 14), (18, 36)]),
    ("motorway", ["motorway"], 4, ("#f4a56b", "#c4753d"), ("#c97c3d", "#241407"),
     [(4, 0.6), (10, 2), (12, 3.5), (14, 7), (16, 15), (18, 38)]),
]

# tunnels first (under everything): pale dashed casing so they read as "underground"
for name, classes, mz, fill, casing, st in ROADS:
    layer(f"road-{name}-tunnel", "line", "transportation", rd(classes, TUNNEL), minzoom=mz, layout=BUTT,
          paint={"line-color": c(*casing), "line-opacity": 0.7, "line-dasharray": [2, 1.5],
                 "line-width": zi(*[(z, round(w * 1.1 + 0.4, 2)) for z, w in st])})
for name, classes, mz, fill, casing, st in ROADS:
    layer(f"road-{name}-casing", "line", "transportation", rd(classes, NOTUNNEL), minzoom=mz, layout=LINE,
          paint={"line-color": c(*casing),
                 "line-width": zi(*[(z, round(w * 1.35 + 0.7, 2)) for z, w in st])})
# bridges: dark outer casing, drawn over the normal casing
for name, classes, mz, fill, casing, st in ROADS:
    if mz > 14:
        continue
    layer(f"road-{name}-bridge", "line", "transportation", rd(classes, BRIDGE), minzoom=max(mz, 12), layout=BUTT,
          paint={"line-color": c("#8d877b", "#05070a"),
                 "line-width": zi(*[(z, round(w * 1.35 + 2.2, 2)) for z, w in st])})

# unpaved/track/path with surface hint
UNPAVED = ["match", ["get", "surface"], ["unpaved", "gravel", "dirt", "ground", "sand", "grass", "mud"], True, False]
layer("road-track", "line", "transportation", cls("track"), minzoom=11, layout=LINE,
      paint={"line-color": c("#a98b58", "#90805a"), "line-dasharray": [3, 1.5],
             "line-width": zi((11, 0.5), (14, 1.2), (16, 1.8), (18, 3.2))})
layer("road-path", "line", "transportation", cls("path"), minzoom=12, layout=LINE,
      paint={"line-color": c("#b0603a", "#c98a62"), "line-dasharray": [1.2, 1.4],
             "line-width": zi((12, 0.6), (15, 1.3), (18, 2.6))})
layer("road-pedestrian", "line", "transportation", cls("pedestrian"), minzoom=13, layout=LINE,
      paint={"line-color": c("#ffffff", "#4a515b"), "line-width": zi((13, 0.8), (16, 3.5), (18, 9))})
for name, classes, mz, fill, casing, st in ROADS:
    layer(f"road-{name}", "line", "transportation", rd(classes, NOTUNNEL), minzoom=mz, layout=LINE,
          paint={"line-color": c(*fill), "line-width": zi(*st)})
# unpaved tertiary/minor roads get a dashed lighter centre so gravel roads are distinguishable
layer("road-unpaved", "line", "transportation",
      ["all", cls("tertiary", "minor", "service", "secondary"), UNPAVED, NOTUNNEL], minzoom=12, layout=BUTT,
      paint={"line-color": c("#c9bfa8", "#2b3036"), "line-dasharray": [2.5, 2.5], "line-opacity": 0.9,
             "line-width": zi((12, 0.5), (15, 1.6), (18, 5))})
ONEWAY_CLS = cls("minor", "tertiary", "secondary", "primary", "service")
for _id, _val, _txt in (("road-oneway", 1, ">"), ("road-oneway-rev", -1, "<")):
    layer(_id, "symbol", "transportation", ["all", ["==", ["get", "oneway"], _val], ONEWAY_CLS],
          minzoom=16, source="openmaptiles-labels",
          layout={"symbol-placement": "line", "symbol-spacing": 150, "text-field": _txt,
                  "text-font": FONT, "text-size": 12, "text-keep-upright": False,
                  "text-rotation-alignment": "map"},
          paint={"text-color": c("#8a8a92", "#9aa0aa")})
HAS_SERVICE = ["has", "service"]
layer("rail", "line", "transportation", ["all", cls("rail"), ["!", HAS_SERVICE]], minzoom=8,
      layout={"line-join": "round"},
      paint={"line-color": c("#8a8a98", "#6f7280"), "line-dasharray": [4, 3],
             "line-width": zi((8, 0.6), (14, 1.2), (18, 2))})
# yards, sidings and spurs: only when zoomed in, thinner, so they do not clutter the main lines
layer("rail-service", "line", "transportation", ["all", cls("rail"), HAS_SERVICE], minzoom=13,
      layout={"line-join": "round"},
      paint={"line-color": c("#a9a9b6", "#575a66"), "line-dasharray": [3, 3],
             "line-width": zi((13, 0.4), (16, 0.9), (18, 1.4))})
layer("transit", "line", "transportation", cls("transit"), minzoom=11,
      paint={"line-color": c("#9a8fb5", "#7d7396"), "line-dasharray": [3, 2], "line-width": 1.1})
layer("aerialway", "line", "transportation", cls("aerialway", "gondola", "cable_car", "chair_lift", "drag_lift",
                                                  "mixed_lift", "t-bar", "j-bar", "platter", "rope_tow", "magic_carpet"),
      minzoom=10, layout=LINE,
      paint={"line-color": c("#6b5b95", "#a898d0"), "line-dasharray": [1, 2.2],
             "line-width": zi((10, 0.8), (14, 1.4), (18, 2.4))})
layer("pier", "line", "transportation", cls("pier"), minzoom=13,
      paint={"line-color": c("#d6d0c3", "#3a3f46"), "line-width": zi((13, 0.8), (18, 6))})
layer("ferry", "line", "transportation", cls("ferry"), minzoom=6,
      paint={"line-color": c("#5f93c9", "#4f80b4"), "line-dasharray": [3, 3], "line-width": 1.2})

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

# ------------------------------------------------------------------ marks
# Peaks, transport hubs, airports and POI are drawn as round icons (res/drawable/ic_cat_*.xml, made by gen_icons.py,
# added to the style by the app at start-up) with the name next to them: see the "lbl-*" symbol layers below.
def _in(prop, *vals):
    return ["match", ["get", prop], list(vals), True, False]


ST_RAIL = ["all", ["has", "name"], _in("subclass", "station", "halt"), ["==", ["get", "class"], "railway"]]
ST_SUBWAY = ["all", ["has", "name"], ["any", _in("subclass", "subway"), _in("class", "subway")]]
ST_TRAM = ["all", ["has", "name"], _in("subclass", "tram_stop"), ["==", ["get", "class"], "railway"]]
ST_BUS = ["all", ["has", "name"], _in("subclass", "bus_station")]
ST_SEA = ["all", ["has", "name"], ["any", _in("subclass", "ferry_terminal"), _in("class", "ferry_terminal")]]
ST_AERIAL = ["all", ["has", "name"], ["==", ["get", "class"], "aerialway"], _in("subclass", "station")]
STATIONS = [  # id, filter, minzoom, label colour (light, dark), icon
    ("rail", ST_RAIL, 11, ("#c62828", "#ef7a7a"), "ic_cat_rail"),
    ("subway", ST_SUBWAY, 12, ("#6a1b9a", "#c08ae0"), "ic_cat_subway"),
    ("bus", ST_BUS, 12, ("#2e7d32", "#7fcf84"), "ic_cat_busstation"),
    ("sea", ST_SEA, 11, ("#0277bd", "#6cc0ee"), "ic_cat_ferry"),
    ("aerial", ST_AERIAL, 11, ("#6b5b95", "#a898d0"), "ic_cat_aerial"),
    ("tram", ST_TRAM, 13, ("#ef6c00", "#f2a65a"), "ic_cat_tram"),
]

# ------------------------------------------------------------------ labels
# Symbol layers whose id starts with "lbl-" get their text-field replaced at runtime (LabelExpr.kt).
HALO = c("rgba(255,255,255,0.92)", "rgba(16,19,24,0.9)")
WHALO = c("rgba(196,222,246,0.92)", "rgba(10,26,44,0.9)")


ICON_SIZE = zi((9, 0.5), (13, 0.68), (16, 0.82), (18, 0.9), base=1)


def label(id, sl, filter, minzoom, size, color, halo=None, maxzoom=None, line=False, anchor=None,
          offset=None, sort=None, width=8, halo_w=1.9, spacing=None, icon=None, isize=None):
    lo = {"text-field": ["to-string", ["get", "name"]], "text-font": FONT, "text-size": size,
          "text-max-width": width, "text-padding": 2, "text-line-height": 1.25}
    if line:
        lo.update({"symbol-placement": "line", "symbol-spacing": 280, "text-max-angle": 35})
    if anchor:
        lo["text-anchor"] = anchor
    if offset:
        lo["text-offset"] = offset
    if sort is not None:
        lo["symbol-sort-key"] = sort
    if spacing:
        lo["text-letter-spacing"] = spacing
    if icon:      # icon + name in ONE symbol layer: the name sits under its icon, and a crowded name never hides the icon
        lo.update({"icon-image": icon, "icon-size": isize or ICON_SIZE, "icon-padding": 2, "icon-allow-overlap": False,
                   "text-optional": True})
    layer(id, "symbol", sl, filter, minzoom, maxzoom, layout=lo, source="openmaptiles-labels",
          paint={"text-color": color, "text-halo-color": halo or HALO, "text-halo-width": halo_w})


RANK = ["to-number", ["get", "rank"], 99]
REF = ["any", ["has", "name"], ["has", "ref"]]
BLUE = c("#35739f", "#7db4e6")

label("lbl-water", "water_name", ["has", "name"], 0, zi((0, 10), (8, 12), (14, 14)), BLUE, halo=WHALO)
label("lbl-waterway", "waterway", ["all", ["has", "name"], cls("river")], 10, zi((10, 10), (16, 13)), BLUE,
      halo=WHALO, line=True)
label("lbl-road-major", "transportation_name", ["all", REF, cls("motorway", "trunk", "primary", "secondary")], 9,
      zi((9, 10), (16, 13)), c("#4a4a4a", "#d0d0d0"), line=True)
label("lbl-road-minor", "transportation_name",
      ["all", REF, cls("tertiary", "minor", "service", "track", "path")], 13,
      zi((13, 10), (17, 12)), c("#555555", "#bbbbbb"), line=True)
# peaks: round mountain icon + "Name\n629 m" (the elevation is appended in LabelExpr.kt); part of the "Высоты" layer
label("lbl-peak", "mountain_peak", ["has", "name"], 8, zi((8, 10), (14, 12)), c("#7a5a3a", "#d3a574"),
      anchor="top", offset=[0, 1.15], sort=["-", 0, ["to-number", ["get", "ele"], 0]], width=7,
      icon="ic_cat_peak", isize=zi((8, 0.45), (12, 0.6), (16, 0.75), base=1))
# poi: the icon (by category) and the filter are set by the app (MainActivity.applyPoiFilter); the name shows from 14.5
label("lbl-poi", "poi", ["has", "name"], 15, ["step", ["zoom"], 0, 14.5, 11], c("#5b6574", "#aab6c8"), anchor="top",
      offset=[0, 1.45], width=7, sort=["to-number", ["get", "rank"], 99], icon="ic_cat_dot")
for sid, flt, mz, col, icon in STATIONS:
    label(f"lbl-station-{sid}", "poi", flt, mz, ["step", ["zoom"], 0, mz + 1.5, 11], c(*col[:2]), anchor="top",
          offset=[0, 1.45], width=7, sort=0, icon=icon)
# airports (aerodrome_label): big icon from zoom 8 for the real airports, small airfields from 11
AIR_MAJOR = ["all", ["has", "name"], _in("class", "international", "public", "regional")]
AIR_MINOR = ["all", ["has", "name"], ["!", _in("class", "international", "public", "regional")]]
label("lbl-aerodrome", "aerodrome_label", AIR_MAJOR, 6, ["step", ["zoom"], 0, 8, 12], c("#00695c", "#6fcfc2"),
      anchor="top", offset=[0, 1.6], icon="ic_cat_airport", isize=zi((6, 0.6), (10, 0.85), (14, 1.0), base=1), sort=0)
label("lbl-aerodrome-minor", "aerodrome_label", AIR_MINOR, 11, ["step", ["zoom"], 0, 12, 11], c("#00695c", "#6fcfc2"),
      anchor="top", offset=[0, 1.45], icon="ic_cat_airport", isize=zi((11, 0.6), (14, 0.8), base=1), sort=1)
# elevation numbers along the thick contour lines (own source: glyph problems must not hide the lines themselves)
layer("contour-label", "symbol", "contour", IS_MAJOR, minzoom=11, source="contours-labels",
      layout={"symbol-placement": "line", "symbol-spacing": 320, "text-field": ["to-string", ["get", "ele"]],
              "text-font": FONT, "text-size": zi((11, 9), (16, 11), base=1), "text-max-angle": 30, "text-padding": 6},
      paint={"text-color": c("#7a5a3a", "#d3a574"), "text-halo-color": HALO, "text-halo-width": 1.6})
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
      maxzoom=10, sort=RANK, width=7, spacing=0.08)
label("lbl-country", "place", cls("country"), 2, zi((2, 11), (5, 15), (8, 22)), c("#4a4458", "#d6cfe6"),
      maxzoom=9, sort=RANK, width=7, spacing=0.12)


# ------------------------------------------------------------------ palettes
# hue families of the base style: vegetation 60-170, water 175-250, warm (roads, sand, commercial) 10-60.
# veg/water = (target hue, saturation multiplier); warm = (hue shift, saturation multiplier);
# neutral = (hue, tint strength) for greys; bg = (light, dark) background colour.
PALETTES = {
    "ocean": None,
    "forest": dict(veg=(108, 1.25), water=(198, 0.85), warm=(-2, 0.9), neutral=(95, 0.05),
                   bg=("#edf1e8", "#0f1a13")),
    "dusk": dict(veg=(74, 0.62), water=(194, 0.50), warm=(-5, 1.0), neutral=(32, 0.13),
                 bg=("#f3ede3", "#1b1311")),
    "lavender": dict(veg=(148, 0.55), water=(224, 0.85), warm=(4, 0.78), neutral=(268, 0.08),
                     bg=("#efedf4", "#17142a")),
    # high contrast: calm grey land, strong blue water, black/white text with a thick halo
    "contrast": dict(veg=(130, 0.30), water=(212, 1.0), warm=(0, 0.55), neutral=(0, 0.0), other=0.4,
                     bg=("#ffffff", "#000000"), labels=("#000000", "#ffffff")),
    # neon: deep blue ground, teal vegetation, cyan roads at night; pastel with vivid roads by day
    "neon": dict(veg=(165, 1.2), water=(225, 1.2), warm=(160, 1.0), neutral=(235, 0.10), other=1.2,
                 bg=("#eef0ff", "#070a16")),
    "graphite": dict(veg=(120, 0.20), water=(210, 0.55), warm=(0, 0.55), neutral=(215, 0.03), other=0.3,
                     bg=("#eceeef", "#101113")),
}
HEX = re.compile(r"^#([0-9a-fA-F]{6})$")
RGBA = re.compile(r"^rgba\((\d+),(\d+),(\d+),([\d.]+)\)$")


def _xform(r, g, b, pal):
    h, sat, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
    hd = h * 360
    cool_grey = 175 <= hd <= 250 and sat < 0.30 and v < 0.40     # dark-theme ground greys (bluish by default)
    if sat < 0.10 or cool_grey:                      # greys: tint towards the palette's neutral hue
        hd = pal["neutral"][0]
        sat = min(max(sat, pal["neutral"][1]), 0.10 + pal["neutral"][1]) * pal.get("other", 1.0)
    elif 60 <= hd <= 170:                            # vegetation
        hd = pal["veg"][0] + (hd - 110) * 0.35
        sat *= pal["veg"][1]
    elif 175 <= hd <= 250:                           # water
        hd = pal["water"][0] + (hd - 212) * 0.35
        sat *= pal["water"][1]
    elif 8 <= hd < 60:                               # warm: roads, sand, commercial
        hd += pal["warm"][0]
        sat *= pal["warm"][1]
    else:                                            # boundaries, labels, everything else
        sat *= pal.get("other", 1.0)
    r2, g2, b2 = colorsys.hsv_to_rgb((hd % 360) / 360, min(1.0, sat), v)
    return round(r2 * 255), round(g2 * 255), round(b2 * 255)


def recolor(node, pal):
    if isinstance(node, dict):
        return {k: recolor(v, pal) for k, v in node.items()}
    if isinstance(node, list):
        return [recolor(v, pal) for v in node]
    if isinstance(node, str):
        m = HEX.match(node)
        if m:
            h = m.group(1)
            return "#%02x%02x%02x" % _xform(int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), pal)
        m = RGBA.match(node.replace(" ", ""))
        if m:
            r, g, b = _xform(int(m.group(1)), int(m.group(2)), int(m.group(3)), pal)
            return "rgba(%d,%d,%d,%s)" % (r, g, b, m.group(4))
    return node


style = {
    "version": 8,
    "name": "Georgia Offline" + (" Dark" if DARK else ""),
    "sources": {
        SRC: {"type": "vector", "url": "__MBTILES_URL__",
              "attribution": "© OpenStreetMap contributors, © OpenMapTiles, © Natural Earth"},
        # same tiles, second source: symbol layers wait for glyphs; keeping them apart means a glyph
        # problem can never hide the roads/water/landcover of a tile
        "openmaptiles-labels": {"type": "vector", "url": "__MBTILES_URL__"},
        # elevation lines; the symbol (label) layer reads the same file through a second source, like the base tiles
        "contours": {"type": "vector", "url": "__CONTOURS_URL__"},
        "contours-labels": {"type": "vector", "url": "__CONTOURS_URL__"},
        "relief": {"type": "raster-dem", "url": "__RELIEF_URL__", "tileSize": 256, "encoding": "terrarium",
                   "attribution": "Terrain: Mapzen/AWS Terrain Tiles (SRTM, ASTER, others)"},
    },
    "glyphs": "asset://glyphs/{fontstack}/{range}.pbf",
    "layers": L,
}

pal = PALETTES.get(PALETTE)
if PALETTE not in PALETTES:
    sys.exit("unknown palette: " + PALETTE)
if pal:
    style = recolor(style, pal)
    for l in style["layers"]:                         # the page colour itself is set explicitly
        if l["id"] == "background":
            l["paint"]["background-color"] = pal["bg"][1 if DARK else 0]
    if "labels" in pal:                              # high-contrast labels
        ink, halo = pal["labels"][1 if DARK else 0], pal["labels"][0 if DARK else 1]
        for l in style["layers"]:
            pt = l.get("paint", {})
            if "text-color" in pt:
                pt["text-color"], pt["text-halo-color"] = ink, halo
                pt["text-halo-width"] = max(2.2, pt["text-halo-width"] if isinstance(pt.get("text-halo-width"), (int, float)) else 0)
    style["name"] = style["name"] + " " + PALETTE.capitalize()

out = args[0] if args else "app/src/main/assets/style_ocean.json"
with open(out, "w", encoding="utf-8") as f:
    json.dump(style, f, ensure_ascii=False, indent=1)
print("wrote", out, len(L), "layers", PALETTE, "(dark)" if DARK else "")
