import sys
from PIL import Image, ImageDraw, ImageFont
out = sys.argv[1]
BG, TEAL, WHITE = (10, 11, 11), (140, 212, 190), (255, 255, 255)

def bubble(d, ox, oy, s):
    """The icon's shapes, from the 108-unit vector, scaled by s and moved to (ox, oy)."""
    X = lambda v: ox + v * s
    Y = lambda v: oy + v * s
    d.rounded_rectangle([X(30), Y(31), X(78), Y(67)], radius=10 * s, fill=TEAL)
    d.polygon([(X(40), Y(64)), (X(52), Y(66.5)), (X(38.2), Y(77)), (X(37), Y(76))], fill=TEAL)
    w = 4.4 * s
    for x, y0, y1 in [(41, 46.5, 51.5), (47.5, 42.5, 55.5), (54, 39.5, 58.5), (60.5, 43.5, 54.5), (67, 46.5, 51.5)]:
        d.line([(X(x), Y(y0)), (X(x), Y(y1))], fill=BG, width=int(w))
        for yy in (y0, y1):
            d.ellipse([X(x) - w / 2, Y(yy) - w / 2, X(x) + w / 2, Y(yy) + w / 2], fill=BG)

SS = 4
# Play Store icon: 512 x 512, full-bleed square (Google rounds the corners).
img = Image.new("RGB", (512 * SS, 512 * SS), BG)
d = ImageDraw.Draw(img)
s = 512 * SS / 80          # show the middle 80 units of 108 (the content is 30..78 x 31..77)
bubble(d, -14 * s, -14 * s, s)
img.resize((512, 512), Image.LANCZOS).save(f"{out}/icon-512.png")

# Feature graphic: 1024 x 500.
W, H = 1024, 500
img = Image.new("RGB", (W * SS, H * SS), BG)
d = ImageDraw.Draw(img)
s = 6.0 * SS
bubble(d, 80 * SS - 30 * s, H * SS / 2 - 54 * s, s)
bold = ImageFont.truetype("/usr/share/fonts/opentype/inter/Inter-Bold.otf", 104 * SS)
reg = ImageFont.truetype("/usr/share/fonts/opentype/inter/Inter-Regular.otf", 33 * SS)
d.text((420 * SS, 170 * SS), "LingoPod", font=bold, fill=WHITE)
d.text((425 * SS, 300 * SS), "Learn languages with podcasts", font=reg, fill=TEAL)
img.resize((W, H), Image.LANCZOS).save(f"{out}/feature-graphic-1024x500.png")
print("ok")
