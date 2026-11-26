#!/usr/bin/env python3
"""Generates the map-marker icons (one round coloured badge + white glyph each) as Android vector drawables
res/drawable/ic_cat_*.xml, and a preview sheet (PNG) so the look can be checked without a phone.

Usage: gen_icons.py [<res/drawable dir>] [<preview.png>]

Glyphs are drawn on a 24x24 grid from simple shapes (polygons, circles, rectangles), so the output is exact and
has no external font or icon-set dependency. The badge is centred at (12,12), radius 11, with a white ring.
"""
import math
import os
import sys

DRAW = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "drawable")
PREVIEW = sys.argv[2] if len(sys.argv) > 2 else None


# ---------------------------------------------------------------- shape helpers (all return ("poly", pts) / ("circle", cx, cy, r))
def poly(*pts):
    return ("poly", [(float(x), float(y)) for x, y in pts])


def rect(x, y, w, h):
    return poly((x, y), (x + w, y), (x + w, y + h), (x, y + h))


def circle(cx, cy, r):
    return ("circle", float(cx), float(cy), float(r))


def rrect(x, y, w, h, r, n=5):
    """Rounded rectangle as a polygon."""
    pts = []
    for cx, cy, a0 in ((x + w - r, y + r, -90), (x + w - r, y + h - r, 0), (x + r, y + h - r, 90), (x + r, y + r, 180)):
        for i in range(n + 1):
            a = math.radians(a0 + 90 * i / n)
            pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return ("poly", pts)


def line(x1, y1, x2, y2, w):
    """A straight stroke of width w as a polygon."""
    dx, dy = x2 - x1, y2 - y1
    L = math.hypot(dx, dy) or 1
    nx, ny = -dy / L * w / 2, dx / L * w / 2
    return poly((x1 + nx, y1 + ny), (x2 + nx, y2 + ny), (x2 - nx, y2 - ny), (x1 - nx, y1 - ny))


def star(cx, cy, ro, ri, n=5):
    pts = []
    for i in range(2 * n):
        a = -math.pi / 2 + i * math.pi / n
        r = ro if i % 2 == 0 else ri
        pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return ("poly", pts)


def arc_poly(cx, cy, r, a0, a1, n=14):
    return [(cx + r * math.cos(math.radians(a0 + (a1 - a0) * i / n)),
             cy + r * math.sin(math.radians(a0 + (a1 - a0) * i / n))) for i in range(n + 1)]


# ---------------------------------------------------------------- glyphs: (white shapes, "cut" shapes painted in the badge colour)
def g_fuel():
    white = [rrect(7.2, 6.2, 6.4, 11.6, 0.8), poly((14.6, 8), (16.2, 8), (17.6, 9.6), (17.6, 15.4), (16.4, 15.4), (16.4, 10.2), (14.6, 10.2)),
             rect(6.2, 17.2, 8.4, 1.4)]
    cut = [rect(8.5, 7.5, 3.8, 3.2)]
    return white, cut


def g_pharmacy():
    return [rect(10.4, 5.5, 3.2, 13), rect(5.5, 10.4, 13, 3.2)], []


def g_hospital():
    white = [rrect(5.5, 5.5, 13, 13, 1.6)]
    cut = [rect(8.2, 7.6, 1.8, 8.8), rect(14.0, 7.6, 1.8, 8.8), rect(8.2, 10.9, 7.6, 2.2)]
    return white, cut


def g_bank():
    white = [poly((12, 5.2), (19, 9.2), (5, 9.2)), rect(5.5, 17.2, 13, 1.8), rect(6.6, 10.2, 1.9, 6.1), rect(11.05, 10.2, 1.9, 6.1),
             rect(15.5, 10.2, 1.9, 6.1)]
    return white, []


def g_shop():
    bag = poly((6.2, 9.4), (17.8, 9.4), (18.6, 18.8), (5.4, 18.8))
    handle = ("poly", arc_poly(12, 9.6, 3.6, 180, 360, 14) + arc_poly(12, 9.6, 2.2, 360, 180, 14))
    return [bag, handle], []


def g_food():
    white = [rect(7.2, 5.5, 0.95, 4.4), rect(8.55, 5.5, 0.95, 4.4), rect(9.9, 5.5, 0.95, 4.4),
             poly((7.2, 9.3), (10.85, 9.3), (10.2, 11.7), (7.85, 11.7)), rect(8.3, 11.5, 1.4, 7.0),
             poly((14.4, 5.5), (16.4, 5.5), (16.4, 18.5), (14.8, 18.5), (14.8, 12.2), (13.6, 12.2), (13.6, 8.0))]
    return white, []


def g_lodging():
    white = [rect(5.2, 6.0, 1.5, 12.0), rect(5.2, 14.2, 13.6, 1.8), rect(16.2, 14.2, 1.6, 3.8),
             rrect(7.2, 10.4, 3.4, 2.6, 1.2), poly((11.4, 11.0), (17.6, 11.0), (18.8, 14.2), (11.4, 14.2))]
    return white, []


def g_water():
    # teardrop: apex on top, tangent lines to a circle (centre 12,13.4 r 5.2), round bottom
    drop = [(12, 4.4)] + arc_poly(12, 13.4, 5.2, -35.3, 215.3, 26)
    return [("poly", drop)], [("poly", arc_poly(9.6, 14.0, 1.6, 100, 190, 6) + [(9.6, 14.0)])]


def g_toilets():
    white = [circle(8.2, 6.9, 1.7), circle(15.8, 6.9, 1.7),
             poly((6.0, 9.4), (10.4, 9.4), (11.0, 14.0), (9.6, 14.0), (9.4, 18.6), (7.0, 18.6), (6.8, 14.0), (5.4, 14.0)),
             poly((13.6, 9.4), (18.0, 9.4), (19.2, 14.0), (17.8, 14.0), (17.4, 18.6), (14.4, 18.6), (14.4, 14.0), (13.0, 14.0))]
    return white, []


def g_parking():
    # letter P
    outer = [(8.2, 5.6), (13.4, 5.6)] + arc_poly(13.4, 9.4, 3.8, -90, 90, 14) + [(10.3, 13.2), (10.3, 18.4), (8.2, 18.4)]
    hole = [(10.3, 7.5), (13.0, 7.5)] + arc_poly(13.0, 9.4, 1.9, -90, 90, 10) + [(10.3, 11.3)]
    return [("poly", outer)], [("poly", hole)]


def g_train():
    white = [rrect(6.4, 5.4, 11.2, 11.0, 2.2), poly((8.2, 16.2), (6.6, 19.0), (8.2, 19.0), (9.4, 16.2)),
             poly((15.8, 16.2), (17.4, 19.0), (15.8, 19.0), (14.6, 16.2))]
    cut = [rect(8.0, 7.2, 8.0, 3.8), circle(9.2, 13.6, 0.9), circle(14.8, 13.6, 0.9)]
    return white, cut


def g_bus():
    white = [rrect(6.0, 5.2, 12.0, 11.6, 1.8), rect(7.0, 16.2, 2.0, 2.8), rect(15.0, 16.2, 2.0, 2.8)]
    cut = [rect(7.6, 7.0, 8.8, 3.6), circle(8.8, 14.0, 0.95), circle(15.2, 14.0, 0.95)]
    return white, cut


def g_ferry():
    white = [poly((4.6, 14.2), (19.4, 14.2), (17.2, 18.6), (6.8, 18.6)), rect(7.4, 9.0, 9.2, 4.0),
             rect(10.0, 5.8, 4.0, 2.4), rect(11.5, 4.4, 1.0, 1.6)]
    cut = [rect(8.8, 10.2, 1.8, 1.6), rect(11.1, 10.2, 1.8, 1.6), rect(13.4, 10.2, 1.8, 1.6)]
    return white, cut


def g_aerial():
    white = [line(4.5, 6.4, 19.5, 8.6, 1.0), line(12.0, 7.5, 12.0, 10.0, 1.2), rrect(7.6, 10.0, 8.8, 7.4, 1.6)]
    cut = [rect(8.8, 11.4, 2.5, 2.6), rect(12.7, 11.4, 2.5, 2.6)]
    return white, cut


def g_tram():
    white = [rrect(7.0, 7.2, 10.0, 10.2, 2.0), line(12.0, 3.8, 12.0, 6.6, 1.1), line(8.6, 5.9, 15.4, 5.9, 1.1),
             poly((8.4, 17.2), (7.2, 19.4), (8.6, 19.4), (9.6, 17.2)), poly((15.6, 17.2), (16.8, 19.4), (15.4, 19.4), (14.4, 17.2))]
    cut = [rect(8.6, 8.8, 6.8, 3.4), circle(9.5, 14.8, 0.85), circle(14.5, 14.8, 0.85)]
    return white, cut


def g_sight():
    return [star(12, 12.4, 7.2, 3.0)], []


def g_police():
    white = [poly((12, 4.8), (18.2, 7.2), (18.2, 12.2), (16.4, 15.8), (12, 19.4), (7.6, 15.8), (5.8, 12.2), (5.8, 7.2))]
    cut = [star(12, 11.8, 3.3, 1.35)]
    return white, cut


def g_airport():
    # airplane seen from above, nose up: right half, then mirrored
    right = [(12, 4.0), (13.1, 5.4), (13.1, 9.6), (19.8, 13.8), (19.8, 15.4), (13.1, 13.6), (13.1, 16.8), (15.4, 18.4),
             (15.4, 19.8), (12, 19.0)]
    left = [(24 - x, y) for x, y in right[1:-1]][::-1]
    return [poly(*(right + left))], []


def g_peak():
    white = [poly((5.0, 17.2), (9.6, 8.6), (12.2, 13.2), (14.4, 10.0), (19.0, 17.2))]
    return white, []


def g_dot():
    return [circle(12, 12, 4.0)], []


# (name, badge colour, glyph function)
ICONS = [
    ("fuel", "#E65100", g_fuel),
    ("pharmacy", "#2E7D32", g_pharmacy),
    ("hospital", "#C62828", g_hospital),
    ("bank", "#37474F", g_bank),
    ("shop", "#AD1457", g_shop),
    ("food", "#EF6C00", g_food),
    ("lodging", "#5E35B1", g_lodging),
    ("water", "#0277BD", g_water),
    ("toilets", "#00838F", g_toilets),
    ("parking", "#1565C0", g_parking),
    ("rail", "#C62828", g_train),
    ("subway", "#6A1B9A", g_train),
    ("tram", "#EF6C00", g_tram),
    ("busstation", "#2E7D32", g_bus),
    ("stop", "#1E88E5", g_bus),
    ("ferry", "#0277BD", g_ferry),
    ("aerial", "#6B5B95", g_aerial),
    ("sight", "#F9A825", g_sight),
    ("police", "#283593", g_police),
    ("airport", "#00695C", g_airport),
    ("peak", "#6D4C41", g_peak),
    ("dot", "#78909C", g_dot),
]


# ---------------------------------------------------------------- Android vector drawable writer
def f(v):
    s = ("%.2f" % v).rstrip("0").rstrip(".")
    return s if s not in ("-0", "") else "0"


def path_data(shape):
    if shape[0] == "circle":
        _, cx, cy, r = shape
        return "M%s,%s m-%s,0 a%s,%s 0 1,0 %s,0 a%s,%s 0 1,0 -%s,0z" % (f(cx), f(cy), f(r), f(r), f(r), f(2 * r), f(r), f(r), f(2 * r))
    pts = shape[1]
    return "M" + " L".join("%s,%s" % (f(x), f(y)) for x, y in pts) + "z"


def write_xml(name, colour, white, cut, out_dir):
    lines = ['<?xml version="1.0" encoding="utf-8"?>',
             '<!-- generated by tools/gen_icons.py: map marker "%s" (badge + white glyph) -->' % name,
             '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
             '    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">',
             '    <path android:fillColor="#FFFFFF" android:pathData="%s" />' % path_data(circle(12, 12, 11.6)),
             '    <path android:fillColor="%s" android:pathData="%s" />' % (colour, path_data(circle(12, 12, 10.2)))]
    for s in white:
        if s[0] == "poly" and len(s[1]) < 3:
            continue
        lines.append('    <path android:fillColor="#FFFFFF" android:pathData="%s" />' % path_data(s))
    for s in cut:
        if s[0] == "circle" and s[3] < 0.01:
            continue
        if s[0] == "poly" and (len(s[1]) < 3):
            continue
        lines.append('    <path android:fillColor="%s" android:pathData="%s" />' % (colour, path_data(s)))
    lines.append("</vector>")
    with open(os.path.join(out_dir, "ic_cat_%s.xml" % name), "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")


def main():
    os.makedirs(DRAW, exist_ok=True)
    for name, colour, fn in ICONS:
        white, cut = fn()
        write_xml(name, colour, white, cut, DRAW)
    print("wrote", len(ICONS), "icons to", os.path.abspath(DRAW))

    if PREVIEW:
        from PIL import Image, ImageDraw
        S, scale = 24, 8
        cols = 8
        rows = (len(ICONS) + cols - 1) // cols
        im = Image.new("RGB", (cols * 28 * scale, rows * 28 * scale), "#e9ecea")
        for i, (name, colour, fn) in enumerate(ICONS):
            white, cut = fn()
            tile = Image.new("RGBA", (S * scale * 4, S * scale * 4), (0, 0, 0, 0))   # 4x supersampling
            d = ImageDraw.Draw(tile)
            k = scale * 4

            def draw(shape, fill):
                if shape[0] == "circle":
                    _, cx, cy, r = shape
                    d.ellipse([(cx - r) * k, (cy - r) * k, (cx + r) * k, (cy + r) * k], fill=fill)
                elif len(shape[1]) >= 3:
                    d.polygon([(x * k, y * k) for x, y in shape[1]], fill=fill)
            draw(circle(12, 12, 11.6), "#ffffff")
            draw(circle(12, 12, 10.2), colour)
            for s in white:
                draw(s, "#ffffff")
            for s in cut:
                draw(s, colour)
            tile = tile.resize((S * scale, S * scale), Image.LANCZOS)
            x, y = (i % cols) * 28 * scale + 2 * scale, (i // cols) * 28 * scale + 2 * scale
            im.paste(tile, (x, y), tile)
        im.save(PREVIEW)
        print("preview:", PREVIEW)


if __name__ == "__main__":
    main()
