# tools/

| file | what it does |
|---|---|
| `gen_style.py` | writes `style_<palette>[_dark].json` (colors, roads, labels); `--palette=ocean|forest|dusk|lavender|graphite` re-colours the base look |
| `build_glyphs.py` | builds the label font (SDF `.pbf`) for Latin + Cyrillic + Georgian: tries GitHub glyph repos / TTF fonts / system fonts, judges every candidate by its number of real glyphs (not by file size) and fails the build if `თბილისი Тбилиси Tbilisi` cannot be drawn |
| `build_relief.py` | builds `relief.mbtiles` (Terrarium elevation tiles) for a requested region; `--bbox=west,south,east,north`, `--maxzoom` (0–11) |
| `build_contours.py` | builds `contours.mbtiles` (vector contour lines, layer `contour`, attributes `ele`, `major`) from the Terrarium DEM; `--relief`, `--like`, `--bbox`; needs `pip install numpy scipy pillow contourpy` |
| `gen_icons.py` | writes the map-marker icons `res/drawable/ic_cat_*.xml` (badge + white glyph) and optionally a PNG preview: `python gen_icons.py <drawable dir> preview.png` |
| `check_tiles.py` | fails the build if the tiles have no `name:ka` / `name:ru` / English names |

If `check_tiles.py` complains about missing names, add a language list to the Planetiler
call in `.github/workflows/build.yml` (see the Planetiler OpenMapTiles docs for the exact
option name in your version), e.g. `--languages=ka,ru,en`.

Wikidata translations (more names in ru/en for villages and POIs) can be enabled with
Planetiler's `--fetch-wikidata` option; this makes the tile build much slower.

## Fonts (labels)

`build_glyphs.py` never trusts file sizes: a font host may return a tiny placeholder for every range.
Each source is scored by the number of glyphs with ink in the Latin / Cyrillic / Georgian ranges.
To force your own font, drop `NotoSans-Regular.ttf` and `NotoSansGeorgian-Regular.ttf` (any TTF/OTF that
covers the scripts) into `tools/fonts/` - they are used before anything is downloaded.
In the app: Menu -> Diagnostics shows the glyph count per script (a mark means OK, a cross means placeholder).

| `build_routing.py` | builds `routing.bin` (road graph for the offline navigator) from the OSM extract; needs `pip install osmium numpy` |
| `tests/` | synthetic OSM map + Kotlin check program for the router; `make_test_dem.py` makes a fake relief.mbtiles to try `build_contours.py` offline |

`planetiler_external_data_revision.txt` is a manual cache epoch for datasets fetched by Planetiler's `--download` option. Bump it when those upstream inputs or their selection change, so dependent tiles, search and contour caches are rebuilt.


## Regional terrain packages

The **Build Regional Map Package** workflow accepts bounds in south/west/north/east order.
It converts them to west/south/east/north for the terrain tools, builds `relief.mbtiles`
with `build_relief.py`, then builds `contours.mbtiles` with `build_contours.py --like`
so contour tiles are limited to the actual vector-map coverage. Both assets are included
in the resulting `.gmap`. Terrain coverage depends on the upstream Terrarium DEM; the
profile must report missing samples rather than treating them as sea level or zero height.
