#!/usr/bin/env python3
"""
Downloads SDF glyph files (.pbf) once, at build time, and stores them in the app's assets,
so the finished APK needs no network.

The label font must cover Latin + Cyrillic + Georgian. Public font hosts are probed in order;
if no single font covers everything, a Latin/Cyrillic font and a Georgian font are merged
into one stack ("NotoSans").

Usage: build_glyphs.py <output dir>      e.g. app/src/main/assets/glyphs/NotoSans
"""
import concurrent.futures as cf
import gzip
import os
import sys
import time
import urllib.parse
import urllib.request

HOSTS = [
    "https://fonts.openmaptiles.org/{font}/{range}.pbf",
    "https://protomaps.github.io/basemaps-assets/fonts/{font}/{range}.pbf",
]
FONTS = [
    "Noto Sans Regular",
    "Klokantech Noto Sans Regular",
    "Noto Sans Georgian Regular",
    "Noto Sans Georgian",
]
RANGES = [(i, i + 255) for i in range(0, 65536, 256)]
MIN_SIZE = 1500  # a range that has real glyphs is much bigger than an empty one (~40 bytes)

LATIN = "0-255"
CYRILLIC = "1024-1279"
GEORGIAN = "4096-4351"


# ---------------------------------------------------------------- protobuf helpers (glyphs.proto)
def read_varint(buf, pos):
    shift = result = 0
    while True:
        b = buf[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        if not b & 0x80:
            return result, pos
        shift += 7


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


def fields(buf):
    pos = 0
    while pos < len(buf):
        key, pos = read_varint(buf, pos)
        num, wt = key >> 3, key & 7
        if wt == 0:
            v, pos = read_varint(buf, pos)
        elif wt == 2:
            ln, pos = read_varint(buf, pos)
            v = buf[pos:pos + ln]
            pos += ln
        elif wt == 1:
            v = buf[pos:pos + 8]
            pos += 8
        elif wt == 5:
            v = buf[pos:pos + 4]
            pos += 4
        else:
            raise ValueError("bad wire type %d" % wt)
        yield num, wt, v


def ld(num, data):
    return varint((num << 3) | 2) + varint(len(data)) + data


def parse(buf):
    """-> {glyph_id: raw glyph bytes}"""
    glyphs = {}
    for num, wt, stack in fields(buf):
        if num != 1 or wt != 2:
            continue
        for n2, w2, g in fields(stack):
            if n2 == 3 and w2 == 2:
                for n3, w3, val in fields(g):
                    if n3 == 1 and w3 == 0:
                        glyphs[val] = g
                        break
    return glyphs


def merge(primary, fallback, name, rng):
    """Glyphs of `primary`, plus any glyph that only `fallback` has."""
    a = parse(primary) if primary else {}
    b = parse(fallback) if fallback else {}
    for gid, g in b.items():
        a.setdefault(gid, g)
    stack = ld(1, name.encode()) + ld(2, rng.encode())
    for gid in sorted(a):
        stack += ld(3, a[gid])
    return ld(1, stack)


# ---------------------------------------------------------------- download
def fetch(url, tries=4):
    for i in range(tries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "georgia-offline-map-build"})
            with urllib.request.urlopen(req, timeout=60) as r:
                data = r.read()
            if data[:2] == b"\x1f\x8b":
                data = gzip.decompress(data)
            return data
        except Exception as e:  # noqa
            if i == tries - 1:
                return None
            time.sleep(1.5 * (i + 1))


def url_for(host, font, rng):
    return host.format(font=urllib.parse.quote(font), range=rng)


def probe():
    """Find (host, font) candidates that work and what they cover."""
    ok = []
    for host in HOSTS:
        for font in FONTS:
            lat = fetch(url_for(host, font, LATIN), tries=2)
            if not lat or len(lat) < MIN_SIZE:
                continue
            cyr = fetch(url_for(host, font, CYRILLIC), tries=2) or b""
            geo = fetch(url_for(host, font, GEORGIAN), tries=2) or b""
            info = dict(host=host, font=font, latin=True,
                        cyr=len(cyr) >= MIN_SIZE, geo=len(geo) >= MIN_SIZE)
            print("  candidate:", info)
            ok.append(info)
    return ok


def main(out_dir):
    cands = probe()
    base = next((c for c in cands if c["cyr"]), None)
    geo = next((c for c in cands if c["geo"]), None)
    if not base:
        sys.exit("ERROR: no font source with Latin+Cyrillic glyphs found")
    if not geo:
        sys.exit(
            "ERROR: no reachable font source has Georgian glyphs.\n"
            "Put your own SDF glyph files (0-255.pbf ... 65280-65535.pbf) for a font covering Georgian\n"
            "into app/src/main/assets/glyphs/NotoSans/ and skip this step (see README)."
        )
    print("base:", base["font"], "| georgian:", geo["font"])
    same = (base["host"], base["font"]) == (geo["host"], geo["font"])
    os.makedirs(out_dir, exist_ok=True)

    def work(r):
        rng = "%d-%d" % r
        a = fetch(url_for(base["host"], base["font"], rng))
        if a is None:
            raise RuntimeError("download failed: " + rng)
        if same:
            data = a
        else:
            b = fetch(url_for(geo["host"], geo["font"], rng))
            data = merge(a, b, "NotoSans", rng) if b else a
        with open(os.path.join(out_dir, rng + ".pbf"), "wb") as f:
            f.write(data)
        return len(data)

    with cf.ThreadPoolExecutor(8) as ex:
        total = sum(ex.map(work, RANGES))
    print("glyphs written: %d files, %.1f MB" % (len(RANGES), total / 1e6))

    g = os.path.getsize(os.path.join(out_dir, GEORGIAN + ".pbf"))
    c = os.path.getsize(os.path.join(out_dir, CYRILLIC + ".pbf"))
    if g < MIN_SIZE or c < MIN_SIZE:
        sys.exit("ERROR: merged stack still lacks Georgian/Cyrillic")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "app/src/main/assets/glyphs/NotoSans")
