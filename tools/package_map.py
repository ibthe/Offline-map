#!/usr/bin/env python3
"""Pack prepared regional map assets into an importable .gmap archive.

Example:
  python tools/package_map.py --id armenia --title "Армения" --country-code AM \
    --bounds 38.8 43.4 41.3 46.7 --tiles tiles.mbtiles --places places.db \
    --routing routing.bin --output armenia.gmap
"""
import argparse
import re
import zipfile
from pathlib import Path

SQLITE_HEADER = b"SQLite format 3\x00"
ASSETS = (("tiles", "tiles.mbtiles", True), ("places", "places.db", False),
          ("relief", "relief.mbtiles", False), ("contours", "contours.mbtiles", False),
          ("routing", "routing.bin", False))


def check_asset(path: Path, kind: str) -> None:
    if not path.is_file():
        raise ValueError(f"Missing asset: {path}")
    with path.open("rb") as stream:
        header = stream.read(16 if kind != "routing" else 4)
    expected = b"GRT1" if kind == "routing" else SQLITE_HEADER
    if header != expected:
        raise ValueError(f"Invalid {kind} asset header: {path}")


def create_package(args) -> Path:
    if not re.fullmatch(r"[a-z0-9][a-z0-9_-]{1,31}", args.id) or args.id == "georgia":
        raise ValueError("Package id must be 2-32 lowercase letters/digits/_/- and cannot be 'georgia'")
    if not re.fullmatch(r"[A-Z]{2}", args.country_code):
        raise ValueError("Country code must contain two uppercase letters")
    south, west, north, east = args.bounds
    if not (-90 <= south < north <= 90 and -180 <= west < east <= 180):
        raise ValueError("Invalid bounds (expected south west north east)")
    if not args.title or len(args.title) > 80 or any(ch in args.title for ch in "\\r\\n="):
        raise ValueError("Title must contain 1-80 characters and cannot contain newlines or '='")

    selected = []
    for key, archive_name, required in ASSETS:
        raw = getattr(args, key)
        if required and not raw:
            raise ValueError("A tiles MBTiles file is required")
        if raw:
            path = Path(raw)
            check_asset(path, key)
            selected.append((key, archive_name, path))

    lines = [
        f"id={args.id}", f"title={args.title}", f"countryCode={args.country_code}",
        f"south={south}", f"west={west}", f"north={north}", f"east={east}"
    ]
    lines.extend(f"{key}={archive_name}" for key, archive_name, _ in selected)
    manifest = ("\n".join(lines) + "\n").encode("utf-8")
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED) as archive:
        archive.writestr("package.properties", manifest)
        for _, archive_name, path in selected:
            archive.write(path, archive_name)
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--id", required=True)
    parser.add_argument("--title", required=True)
    parser.add_argument("--country-code", required=True)
    parser.add_argument("--bounds", nargs=4, type=float, required=True,
                        metavar=("SOUTH", "WEST", "NORTH", "EAST"))
    parser.add_argument("--tiles", required=True)
    parser.add_argument("--places")
    parser.add_argument("--relief")
    parser.add_argument("--contours")
    parser.add_argument("--routing")
    parser.add_argument("--output", required=True)
    create_package(parser.parse_args())


if __name__ == "__main__":
    main()
