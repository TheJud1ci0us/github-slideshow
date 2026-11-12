"""Renders each fish's swim loop as a sprite sheet (8 frames, 4x2) in three looks: its own
colours, a second colouring, and gold. Run: python render_sprites.py LowPolyFish.blend <out folder> [fish]"""
import bpy, sys, math, os, colorsys
from mathutils import Vector
from PIL import Image

src, out = sys.argv[-2], sys.argv[-1]
CELL, FRAMES = 200, 8
# Second colouring: hue turned by this much; greys take the tint hue (None: stay grey).
ALT = {"Clownfish": (0.52, None), "BlueTang": (0.35, None), "Goldfish": (0.90, None), "Angelfish": (0.42, 0.58), "Pufferfish": (0.30, None),
       "Shark": (0.0, 0.52), "Betta": (0.60, None), "NeonTetra": (0.30, 0.75), "Butterflyfish": (0.47, None), "Anglerfish": (0.0, 0.0)}
bpy.ops.wm.open_mainfile(filepath=src)
sc = bpy.context.scene
sc.render.engine = 'CYCLES'; sc.cycles.samples = 48; sc.cycles.device = 'CPU'; sc.cycles.use_denoising = False
sc.render.resolution_x = sc.render.resolution_y = CELL; sc.render.film_transparent = True
sc.render.image_settings.file_format = 'PNG'; sc.render.image_settings.color_mode = 'RGBA'
sc.view_settings.view_transform = 'Standard'
w = bpy.data.worlds.new("w"); sc.world = w
try: w.use_nodes = True
except Exception: pass
bg = next(n for n in w.node_tree.nodes if n.type == 'BACKGROUND'); bg.inputs[0].default_value = (0.85, 0.93, 1, 1); bg.inputs[1].default_value = 1.1
cam = bpy.data.cameras.new("c"); cam.type = 'ORTHO'; co = bpy.data.objects.new("c", cam); sc.collection.objects.link(co); sc.camera = co
sun = bpy.data.lights.new("s", 'SUN'); sun.energy = 2.6; so = bpy.data.objects.new("s", sun); sc.collection.objects.link(so); so.rotation_euler = (math.radians(40), math.radians(10), math.radians(-30))

def bsdf(m): return next(n for n in m.node_tree.nodes if n.type == 'BSDF_PRINCIPLED')
def look(ob, kind, saved):
    shift, tint = ALT[ob.name]
    for m in ob.data.materials:
        b = bsdf(m); key = m.name.split("_", 1)[1]; r, g, bl, _ = saved[m.name][0]
        b.inputs["Base Color"].default_value = saved[m.name][0]; b.inputs["Metallic"].default_value = 0; b.inputs["Roughness"].default_value = 0.55
        b.inputs["Emission Color"].default_value = saved[m.name][1]
        if key in ("eye", "pupil") or kind == "base": continue
        h, s, v = colorsys.rgb_to_hsv(r, g, bl)
        if kind == "alt":
            if (s < 0.45 or ob.name in ("Shark", "Anglerfish")) and tint is not None and key not in ("white", "tooth"): h, s, v = tint, 0.6, min(1.0, v * 1.5 + 0.03)
            else: h = (h + shift) % 1.0
            c = (*colorsys.hsv_to_rgb(h, s, v), 1)
            b.inputs["Base Color"].default_value = c
            if b.inputs["Emission Strength"].default_value > 0: b.inputs["Emission Color"].default_value = c
        else:  # gold: the pattern stays, as lighter and darker gold
            k = 0.30 + 0.80 * (0.2126 * r + 0.7152 * g + 0.0722 * bl) ** 0.5
            b.inputs["Base Color"].default_value = (1.0 * k, 0.60 * k, 0.08 * k, 1); b.inputs["Metallic"].default_value = 0.55; b.inputs["Roughness"].default_value = 0.32

meshes = [o for o in bpy.data.objects if o.type == 'MESH']
only = os.environ.get("ONLY", "").split(",") if os.environ.get("ONLY") else None
os.makedirs(out, exist_ok=True)
for ob in meshes:
    if only and ob.name not in only: continue
    for o in meshes: o.hide_render = o is not ob
    pts = [ob.matrix_world @ Vector(c) for c in ob.bound_box]
    lo = Vector((min(p.x for p in pts), min(p.y for p in pts), min(p.z for p in pts))); hi = Vector((max(p.x for p in pts), max(p.y for p in pts), max(p.z for p in pts)))
    center = (lo + hi) / 2; cam.ortho_scale = max(hi.x - lo.x, hi.z - lo.z) * 1.10 + 0.12
    yaw, pitch = math.radians(22), math.radians(9)
    d = Vector((math.sin(yaw) * math.cos(pitch), -math.cos(yaw) * math.cos(pitch), math.sin(pitch)))
    co.location = center + d * 10; co.rotation_euler = (center - co.location).to_track_quat('-Z', 'Y').to_euler()
    saved = {m.name: (tuple(bsdf(m).inputs["Base Color"].default_value), tuple(bsdf(m).inputs["Emission Color"].default_value)) for m in ob.data.materials}
    for kind in ("base", "alt", "gold"):
        look(ob, kind, saved)
        sheet = Image.new("RGBA", (CELL * 4, CELL * 2))
        for i in range(FRAMES):
            sc.frame_set(1 + i * 3); sc.render.filepath = os.path.join(out, "_f.png"); bpy.ops.render.render(write_still=True)
            sheet.paste(Image.open(sc.render.filepath), ((i % 4) * CELL, (i // 4) * CELL))
        sheet.save(os.path.join(out, f"fish_{ob.name.lower()}_{kind}.webp"), quality=88, method=6)
    look(ob, "base", saved)
    print("DONE", ob.name, round(cam.ortho_scale, 2), flush=True)
os.remove(os.path.join(out, "_f.png"))
