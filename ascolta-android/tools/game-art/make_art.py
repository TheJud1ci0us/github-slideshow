#!/usr/bin/env python3
"""Draws the Aquarium vector artwork (coin, bait, tank decorations, icons) and writes it as SVG
(svg/, for previews) and as Android vector drawables (app/src/main/res/drawable/aq_*.xml).
Run: python3 tools/game-art/make_art.py   (from ascolta-android/)"""
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

# ---------------------------------------------------------------- coin, bait, float
a = art("coin")
a.shape(circ(32, 34, 22), "#C9892B"); a.shape(circ(32, 31, 22), "#F4C24D"); a.line(circ(32, 31, 16), "#C9892B", 1.8)
a.add("M22,31C26,24 34,24 39,31C34,38 26,38 22,31Z", "#C9892B"); a.add(poly([(39, 31), (45, 25), (45, 37)]), "#C9892B"); a.add(circ(27, 30, 1.5), "#F4C24D")
a.line("M17,24A17,17 0 0,1 30,13", "#FFFFFF", 2.4, 0.6)

a = art("bait_worm")
w = "M12,44C12,30 24,30 26,38C28,46 40,46 40,34C40,24 48,18 54,22"
a.line(w, OUT, 12); a.line(w, "#E58B8B", 7.5); a.line("M14,40C15,34 22,33 24,37", "#FFFFFF", 1.6, 0.5)
for x, y in ((19, 34), (33, 43), (41, 30)): a.line(f"M{x - 3},{y}l6,2", "#B65C5C", 1.4)
a.add(circ(52, 20.5, 1.4), OUT)

a = art("bait_shrimp")
a.shape("M50,22C40,10 20,12 14,26C10,36 16,48 28,50C24,44 24,38 30,36C38,34 46,30 50,22Z", "#FF8F6B")
a.add("M48,22C40,14 24,15 18,26C22,20 36,20 44,24Z", "#FFC2A8")
for d in ("M22,22L18,34", "M30,18L27,32", "M38,18L36,30"): a.line(d, "#D9603F", 1.5)
a.shape(poly([(28, 50), (18, 58), (22, 48), (12, 50), (22, 44)]), "#FF6F4A", 1.8)
a.line("M50,22C56,16 58,10 54,6M50,22C58,20 62,14 60,10", OUT, 1.6); a.add(circ(46, 21, 1.8), OUT)
for x in (30, 36, 42): a.line(f"M{x},33l2,7", "#D9603F", 1.6)

a = art("bait_lure")
a.line("M32,4V14", OUT, 2); a.shape(circ(32, 8, 3), "#C3CAD3", 1.6)
a.shape("M32,12C42,20 44,36 32,48C20,36 22,20 32,12Z", "#F4C24D"); a.add("M32,12C42,20 44,36 32,48Z", "#E0A92E")
a.shape(circ(32, 24, 3.2), "#D8324A", 1.4); a.line("M27,32h10M28,38h8", "#C9892B", 1.5)
a.line("M32,48C32,58 22,60 20,52", OUT, 2.4); a.sparkle(46, 16, 5); a.sparkle(18, 30, 3.5)

a = art("float")
a.line("M32,4V16", OUT, 2); a.shape(ell(32, 32, 14, 16), "#FFFFFF"); a.add("M18,32A14,16 0 0,1 46,32Z", "#E5484D"); a.line("M18,32H46", OUT, 2)
a.line(ell(32, 32, 14, 16), OUT); a.line("M32,48V58", OUT, 2); a.shine(ell(26, 24, 2.5, 5), 0.55)

a = art("tank")  # the aquarium, for the Profile card
a.shape(rect(6, 14, 52, 38, 6), "#8FD3F4"); a.add(rect(6, 14, 52, 9, 4), "#D9F1FB"); a.add("M8,46Q20,40 32,45Q46,50 56,44V50H8Z", "#F1D9A5")
a.line("M16,46C14,40 18,36 16,30M20,46C22,40 18,38 21,32", "#3FA35B", 2.2)
a.shape("M30,32C34,26 42,26 46,32C42,38 34,38 30,32Z", "#F5822A", 1.8); a.shape(poly([(30, 32), (24, 27), (24, 37)]), "#F5822A", 1.8); a.add(circ(42, 31, 1.3), OUT)
a.add(circ(48, 22, 1.6), "#FFFFFF", None, 0, 0.8); a.add(circ(52, 28, 1.1), "#FFFFFF", None, 0, 0.8)
a.line(rect(6, 14, 52, 38, 6), OUT); a.shape(rect(4, 50, 56, 7, 3), "#5B4636"); a.shine(rect(10, 26, 2.5, 14, 1.2), 0.5)

# ---------------------------------------------------------------- tank decorations (standing on y = 60)
a = art("deco_seaweed")
for x, c, t in ((22, "#2E8B57", 8), (32, "#3FA35B", 4), (42, "#2E8B57", 12)):
    d = f"M{x},60C{x - 8},50 {x + 8},42 {x},32C{x - 6},24 {x + 6},18 {x + 1},{t}"
    a.line(d, OUT, 8.5); a.line(d, c, 4.6); a.line(d, light(c, 0.35), 1.2, 0.7)

a = art("deco_redplant")
for an, h in ((-34, 34), (-16, 46), (0, 52), (16, 46), (34, 34)):
    r = math.radians(an); tx, ty = 32 + h * math.sin(r), 60 - h * math.cos(r)
    mx, my = 32 + h * 0.5 * math.sin(r), 60 - h * 0.5 * math.cos(r)
    a.shape(f"M32,60Q{mx - 7:.1f},{my:.1f} {tx:.1f},{ty:.1f}Q{mx + 7:.1f},{my:.1f} 32,60Z", "#C73E5C" if an % 32 else "#E0557A", 2)
    a.line(f"M32,60L{tx:.1f},{ty:.1f}", "#7A1F35", 1.1)

a = art("deco_coral_pink")
branches = (("M32,60V30", 9), ("M32,44C22,40 18,30 18,20", 7), ("M32,38C42,34 46,26 46,14", 7), ("M32,30C30,22 34,16 32,8", 6), ("M18,30C12,28 10,22 10,18", 5), ("M46,26C52,24 54,20 54,16", 5))
for d, wd in branches: a.line(d, OUT, wd + 4.4)
for d, wd in branches: a.line(d, "#FF7FA8", wd)
for x, y in ((32, 10), (18, 22), (46, 16), (10, 19), (54, 17)): a.add(circ(x, y, 1.6), "#FFD3E1")

a = art("deco_coral_brain")
a.shape("M8,60C6,40 18,28 32,28C46,28 58,40 56,60Z", "#F2A14B")
for d in ("M14,56C14,46 20,44 20,52C20,58 26,58 26,48C26,38 32,36 32,46C32,56 38,56 38,46C38,40 44,40 44,48C44,56 50,54 50,48", "M18,42C22,34 28,34 30,38M36,36C40,32 46,36 48,42"):
    a.line(d, "#B5651D", 2)
a.shine(ell(22, 35, 5, 2.5), 0.4)

a = art("deco_rocks")
a.shape(poly([(4, 60), (8, 44), (20, 36), (32, 42), (36, 60)]), "#7C8088"); a.add(poly([(20, 36), (32, 42), (36, 60), (22, 60)]), "#62666E")
a.shape(poly([(28, 60), (32, 40), (44, 30), (56, 38), (60, 60)]), "#8E939B"); a.add(poly([(44, 30), (56, 38), (60, 60), (46, 60)]), "#70757D")
a.line(poly([(8, 44), (20, 36)], False), "#FFFFFF", 1.6, 0.4); a.line(poly([(32, 40), (44, 30)], False), "#FFFFFF", 1.6, 0.4)
a.line("M10,60C10,54 14,52 14,56", "#3FA35B", 2.4)

a = art("deco_castle")
st = "#B9B2A3"
a.shape(rect(18, 34, 28, 26), st); a.shape(rect(8, 22, 14, 38), light(st, 0.12)); a.shape(rect(42, 22, 14, 38), dark(st, 0.08))
for x0 in (8, 42):
    a.shape(poly([(x0 - 2, 22), (x0 + 7, 6), (x0 + 16, 22)]), "#D9453C")
    a.shape(rect(x0 + 4.5, 30, 5, 8, 2.5), "#3A2E26", 1.5)
for x in (18, 25.3, 32.6, 40): a.shape(rect(x, 29, 6, 6), st, 2)
a.shape("M26,60V50A6,6 0 0,1 38,50V60Z", "#3A2E26", 2); a.line("M12,48h6M46,44h6M22,42h5", dark(st, 0.3), 1.4)

a = art("deco_chest")
a.shape(rect(10, 36, 44, 22, 3), "#A8703F"); a.add(rect(34, 37, 19, 20, 2), dark("#A8703F", 0.14))
a.turn(-22, 12, 34); a.shape(rect(10, 22, 44, 12, 3), light("#A8703F", 0.12)); a.turn(0)
a.shape(rect(12, 32, 40, 6, 1), "#3A2E26", 2)
for x, y, c in ((20, 33, "#F4C24D"), (28, 31, "#F4C24D"), (36, 33, "#F4C24D"), (44, 32, "#F4C24D"), (32, 28, "#D8324A"), (24, 28, "#2F6FD6")): a.shape(circ(x, y, 4), c, 1.5)
a.shape(rect(14, 36, 6, 22, 1), "#6F7680", 1.8); a.shape(rect(44, 36, 6, 22, 1), "#6F7680", 1.8); a.shape(rect(28, 40, 8, 9, 2), "#F4C24D", 1.8); a.sparkle(50, 18, 5)

a = art("deco_ship")
a.line("M30,44V8", OUT, 5); a.line("M30,44V8", "#8A5A33", 2.4)
a.shape(poly([(32, 10), (50, 26), (32, 30)]), "#E9E0CC"); a.line("M36,16L44,26M34,22L40,28", "#B9AB8E", 1.3)
a.shape("M4,40H58C56,52 48,60 38,60H18C10,58 6,50 4,40Z", "#8A5A33"); a.add("M34,41H57C55,51 48,59 38,59H34Z", dark("#8A5A33", 0.16))
a.line("M8,48H54M12,54H48", "#5B3A1E", 1.5); a.shape(circ(20, 49, 3.2), "#2B3C55", 1.5); a.shape(circ(44, 49, 3.2), "#2B3C55", 1.5)
a.shape(poly([(24, 60), (28, 50), (34, 54), (32, 60)]), "#1D1A2B", 1.6); a.line("M52,40C54,34 58,34 58,38", "#3FA35B", 2.2)

a = art("deco_amphora")
a.turn(28, 32, 50)
a.line("M22,24C12,24 12,38 22,38", OUT, 6); a.line("M22,24C12,24 12,38 22,38", "#C97B4A", 2.4); a.line("M42,24C52,24 52,38 42,38", OUT, 6); a.line("M42,24C52,24 52,38 42,38", "#C97B4A", 2.4)
a.shape("M26,16C26,24 18,28 18,40C18,52 24,60 32,60C40,60 46,52 46,40C46,28 38,24 38,16Z", "#C97B4A"); a.shape(rect(23, 9, 18, 8, 3), "#DD9666")
a.line(poly([(19, 38), (23, 34), (28, 39), (32, 34), (36, 39), (41, 34), (45, 38)], False), "#F4DFC0", 2); a.line(poly([(36, 20), (40, 30), (38, 40)], False), "#7A3F22", 1.3)
a.turn(0)

a = art("deco_anchor")
a.turn(18, 32, 40)
an = "M32,14V54M14,40C14,52 24,56 32,54C40,56 50,52 50,40M22,24H42"
a.line(an, OUT, 9); a.line(an, "#6F7680", 5); a.line(circ(32, 10, 5), OUT, 7); a.line(circ(32, 10, 5), "#6F7680", 3.2)
a.shape(poly([(14, 34), (9, 44), (19, 43)]), "#6F7680", 2); a.shape(poly([(50, 34), (55, 44), (45, 43)]), "#6F7680", 2); a.line("M30,18V50", "#AEB4BD", 1.3)
a.turn(0)

a = art("deco_starfish")
pts = []
for i in range(10):
    ang = math.radians(-90 + i * 36); r = 24 if i % 2 == 0 else 10; pts.append((32 + r * math.cos(ang), 40 + r * 0.8 * math.sin(ang)))
a.shape(poly(pts), "#FF8A5B"); a.add(poly([pts[0], pts[1], (32, 40), pts[9]]), "#FFB08A")
for i in range(0, 10, 2): a.line(f"M32,40L{(32 + pts[i][0]) / 2:.1f},{(40 + pts[i][1]) / 2:.1f}", "#D9603F", 1.4)
for x, y in ((32, 30), (40, 38), (24, 38), (36, 47), (28, 47)): a.add(circ(x, y, 1.3), "#FFE0CF")

a = art("deco_clam")
a.shape("M8,50C8,34 20,26 32,26C44,26 56,34 56,50Z", "#C9B8D8")
for x in (16, 24, 32, 40, 48): a.line(f"M32,48L{x},{31 + abs(32 - x) * 0.4:.1f}", "#9A86AE", 1.4)
a.shape("M8,50H56C56,56 46,60 32,60C18,60 8,56 8,50Z", "#A892BC"); a.shape(circ(32, 50, 6), "#FBF6F0", 1.8); a.shine(circ(30, 48, 1.8), 0.95); a.sparkle(44, 40, 3.5)

if __name__ == "__main__":
    here = os.path.dirname(os.path.abspath(__file__))
    res = os.path.join(here, "..", "..", "app", "src", "main", "res", "drawable")
    os.makedirs(os.path.join(here, "svg"), exist_ok=True)
    for a in ALL:
        open(os.path.join(here, "svg", a.name + ".svg"), "w").write(a.svg())
        open(os.path.join(res, "aq_" + a.name + ".xml"), "w").write(a.vector())
    print(len(ALL), "drawings")
