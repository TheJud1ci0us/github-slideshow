#!/usr/bin/env python3
"""Draws the Dig site artwork (finds, crates, pickaxes, icons) and writes it as SVG
(svg/, for previews) and as Android vector drawables (app/src/main/res/drawable/dig_*.xml).
Run: python3 tools/dig-art/make_art.py   (from ascolta-android/)"""
import math, os, sys

OUT = "#33261F"
SW = 2.4

def mix(a, b, t):
    a = a.lstrip('#'); b = b.lstrip('#')
    return '#' + ''.join('%02X' % round(int(a[i:i+2], 16) * (1 - t) + int(b[i:i+2], 16) * t) for i in (0, 2, 4))
def dark(c, t=0.3): return mix(c, "#1A0F0A", t)
def light(c, t=0.4): return mix(c, "#FFFFFF", t)
def f(x): return ('%.2f' % x).rstrip('0').rstrip('.')

def circ(cx, cy, r): return ell(cx, cy, r, r)
def ell(cx, cy, rx, ry):
    return f"M{f(cx-rx)},{f(cy)}A{f(rx)},{f(ry)} 0 1,0 {f(cx+rx)},{f(cy)}A{f(rx)},{f(ry)} 0 1,0 {f(cx-rx)},{f(cy)}Z"
def rect(x, y, w, h, r=0):
    if r <= 0: return f"M{f(x)},{f(y)}H{f(x+w)}V{f(y+h)}H{f(x)}Z"
    return (f"M{f(x+r)},{f(y)}H{f(x+w-r)}A{f(r)},{f(r)} 0 0,1 {f(x+w)},{f(y+r)}V{f(y+h-r)}A{f(r)},{f(r)} 0 0,1 {f(x+w-r)},{f(y+h)}"
            f"H{f(x+r)}A{f(r)},{f(r)} 0 0,1 {f(x)},{f(y+h-r)}V{f(y+r)}A{f(r)},{f(r)} 0 0,1 {f(x+r)},{f(y)}Z")
def poly(pts, close=True):
    return "M" + "L".join(f"{f(x)},{f(y)}" for x, y in pts) + ("Z" if close else "")
def ngon(cx, cy, r, n, rot=-90, ry=None):
    ry = ry or r
    return [(cx + r * math.cos(math.radians(rot + 360 * i / n)), cy + ry * math.sin(math.radians(rot + 360 * i / n))) for i in range(n)]
def spiral(cx, cy, r0, r1, turns, start=0, steps=60):
    pts = []
    for i in range(steps + 1):
        t = i / steps; a = math.radians(start + 360 * turns * t); r = r0 + (r1 - r0) * t
        pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return poly(pts, close=False)
def star4(cx, cy, r):
    k = r * 0.22
    return poly([(cx, cy-r), (cx+k, cy-k), (cx+r, cy), (cx+k, cy+k), (cx, cy+r), (cx-k, cy+k), (cx-r, cy), (cx-k, cy-k)])

class Art:
    def __init__(self, name): self.name = name; self.items = []; self.rot = None
    def add(self, d, fill=None, stroke=None, sw=SW, alpha=1.0):
        self.items.append(dict(d=d, fill=fill, stroke=stroke, sw=sw, alpha=alpha, rot=self.rot)); return self
    def shape(self, d, fill, sw=SW): return self.add(d, fill, OUT, sw)
    def blob(self, ds, fill, sw=SW):
        """Several shapes that read as one: outlined together, filled without inner lines."""
        for d in ds: self.add(d, OUT, OUT, sw * 2)
        for d in ds: self.add(d, fill)
        return self
    def line(self, d, color=OUT, sw=1.6, alpha=1.0): return self.add(d, None, color, sw, alpha)
    def shine(self, d, alpha=0.55): return self.add(d, "#FFFFFF", None, 0, alpha)
    def sparkle(self, cx, cy, r=4): return self.add(star4(cx, cy, r), "#FFFFFF", None, 0, 0.95)
    def turn(self, deg, cx=32, cy=32): self.rot = (deg, cx, cy) if deg else None; return self

    def svg(self):
        out = ['<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64">']
        for it in self.items:
            a = [f'd="{it["d"]}"', f'fill="{it["fill"] or "none"}"']
            if it["stroke"]: a += [f'stroke="{it["stroke"]}"', f'stroke-width="{f(it["sw"])}"', 'stroke-linejoin="round"', 'stroke-linecap="round"']
            if it["alpha"] < 1: a.append(f'opacity="{f(it["alpha"])}"')
            if it["rot"]: a.append('transform="rotate(%s %s %s)"' % tuple(f(v) for v in it["rot"]))
            out.append('<path ' + ' '.join(a) + '/>')
        return '\n'.join(out + ['</svg>'])
    def vector(self):
        out = ['<vector xmlns:android="http://schemas.android.com/apk/res/android"',
               '    android:width="64dp" android:height="64dp" android:viewportWidth="64" android:viewportHeight="64">']
        for it in self.items:
            a = [f'android:pathData="{it["d"]}"']
            if it["fill"]: a.append(f'android:fillColor="{it["fill"]}"')
            if it["stroke"]: a += [f'android:strokeColor="{it["stroke"]}"', f'android:strokeWidth="{f(it["sw"])}"', 'android:strokeLineJoin="round"', 'android:strokeLineCap="round"']
            if it["alpha"] < 1: a.append(f'android:{"fillAlpha" if it["fill"] else "strokeAlpha"}="{f(it["alpha"])}"')
            p = '<path ' + ' '.join(a) + '/>'
            if it["rot"]: p = '<group android:rotation="%s" android:pivotX="%s" android:pivotY="%s">%s</group>' % (*[f(v) for v in it["rot"]], p)
            out.append('    ' + p)
        return '\n'.join(out + ['</vector>']) + '\n'

ALL = []
def art(name):
    a = Art(name); ALL.append(a); return a

# ---------------------------------------------------------------- shared pieces
def coin(name, c, mark):
    a = art(name)
    a.shape(circ(32, 33, 22), dark(c, 0.25)); a.shape(circ(32, 31, 22), c)
    a.line(circ(32, 31, 16.5), dark(c, 0.3), 1.8)
    mark(a, dark(c, 0.35))
    a.line("M17,24A17,17 0 0,1 30,13", "#FFFFFF", 2.4, 0.6)
    return a

def gem(name, c, pts, table):
    """A cut stone: outline [pts], an inner table, facets from each corner."""
    a = art(name)
    a.shape(poly(pts), c)
    n = len(pts)
    for i in range(n):
        j = (i + 1) % n
        shade = [light(c, 0.45), light(c, 0.2), dark(c, 0.12), dark(c, 0.28)][(i * 4) // n]
        a.add(poly([pts[i], pts[j], table[j], table[i]]), shade)
    a.add(poly(table), light(c, 0.3))
    for i in range(n): a.line(poly([pts[i], table[i]], False), dark(c, 0.35), 1.0, 0.7)
    a.line(poly(table), dark(c, 0.35), 1.0, 0.7)
    a.line(poly(pts), OUT, SW)
    return a

# ---------------------------------------------------------------- common
coin("coin_copper", "#C9793D", lambda a, c: a.add(star4(32, 31, 9), c))

a = art("shell_snail")
a.shape("M12,44C10,24 26,12 40,16C54,20 56,40 44,48C36,53 16,54 12,44Z", "#E9C79A")
a.line(spiral(34, 33, 2, 15, 2.1, 200), "#9A6A3C", 2.2)
a.shape("M12,44C14,50 24,54 30,50C24,48 18,46 12,44Z", "#F6E6CC", 1.8)
a.shine(ell(26, 22, 5, 2.5), 0.5)

a = art("bone").turn(-40)
a.blob([rect(16, 28, 32, 8, 3), circ(15, 27, 5.5), circ(15, 37, 5.5), circ(49, 27, 5.5), circ(49, 37, 5.5)], "#F3EBDA")
a.line("M20,34H44", "#CDBFA3", 1.6)

a = art("flint")
a.shape(poly([(14, 40), (20, 22), (36, 14), (50, 26), (48, 44), (30, 52)]), "#7C8088")
a.add(poly([(20, 22), (36, 14), (32, 30)]), light("#7C8088", 0.3)); a.add(poly([(32, 30), (50, 26), (48, 44)]), dark("#7C8088", 0.2))
a.line(poly([(20, 22), (32, 30), (30, 52)], False), dark("#7C8088", 0.4), 1.2); a.line("M32,30L50,26", dark("#7C8088", 0.4), 1.2)
a.line(poly([(14, 40), (20, 22), (36, 14), (50, 26), (48, 44), (30, 52)]), OUT)

a = art("shard")
a.shape(poly([(14, 22), (30, 14), (50, 20), (46, 40), (34, 52), (18, 44)]), "#C8693F")
a.line(poly([(16, 30), (22, 25), (28, 31), (34, 25), (40, 31), (47, 26)], False), "#F2D9B8", 2.4)
a.line(poly([(18, 39), (45, 36)], False), "#7A3A1E", 2)
a.shine(poly([(20, 21), (30, 17), (31, 19), (21, 23)]), 0.35)

a = art("nail").turn(35)
a.blob([rect(29.5, 16, 5, 34, 1), poly([(29.5, 50), (34.5, 50), (32, 58)]), rect(22, 10, 20, 7, 3)], "#9A5A3A")
a.line("M31,20V46", "#C98B62", 1.4)

a = art("button")
a.shape(circ(32, 33, 20), dark("#5F86B8", 0.25)); a.shape(circ(32, 31, 20), "#5F86B8")
a.line(circ(32, 31, 13), dark("#5F86B8", 0.3), 1.8)
for x, y in ((27, 26), (37, 26), (27, 36), (37, 36)): a.shape(circ(x, y, 2.6), "#2B3C55", 1.2)
a.line("M18,24A16,16 0 0,1 29,14", "#FFFFFF", 2.2, 0.55)

a = art("shell_clam")
a.shape("M32,50L10,34C8,18 22,10 32,10C42,10 56,18 54,34Z", "#F1B7A8")
for x in (14, 21, 27, 32, 37, 43, 50): a.line(f"M32,48L{x},{22 + abs(32 - x) * 0.45:.1f}", "#B86C5E", 1.5)
a.shape(rect(25, 46, 14, 8, 3), "#E79C8C", 2)
a.shine(ell(22, 20, 5, 2.5), 0.45)

# ---------------------------------------------------------------- uncommon
a = art("arrowhead")
a.shape(poly([(32, 6), (48, 36), (38, 40), (38, 56), (26, 56), (26, 40), (16, 36)]), "#6F8791")
a.add(poly([(32, 6), (48, 36), (38, 40), (32, 40)]), dark("#6F8791", 0.18))
a.line("M32,8V54", dark("#6F8791", 0.45), 1.3)
for y in (20, 28): a.line(f"M32,{y}L{24 - (y - 20) * 0.4:.1f},{y + 6}", light("#6F8791", 0.45), 1.3)
a.line(poly([(32, 6), (48, 36), (38, 40), (38, 56), (26, 56), (26, 40), (16, 36)]), OUT)

coin("coin_silver", "#C3CAD3", lambda a, c: (a.add(circ(32, 31, 6), c), a.line("M32,19V43M20,31H44", c, 2.2)))

a = art("pot")
a.line("M20,24C10,22 10,36 20,36", "#8A4B2B", 6); a.line("M44,24C54,22 54,36 44,36", "#8A4B2B", 6)
a.line("M20,24C10,22 10,36 20,36", "#C97B4A", 2.4); a.line("M44,24C54,22 54,36 44,36", "#C97B4A", 2.4)
a.shape("M24,16C24,22 14,28 14,40C14,52 22,58 32,58C42,58 50,52 50,40C50,28 40,22 40,16Z", "#C97B4A")
a.shape(rect(21, 9, 22, 8, 3), "#DD9666")
a.line(poly([(15, 38), (20, 33), (26, 39), (32, 33), (38, 39), (44, 33), (49, 38)], False), "#F4DFC0", 2.2)
a.line("M16,46H48", "#7A3F22", 2)
a.shine(ell(23, 28, 2.5, 5), 0.35)

a = art("key").turn(40)
a.blob([circ(32, 16, 10), rect(29.5, 24, 5, 34, 1), rect(34, 46, 8, 4, 1), rect(34, 53, 6, 4, 1)], "#D2A24A")
a.shape(circ(32, 16, 4.5), "#F4EEE2", 1.6)
a.line("M31,28V54", light("#D2A24A", 0.5), 1.3)

a = art("ammonite")
a.shape(circ(32, 32, 23), "#B88A5C")
a.line(spiral(33, 33, 2, 21, 2.6, 30, 90), "#6E4A2A", 2.4)
for i in range(14):
    an = math.radians(i * 360 / 14); a.line(f"M{32 + 15 * math.cos(an):.1f},{32 + 15 * math.sin(an):.1f}L{32 + 22 * math.cos(an + 0.12):.1f},{32 + 22 * math.sin(an + 0.12):.1f}", "#8A6038", 1.4)
a.shine(ell(22, 18, 6, 2.5), 0.35)

a = art("bottle")
a.shape("M26,8H38V20C44,24 46,28 46,34V52C46,56 43,58 40,58H24C21,58 18,56 18,52V34C18,28 20,24 26,20Z", "#5FAE8C")
a.add(rect(21, 36, 22, 19, 3), "#3F8468", None, 0, 0.7)
a.shape(rect(24, 5, 16, 6, 2), "#B78A58")
a.shine(rect(22, 26, 3.5, 22, 1.7), 0.55)

a = art("horseshoe")
u = "M18,50V28C18,12 46,12 46,28V50"
a.line(u, OUT, 13); a.line(u, "#8C9199", 8.4); a.line("M21,46V28C21,17 38,15 41,22", light("#8C9199", 0.5), 1.6)
for x, y in ((18, 44), (18, 34), (23, 22), (41, 22), (46, 34), (46, 44)): a.add(circ(x, y, 1.5), OUT)

a = art("beads")
a.line("M12,16C12,48 52,48 52,16", "#7A5A3A", 1.6)
cols = ["#D9534F", "#F0AD4E", "#5BC0DE", "#5CB85C", "#F0AD4E", "#D9534F", "#5BC0DE", "#5CB85C", "#D9534F"]
for i, c in enumerate(cols):
    t = (i + 0.5) / len(cols); x = 12 + 40 * t; y = 16 + 24 * 4 * t * (1 - t) * 1.0
    a.shape(circ(x, y, 4.4 if i != 4 else 6.5), c, 1.6)
a.shine(circ(30.5, 38, 1.8), 0.7)

# ---------------------------------------------------------------- rare
a = art("amber")
a.shape("M32,8C44,14 54,28 50,42C47,54 36,58 28,56C16,53 10,42 14,30C17,20 24,12 32,8Z", "#F2A02E")
a.add("M30,16C40,22 46,32 44,42C42,50 34,52 28,50C20,48 16,40 19,31C21,24 25,19 30,16Z", "#F9C255", None, 0, 0.8)
a.add(ell(32, 36, 3, 5), "#4A2A12"); a.add(circ(32, 29.5, 2.2), "#4A2A12")
for s in (-1, 1):
    a.line(f"M{32 + 2 * s},34L{32 + 8 * s},31M{32 + 2 * s},37L{32 + 8 * s},38M{32 + 2 * s},39L{32 + 7 * s},43", "#4A2A12", 1.1)
a.shine(ell(24, 22, 3, 6), 0.6)

a = art("ring")
a.line(ell(32, 40, 17, 14), OUT, 10); a.line(ell(32, 40, 17, 14), "#C99A3E", 5.6); a.line("M18,34A15,12 0 0,1 30,27", light("#C99A3E", 0.55), 1.5)
a.shape(poly(ngon(32, 21, 9, 6)), "#2FA6A0"); a.add(poly(ngon(32, 21, 4.5, 6)), light("#2FA6A0", 0.45))
a.sparkle(40, 13, 3.5)

a = art("lamp")
a.line("M16,34C4,30 6,46 18,44", OUT, 6); a.line("M16,34C4,30 6,46 18,44", "#B8763E", 2.2)
a.shape("M14,36C14,28 26,26 34,26C42,26 46,30 60,28C56,34 50,36 48,42C46,50 38,52 30,52C20,52 14,46 14,36Z", "#C98443")
a.shape(rect(24, 22, 14, 6, 3), "#DFA25E")
a.shape("M57,26C53,20 58,16 57,10C62,15 64,22 57,26Z", "#F6B93B", 1.6); a.add("M57.5,24C56,20 58,18 58,15C60,18 60,22 57.5,24Z", "#FFF1B8")
a.line("M20,42C26,46 36,46 42,40", "#8A5526", 1.8)
a.shape(rect(24, 52, 14, 5, 2), "#A9682F")

a = art("trilobite")
a.shape(ell(32, 34, 17, 23), "#7D6B58")
a.add("M15,28A17,17 0 0,1 49,28Q32,34 15,28Z", "#96826C"); a.line("M15,28Q32,34 49,28", "#4A3D30", 1.6)
for y in (34, 39, 44, 49): a.line(f"M{17 + (y - 34) * 0.5:.1f},{y}Q32,{y + 4} {47 - (y - 34) * 0.5:.1f},{y}", "#4A3D30", 1.5)
a.line("M26,30V54M38,30V54", "#4A3D30", 1.5)
a.add(circ(24, 21, 2.4), "#2C231B"); a.add(circ(40, 21, 2.4), "#2C231B")
a.shine(ell(27, 16, 5, 2), 0.3)

gem("sapphire", "#2F6FD6", ngon(32, 32, 24, 8, -67.5), ngon(32, 32, 13, 8, -67.5)).sparkle(46, 16, 4)

a = art("dagger").turn(45)
a.shape(poly([(32, 2), (38, 12), (38, 38), (26, 38), (26, 12)]), "#C9D2DA"); a.add(poly([(32, 2), (38, 12), (38, 38), (32, 38)]), "#9DA9B4"); a.line("M32,6V38", "#6F7B86", 1.2)
a.shape(rect(18, 37, 28, 6, 3), "#C99A3E"); a.shape(rect(28, 43, 8, 14, 2), "#6B4226")
a.line("M28,47H36M28,51H36", "#3E2413", 1.3); a.shape(circ(32, 59, 4), "#C99A3E", 1.8)

a = art("tablet")
a.shape("M14,12C22,8 44,8 50,12L52,50C44,56 20,56 12,50Z", "#B7A78C")
for y in (19, 27, 35, 43):
    for x in (19, 27, 35, 43):
        k = (x * 7 + y * 3) % 4
        d = [f"M{x},{y}v5", f"M{x},{y}l4,2l-4,3", f"M{x},{y}h4M{x + 2},{y}v5", f"M{x},{y + 4}l2,-4l2,4"][k]
        a.line(d, "#5F5340", 1.4)
a.line("M40,10L44,20L50,22", "#7E6F58", 1.3)
a.shine(poly([(16, 14), (26, 11.5), (26, 13.5), (16, 16)]), 0.4)

a = art("compass")
a.shape(circ(32, 8, 4.5), "#C99A3E", 2); a.add(circ(32, 8, 1.8), "#F4EEE2")
a.shape(circ(32, 35, 22), "#C99A3E"); a.shape(circ(32, 35, 16.5), "#F6F0E2", 1.6)
a.add(poly([(32, 21), (36, 35), (28, 35)]), "#D9453C"); a.add(poly([(32, 49), (36, 35), (28, 35)]), "#3D5A80")
a.add(circ(32, 35, 2.2), OUT)
for an in (0, 90, 180, 270): a.line(f"M{32 + 14 * math.cos(math.radians(an)):.1f},{35 + 14 * math.sin(math.radians(an)):.1f}L{32 + 16 * math.cos(math.radians(an)):.1f},{35 + 16 * math.sin(math.radians(an)):.1f}", OUT, 1.4)
a.line("M15,28A19,19 0 0,1 26,17", "#FFFFFF", 2, 0.55)

# ---------------------------------------------------------------- epic
a = art("geode")
rock = [(10, 34), (14, 18), (28, 8), (46, 12), (56, 28), (52, 46), (38, 56), (20, 52)]
a.shape(poly(rock), "#7A746C"); a.shape(poly([(18, 34), (21, 22), (30, 16), (43, 19), (49, 30), (46, 42), (36, 49), (24, 46)]), "#5B2E8C", 1.6)
for x, y, h in ((24, 40, 12), (31, 44, 17), (38, 42, 14), (44, 36, 9), (27, 30, 8), (36, 30, 10)):
    a.add(poly([(x - 3.5, y), (x, y - h), (x + 3.5, y)]), "#B388E8"); a.add(poly([(x, y - h), (x + 3.5, y), (x, y)]), "#8E5BD0")
a.sparkle(34, 24, 4); a.shine(poly([(15, 20), (27, 11), (28, 13), (17, 22)]), 0.3)

a = art("goblet")
a.shape("M14,10H50C50,28 44,36 36,38V48H44C47,48 48,52 48,55H16C16,52 17,48 20,48H28V38C20,36 14,28 14,10Z", "#E2B23A")
a.add("M34,12H48C48,26 43,33 36,36Z", dark("#E2B23A", 0.15)); a.shape(rect(12, 7, 40, 6, 3), "#F0C95A")
a.shape(circ(32, 23, 4.5), "#D9453C", 1.6); a.add(circ(22, 20, 2.3), "#2F6FD6"); a.add(circ(42, 20, 2.3), "#2FA66A")
a.shine(rect(18, 15, 3, 14, 1.5), 0.5); a.sparkle(50, 8, 4)

a = art("skull_dino")
a.shape("M6,30C6,18 18,10 30,10C42,10 50,16 54,24L60,32L56,40H40L36,46H20C12,46 6,40 6,30Z", "#EFE6D2")
a.shape(circ(24, 26, 6.5), "#3A2E26", 1.6); a.add(ell(50, 28, 2.5, 1.8), "#3A2E26")
a.shape(poly([(34, 40), (36, 46), (38, 40), (40, 46), (42, 40), (44, 46), (46, 40), (48, 46), (50, 40), (52, 45), (54, 40)]), "#FFFFFF", 1.5)
a.shape("M22,46H36L40,52C36,56 26,56 20,52Z", "#E2D6BC")
a.line("M12,20C16,16 22,14 28,14", "#FFFFFF", 2, 0.6); a.line("M36,16C40,20 42,26 40,34", "#B9AB8E", 1.4)

gem("emerald", "#22A565", [(20, 8), (44, 8), (54, 20), (54, 44), (44, 56), (20, 56), (10, 44), (10, 20)],
    [(24, 18), (40, 18), (44, 24), (44, 40), (40, 46), (24, 46), (20, 40), (20, 24)]).sparkle(47, 14, 4)

a = art("idol")
a.shape("M22,26H42L48,56H16Z", "#4FA58B"); a.shape(rect(18, 8, 28, 22, 8), "#5FBFA2")
a.shape(rect(14, 12, 6, 12, 3), "#4FA58B", 2); a.shape(rect(44, 12, 6, 12, 3), "#4FA58B", 2)
a.add(rect(23, 15, 6, 4, 1), "#1F4D40"); a.add(rect(35, 15, 6, 4, 1), "#1F4D40"); a.line("M27,24H37", "#1F4D40", 2)
a.line("M22,36H42M21,42H43", "#2F7A66", 1.6); a.shape(circ(32, 49, 3.5), "#E2B23A", 1.5)
a.shine(rect(21, 10, 8, 2.5, 1.2), 0.45)

gem("ruby", "#D8324A", ngon(32, 32, 24, 6, -90), ngon(32, 32, 12, 6, -90)).sparkle(45, 15, 4)

a = art("pearl")
a.shape("M8,36C8,18 22,8 32,8C42,8 56,18 56,36Z", "#C9B8D8")
for x in (16, 24, 32, 40, 48): a.line(f"M32,34L{x},{16 + abs(32 - x) * 0.5:.1f}", "#9A86AE", 1.4)
a.shape("M8,36H56C56,50 44,58 32,58C20,58 8,50 8,36Z", "#A892BC"); a.add("M12,37H52C52,44 44,48 32,48C20,48 12,44 12,37Z", "#F4E3EC")
a.shape(circ(32, 40, 8), "#FBF6F0", 1.8); a.shine(circ(29.5, 37.5, 2.4), 0.95); a.add("M36,45A8,8 0 0,0 39.5,38A9,9 0 0,1 36,45Z", "#D9CFE6")
a.sparkle(46, 30, 3.5)

a = art("helmet")
a.shape("M22,10C26,2 44,2 46,12C40,8 30,8 26,14Z", "#C8372F")
a.shape("M12,40C12,20 22,12 32,12C42,12 52,20 52,40V52H40V40H36V56H28V40H24V52H12Z", "#C08A3A")
a.add("M34,14C44,16 50,26 50,40V50H42V38H36V16Z", dark("#C08A3A", 0.15)); a.line("M12,36H52", "#7A5320", 1.8)
for x in (17, 25, 39, 47): a.add(circ(x, 32, 1.5), "#F4D48A")
a.shine(ell(21, 22, 2.5, 6), 0.4)

# ---------------------------------------------------------------- legendary
a = art("mask")
a.shape("M8,14C16,4 48,4 56,14L52,48H12Z", "#2F5FB0")
for x in (13, 19, 45, 51): a.line(f"M{x},{12 if x in (19, 45) else 15}L{x + (1 if x < 32 else -1)},46", "#F2C94C", 3)
a.shape("M18,20C18,10 46,10 46,20V36C46,48 38,56 32,56C26,56 18,48 18,36Z", "#F2C94C")
a.add("M33,12C42,12 46,16 46,22V36C46,46 40,53 33,55Z", dark("#F2C94C", 0.13))
a.shape("M21,27Q26,23 30,27Q26,31 21,27Z", "#1D1A2B", 1.3); a.shape("M34,27Q38,23 43,27Q38,31 34,27Z", "#1D1A2B", 1.3)
a.line("M32,30V38L29,40M27,45Q32,48 37,45", "#8A6510", 1.8); a.shape(rect(29, 54, 6, 8, 2), "#2F5FB0", 1.8)
a.shape(circ(32, 13, 3.2), "#D8324A", 1.4); a.sparkle(50, 8, 4.5)

a = art("crown")
a.shape(poly([(8, 18), (20, 32), (32, 10), (44, 32), (56, 18), (52, 48), (12, 48)]), "#F2C94C")
a.add(poly([(32, 10), (44, 32), (56, 18), (52, 48), (32, 48)]), dark("#F2C94C", 0.13))
a.shape(rect(10, 44, 44, 10, 3), "#E0A92E")
for x, y in ((8, 17), (32, 9), (56, 17)): a.shape(circ(x, y, 3.6), "#F7DC7A", 1.5)
a.shape(poly(ngon(32, 36, 5.5, 4)), "#D8324A", 1.4); a.add(circ(18, 49, 2.5), "#2F6FD6"); a.add(circ(46, 49, 2.5), "#22A565"); a.add(circ(32, 49, 2.5), "#D8324A")
a.sparkle(47, 24, 4.5); a.shine(poly([(13, 24), (19, 32), (17, 33), (12, 27)]), 0.5)

a = art("diamond")
top = [(8, 24), (18, 10), (46, 10), (56, 24)]
a.shape(poly(top + [(32, 56)]), "#A9E4F5")
a.add(poly([(8, 24), (18, 10), (24, 24)]), "#D9F5FC"); a.add(poly([(18, 10), (32, 10), (24, 24)]), "#BDEBF8"); a.add(poly([(32, 10), (46, 10), (40, 24)]), "#8FD8EE"); a.add(poly([(46, 10), (56, 24), (40, 24)]), "#6FC6E2")
a.add(poly([(24, 24), (32, 10), (40, 24)]), "#EAFBFF"); a.add(poly([(8, 24), (24, 24), (32, 56)]), "#BDEBF8"); a.add(poly([(24, 24), (40, 24), (32, 56)]), "#8FD8EE"); a.add(poly([(40, 24), (56, 24), (32, 56)]), "#56B3D4")
a.line("M8,24H56M24,24L32,56L40,24M18,10L24,24L32,10L40,24L46,10", "#3C8FB0", 1.1, 0.8)
a.line(poly(top + [(32, 56)]), OUT); a.sparkle(50, 8, 5); a.sparkle(12, 44, 3.5)

a = art("egg")
a.shape("M32,6C44,6 52,26 52,38C52,50 44,58 32,58C20,58 12,50 12,38C12,26 20,6 32,6Z", "#6B4FB5")
a.add("M34,8C44,10 50,26 50,38C50,48 43,55 34,56Z", dark("#6B4FB5", 0.18))
for r, y in enumerate((20, 29, 38, 47)):
    for i in range(-2, 3):
        x = 32 + i * 9 + (4.5 if r % 2 else 0)
        if abs(x - 32) < 17 - abs(y - 36) * 0.35: a.line(f"M{x - 4},{y}Q{x},{y + 6} {x + 4},{y}", "#B9A4F0", 1.5)
a.shine(ell(23, 20, 3, 7), 0.45); a.sparkle(47, 12, 4.5)

a = art("scarab")
for s in (-1, 1):
    a.line(f"M{32 + 12 * s},26L{32 + 24 * s},18M{32 + 14 * s},36L{32 + 26 * s},38M{32 + 12 * s},46L{32 + 22 * s},54", OUT, 3.4)
    a.line(f"M{32 + 12 * s},26L{32 + 24 * s},18M{32 + 14 * s},36L{32 + 26 * s},38M{32 + 12 * s},46L{32 + 22 * s},54", "#E0A92E", 1.4)
a.shape(ell(32, 38, 15, 19), "#1F6FB5"); a.add("M32,20A15,19 0 0,1 32,57Z", dark("#1F6FB5", 0.2))
a.shape("M19,26A14,12 0 0,1 45,26Q32,32 19,26Z", "#F2C94C"); a.shape(ell(32, 13, 7, 5), "#F2C94C")
a.line("M32,29V57", "#F2C94C", 2); a.line("M22,38Q26,44 22,50M42,38Q38,44 42,50", "#7FC1F0", 1.5)
a.shine(ell(25, 34, 2, 4), 0.5); a.sparkle(48, 10, 4.5)

a = art("meteorite")
rock = [(10, 30), (18, 14), (34, 8), (50, 16), (56, 34), (48, 50), (30, 56), (14, 46)]
a.shape(poly(rock), "#3B3640"); a.add(poly([(34, 8), (50, 16), (56, 34), (48, 50), (30, 56), (36, 34)]), "#2A2630")
a.line(poly([(18, 22), (26, 28), (24, 38), (32, 44), (30, 52)], False), "#FF8A2A", 2.6); a.line(poly([(26, 28), (38, 24), (46, 30)], False), "#FF8A2A", 2.2); a.line(poly([(32, 44), (44, 42)], False), "#FF8A2A", 2)
a.line(poly([(18, 22), (26, 28), (24, 38), (32, 44), (30, 52)], False), "#FFE08A", 1); a.line(poly([(26, 28), (38, 24), (46, 30)], False), "#FFE08A", 0.9)
a.add(circ(40, 15, 2.5), "#25212A"); a.add(circ(18, 36, 2), "#25212A"); a.add(circ(46, 40, 2.2), "#1D1A21")
a.line(poly(rock), OUT); a.sparkle(52, 10, 5); a.sparkle(10, 16, 3.5)

a = art("skull_crystal")
a.shape("M10,28C10,14 20,6 32,6C44,6 54,14 54,28C54,36 50,40 46,43V54C46,56 44,58 42,58H22C20,58 18,56 18,54V43C14,40 10,36 10,28Z", "#BFEFF2")
a.add("M34,8C45,9 52,17 52,28C52,35 48,39 44,42V56H34Z", "#8ED6DE")
a.shape(ell(23, 30, 6, 7), "#2A6F7A", 1.6); a.shape(ell(41, 30, 6, 7), "#2A6F7A", 1.6); a.shape(poly([(32, 37), (35, 44), (29, 44)]), "#2A6F7A", 1.3)
a.line("M25,50V58M32,50V58M39,50V58M20,50H44", "#3E8F9A", 1.5)
a.shine(ell(21, 15, 6, 3), 0.7); a.sparkle(49, 10, 5); a.sparkle(12, 48, 3.5)

a = art("sceptre").turn(40)
a.shape(rect(29.5, 20, 5, 40, 2), "#E0A92E"); a.line("M31,24V58", light("#E0A92E", 0.55), 1.2)
a.shape(rect(25, 30, 14, 4, 2), "#F2C94C", 1.8); a.shape(rect(26, 54, 12, 6, 3), "#F2C94C", 1.8)
a.shape(poly([(22, 22), (24, 12), (40, 12), (42, 22)]), "#F2C94C"); a.shape(circ(32, 11, 9), "#9B4DDB"); a.add("M32,2A9,9 0 0,1 32,20Z", dark("#9B4DDB", 0.2))
a.shine(circ(29, 8, 2.5), 0.8); a.sparkle(44, 6, 4.5)

# ---------------------------------------------------------------- crates
def crate(name, body, band, opened, glow=None):
    a = art(name)
    if opened:
        if glow:
            a.add(poly([(14, 30), (4, 4), (60, 4), (50, 30)]), glow, None, 0, 0.35); a.add(poly([(20, 30), (18, 6), (46, 6), (44, 30)]), "#FFFFFF", None, 0, 0.45)
        a.turn(-18, 12, 26); a.shape(rect(10, 14, 44, 12, 3), light(body, 0.12)); a.shape(rect(28, 13, 8, 14, 1), band, 1.8); a.turn(0)
        a.shape(rect(12, 28, 40, 6, 1), dark(body, 0.5), 2)
    a.shape(rect(10, 30, 44, 26, 3), body)
    a.add(rect(34, 31, 19, 24, 2), dark(body, 0.14))
    a.line("M10,43H54", dark(body, 0.4), 1.5)
    a.shape(rect(14, 30, 6, 26, 1), band, 1.8); a.shape(rect(44, 30, 6, 26, 1), band, 1.8)
    if not opened:
        a.shape("M10,30C10,18 18,12 32,12C46,12 54,18 54,30Z", light(body, 0.12)); a.add("M34,13C46,14 53,19 53,29H34Z", body)
        a.shape("M14,30C14,20 16,15 20,13.6V30Z", band, 1.8); a.shape("M50,30C50,20 48,15 44,13.6V30Z", band, 1.8)
        a.shape(rect(8, 28, 48, 5, 2), band, 1.8)
    a.shape(rect(27, 31 if opened else 27, 10, 11, 2), light(band, 0.25), 1.8); a.add(circ(32, 36 if opened else 32, 1.8), OUT); a.add(rect(31.2, 37 if opened else 33, 1.6, 3.5), OUT)
    a.shine(rect(13, 33, 2, 8, 1) if opened else poly([(14, 22), (20, 16), (21, 17.5), (15.5, 23.5)]), 0.4)
    if glow: a.sparkle(52, 10 if opened else 14, 4.5)
    return a

for n, body, band, glow in (("wood", "#A8703F", "#6F7680", None), ("iron", "#7F8C99", "#3F4A56", "#7FC1F0"), ("gold", "#EDBE3C", "#B5611F", "#FFE08A")):
    crate(f"crate_{n}", body, band, False, glow); crate(f"crate_{n}_open", body, band, True, glow or "#FFE8B0")

# ---------------------------------------------------------------- pickaxes
for n, head in (("wood", "#B98A55"), ("stone", "#8D9299"), ("iron", "#C3CAD3"), ("steel", "#6FA3C7"), ("diamond", "#7FE3F2")):
    a = art(f"pick_{n}").turn(45)
    a.shape(rect(29, 12, 6, 48, 2.5), "#8A5A33"); a.line("M30.8,20V56", "#B98456", 1.3)
    a.shape("M4,22C16,4 48,4 60,22C50,15 40,13 36,13V19H28V13C24,13 14,15 4,22Z", head)
    a.add("M36,13C40,13 50,15 60,22C52,10 42,7.5 36,7.5Z", dark(head, 0.18)); a.line("M10,16C18,10 26,8.5 30,8.5", "#FFFFFF", 1.6, 0.6)
    a.shape(rect(27, 9, 10, 11, 2), dark(head, 0.3), 1.8)
    if n == "diamond": a.turn(0); a.sparkle(50, 12, 5); a.sparkle(14, 46, 3.5)

# ---------------------------------------------------------------- icons
a = art("minute")  # the currency: a small clock coin
a.shape(circ(32, 34, 22), "#C9892B"); a.shape(circ(32, 31, 22), "#F4C24D"); a.shape(circ(32, 31, 15.5), "#FFF4D6", 1.8)
a.line("M32,21V31L39,35", OUT, 2.8); a.add(circ(32, 31, 1.8), OUT); a.line("M17,24A17,17 0 0,1 30,13", "#FFFFFF", 2.4, 0.6)

a = art("site")  # the dig site: a tent, a mound and a pickaxe
a.add(circ(46, 16, 7), "#F4C24D")
a.shape("M2,58C10,40 24,38 34,46C42,38 56,42 62,58Z", "#B98A55"); a.add("M34,46C42,38 56,42 62,58H40Z", "#9A6E3E")
a.shape(poly([(8, 50), (24, 18), (40, 50)]), "#E9E0CC"); a.add(poly([(24, 18), (40, 50), (27, 50)]), "#CFC2A6"); a.shape(poly([(24, 30), (30, 50), (18, 50)]), "#5B4636", 1.8)
a.line("M24,18V10", OUT, 2); a.shape(poly([(24, 9), (33, 12), (24, 15)]), "#D9453C", 1.5)
a.turn(30, 50, 44); a.shape(rect(48.5, 30, 3.5, 24, 1.5), "#8A5A33", 1.8); a.shape("M38,36C44,26 56,26 62,36C56,32 44,32 38,36Z", "#C3CAD3", 1.8); a.turn(0)

a = art("museum")
a.shape(poly([(6, 24), (32, 8), (58, 24)]), "#E9E0CC"); a.shape(rect(8, 24, 48, 6, 1), "#CFC2A6")
for x in (13, 25, 37, 49): a.shape(rect(x - 3, 30, 6, 20, 1), "#F6F0E2", 2)
a.shape(rect(6, 50, 52, 7, 2), "#CFC2A6"); a.shape(poly(ngon(32, 18, 4, 4)), "#2F6FD6", 1.4)

if __name__ == "__main__":
    here = os.path.dirname(os.path.abspath(__file__))
    res = os.path.join(here, "..", "..", "app", "src", "main", "res", "drawable")
    os.makedirs(os.path.join(here, "svg"), exist_ok=True)
    for a in ALL:
        open(os.path.join(here, "svg", a.name + ".svg"), "w").write(a.svg())
        open(os.path.join(res, "dig_" + a.name + ".xml"), "w").write(a.vector())
    print(len(ALL), "drawings")
