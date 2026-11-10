"""
LingoPod's icon from the designer's image (store/logo-source.png, 1024 x 1024, the green
rounded square at x 245-780, y 162-697). Uses the image's own pixels:

  - app icon: the square cropped just inside its rounded corners (the phone applies its own
    shape), on the same green gradient for the hidden bleed area
  - themed icon (Android 13+): the white drawing, lifted from the same pixels
  - Play Store: icon-512.png and feature-graphic-1024x500.png

    python3 tools/logo_from_image.py store/logo-source.png app/src/main/res store
"""
import sys
from PIL import Image, ImageDraw, ImageFont

src, res, store = sys.argv[1:4]
im = Image.open(src).convert("RGB")
# The square inside the rounded corners (the corner radius is ~95 px).
CROP = (277, 194, 747, 664)
art = im.crop(CROP)

# App icon art (shown in the adaptive icon's visible 72 dp; 432 px = sharp on any phone).
art.resize((432, 432), Image.LANCZOS).save(f"{res}/drawable-nodpi/ic_launcher_art.png")

# Themed icon: the white lines as an alpha mask.
mono = Image.new("RGBA", art.size, (255, 255, 255, 0))
px = art.load()
mp = mono.load()
for y in range(art.height):
    for x in range(art.width):
        r, g, b = px[x, y]
        white = min(r, g, b)          # the background is green: its red/blue stay low
        a = max(0, min(255, (white - 185) * 255 // 60))
        mp[x, y] = (255, 255, 255, a)
mono.resize((432, 432), Image.LANCZOS).save(f"{res}/drawable-nodpi/ic_launcher_mono_art.png")

# Play Store icon: full-bleed square (Google rounds the corners).
art.resize((512, 512), Image.LANCZOS).save(f"{store}/icon-512.png")

# Feature graphic 1024 x 500: the icon as designed (rounded square, soft shadow) and the name.
from PIL import ImageFilter
bg = im.getpixel((20, 20))
canvas = Image.new("RGB", (1024, 500), bg)
square = im.crop((245, 162, 780, 697)).resize((300, 300), Image.LANCZOS)
mask = Image.new("L", square.size, 0)
ImageDraw.Draw(mask).rounded_rectangle([0, 0, 299, 299], radius=54, fill=255)
shadow = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
ImageDraw.Draw(shadow).rounded_rectangle([95, 120, 395, 420], radius=54, fill=(20, 150, 100, 80))
shadow = shadow.filter(ImageFilter.GaussianBlur(24))
canvas.paste(shadow, (0, 0), shadow)
canvas.paste(square, (95, 95), mask)
d = ImageDraw.Draw(canvas)
d.text((450, 160), "LingoPod", font=ImageFont.truetype("/usr/share/fonts/opentype/inter/Inter-Light.otf", 104), fill=(88, 94, 98))
d.text((456, 300), "Learn languages with podcasts", font=ImageFont.truetype("/usr/share/fonts/opentype/inter/Inter-Regular.otf", 33), fill=(5, 150, 110))
canvas.save(f"{store}/feature-graphic-1024x500.png")
print("ok")
