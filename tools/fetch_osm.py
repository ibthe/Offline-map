#!/usr/bin/env python3
"""Downloads the OSM extract of the country of Georgia from Geofabrik.
The exact URL is looked up in Geofabrik's official index, so it does not depend on a hard-coded path
(and never picks the US state of Georgia). Usage: fetch_osm.py <out.osm.pbf>"""
import json
import os
import shutil
import sys
import time
import urllib.request

OUT = sys.argv[1]
INDEXES = [
    "https://download.geofabrik.de/index-v1-nogeom.json",
    "https://download.geofabrik.de/index-v1.json",
]
GUESSES = [
    "https://download.geofabrik.de/europe/georgia-latest.osm.pbf",
    "https://download.geofabrik.de/asia/georgia-latest.osm.pbf",
]
UA = {"User-Agent": "georgia-offline-map-build"}


def get(url, tries=4):
    for i in range(tries):
        try:
            return urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=120)
        except Exception as e:  # noqa
            print("  retry", url, e)
            time.sleep(2 * (i + 1))
    return None


def find_url():
    for idx in INDEXES:
        r = get(idx)
        if r is None:
            continue
        data = json.load(r)
        cands = []
        for f in data.get("features", []):
            p = f.get("properties", {})
            if p.get("name", "").strip().lower() == "georgia" and not str(p.get("id", "")).startswith("us"):
                url = (p.get("urls") or {}).get("pbf")
                print("  candidate:", p.get("id"), "parent:", p.get("parent"), url)
                if url and not "/us/" in url and "north-america" not in url:
                    cands.append(url)
        if cands:
            return cands[0]
        near = [f["properties"].get("id") for f in data.get("features", [])
                if "georgia" in f["properties"].get("id", "").lower()]
        print("  no match in", idx, "| ids containing 'georgia':", near)
    return None


url = find_url()
urls = [url] if url else []
urls += [g for g in GUESSES if g != url]
for u in urls:
    print("downloading", u)
    r = get(u)
    if r is None:
        continue
    with open(OUT, "wb") as f:
        shutil.copyfileobj(r, f, 1 << 20)
    size = os.path.getsize(OUT)
    print("saved", OUT, size >> 20, "MB")
    if size > 10 << 20:
        sys.exit(0)
    print("file too small, trying next")
sys.exit("ERROR: could not download an OSM extract of Georgia from Geofabrik")
