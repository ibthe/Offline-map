# tools/

| file | what it does |
|---|---|
| `gen_style.py` | writes `app/src/main/assets/style.json` (colors, roads, labels) |
| `build_glyphs.py` | downloads label fonts (SDF `.pbf`) into assets at build time |
| `check_tiles.py` | fails the build if the tiles have no `name:ka` / `name:ru` / English names |

If `check_tiles.py` complains about missing names, add a language list to the Planetiler
call in `.github/workflows/build.yml` (see the Planetiler OpenMapTiles docs for the exact
option name in your version), e.g. `--languages=ka,ru,en`.

Wikidata translations (more names in ru/en for villages and POIs) can be enabled with
Planetiler's `--fetch-wikidata` option; this makes the tile build much slower.
