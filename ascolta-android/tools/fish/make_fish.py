"""Builds 10 low-poly fish in Blender (bpy): mesh, flat colours, a 4-bone spine and a looping
"Swim" action each. Writes one .glb per fish, a .blend with all of them and a preview render.
Run: python make_fish.py <output folder>   (Blender 4.2+ / bpy)"""
import bpy, bmesh, math, os, sys
from mathutils import Vector, Quaternion, Matrix

OUT = sys.argv[-1]
for o in list(bpy.data.objects): bpy.data.objects.remove(o, do_unlink=True)  # the default cube, camera and light

def lin(h):
    h = h.lstrip('#'); c = [int(h[i:i + 2], 16) / 255 for i in (0, 2, 4)]
    return [x / 12.92 if x <= 0.04045 else ((x + 0.055) / 1.055) ** 2.4 for x in c] + [1.0]

def material(name, color, emit=0.0):
    m = bpy.data.materials.new(name)
    try: m.use_nodes = True
    except Exception: pass
    b = next(n for n in m.node_tree.nodes if n.type == 'BSDF_PRINCIPLED')
    b.inputs["Base Color"].default_value = lin(color)
    b.inputs["Roughness"].default_value = 0.55
    if emit:
        b.inputs["Emission Color"].default_value = lin(color); b.inputs["Emission Strength"].default_value = emit
    m.use_backface_culling = False  # fins are single sheets: exported double-sided
    m.diffuse_color = lin(color)
    return m

class Fish:
    def __init__(self, name, colors, stations, nose, sides=8):
        """stations: (x, half width, half height, centre z) from the head to the tail root; nose: x of the tip."""
        self.name, self.st, self.nose, self.n = name, stations, nose, sides
        self.bm = bmesh.new(); self.keys = list(colors); self.colors = colors
        self.body_faces = []  # (face, segment)
        self.tip = stations[-1][0]

    def mi(self, key): return self.keys.index(key)
    def at(self, x):
        s = self.st
        if x >= s[0][0]:
            t = (self.nose - x) / max(1e-6, self.nose - s[0][0]); return s[0][1] * t, s[0][2] * t, s[0][3]
        for a, b in zip(s, s[1:]):
            if b[0] <= x <= a[0]:
                t = (a[0] - x) / (a[0] - b[0]); return tuple(a[i] + (b[i] - a[i]) * t for i in (1, 2, 3))
        return s[-1][1:]

    def body(self, seg_colors, belly=None, nose_color=None):
        bm, n = self.bm, self.n
        rings = []
        for x, w, h, zc in self.st:
            rings.append([bm.verts.new((x, w * math.cos(math.pi / 2 + 2 * math.pi * k / n), zc + h * math.sin(math.pi / 2 + 2 * math.pi * k / n))) for k in range(n)])
        apex = bm.verts.new((self.nose, 0, self.st[0][3])); end = bm.verts.new((self.st[-1][0] - 0.01, 0, self.st[-1][3]))
        for k in range(n):
            f = bm.faces.new((apex, rings[0][k], rings[0][(k + 1) % n])); f.material_index = self.mi(nose_color or seg_colors[0]); self.body_faces.append((f, -1))
            f = bm.faces.new((end, rings[-1][(k + 1) % n], rings[-1][k])); f.material_index = self.mi(seg_colors[-1])
        for i in range(len(rings) - 1):
            for k in range(n):
                f = bm.faces.new((rings[i][k], rings[i + 1][k], rings[i + 1][(k + 1) % n], rings[i][(k + 1) % n]))
                f.material_index = self.mi(seg_colors[i]); self.body_faces.append((f, i))
        bmesh.ops.recalc_face_normals(bm, faces=bm.faces[:])
        if belly:
            for f, seg in self.body_faces:
                if f.normal.z < -0.45: f.material_index = self.mi(belly)
        return self

    def fin(self, pts, color):
        """A flat fin in the fish's middle plane: (x, z) outline."""
        f = self.bm.faces.new([self.bm.verts.new((x, 0, z)) for x, z in pts]); f.material_index = self.mi(color); return self

    def pectorals(self, x, z, pts, color, splay=40, inset=0.8):
        """A fin on each side: (back, up) outline in a plane swept back and out by [splay] degrees."""
        w = self.at(x)[0] * inset
        for side in (-1, 1):
            b = Vector((-math.cos(math.radians(splay)), side * math.sin(math.radians(splay)), 0))
            f = self.bm.faces.new([self.bm.verts.new(Vector((x, side * w, z)) + u * b + Vector((0, 0, v))) for u, v in pts]); f.material_index = self.mi(color)
        return self

    def ball(self, pos, r, color, u=6, v=4, scale=(1, 1, 1)):
        m = Matrix.Translation(pos) @ Matrix.Diagonal((*scale, 1))
        got = bmesh.ops.create_uvsphere(self.bm, u_segments=u, v_segments=v, radius=r, matrix=m)
        for f in {f for vert in got["verts"] for f in vert.link_faces}: f.material_index = self.mi(color)
        return self

    def cone(self, base, tip, r, color, sides=4):
        base, tip = Vector(base), Vector(tip); axis = (tip - base).normalized()
        a = axis.orthogonal().normalized(); b = axis.cross(a)
        ring = [self.bm.verts.new(base + r * (math.cos(2 * math.pi * k / sides) * a + math.sin(2 * math.pi * k / sides) * b)) for k in range(sides)]
        t = self.bm.verts.new(tip)
        for k in range(sides):
            f = self.bm.faces.new((ring[k], ring[(k + 1) % sides], t)); f.material_index = self.mi(color)
        return self

    def eyes(self, x, z, r, white="eye", pupil="pupil"):
        w = self.at(x)[0]
        for side in (-1, 1):
            self.ball((x, side * w * 0.86, z), r, white); self.ball((x + r * 0.15, side * (w * 0.86 + r * 0.62), z), r * 0.55, pupil, 5, 3)
        return self

    def finish(self, swim=1.0, length_to=None):
        """Makes the object, a 4-bone spine weighted along the body and the looping Swim action."""
        me = bpy.data.meshes.new(self.name); self.bm.normal_update(); self.bm.to_mesh(me); self.bm.free()
        for k in self.keys: me.materials.append(material(f"{self.name}_{k}", *((self.colors[k],) if isinstance(self.colors[k], str) else self.colors[k])))
        ob = bpy.data.objects.new(self.name, me); bpy.context.collection.objects.link(ob)
        xs = [v.co.x for v in me.vertices]; x0 = self.nose; x1 = length_to if length_to is not None else min(xs)
        arm = bpy.data.armatures.new(self.name + "_rig"); rig = bpy.data.objects.new(self.name + "_rig", arm); bpy.context.collection.objects.link(rig)
        bpy.context.view_layer.objects.active = rig; bpy.ops.object.mode_set(mode='EDIT')
        joints = [0.0, 0.34, 0.58, 0.8, 1.0]; names = ["head", "body", "tail", "fin"]; z = self.st[len(self.st) // 2][3]; prev = None
        for i, nm in enumerate(names):
            e = arm.edit_bones.new(nm); e.head = (x0 + (x1 - x0) * joints[i], 0, z); e.tail = (x0 + (x1 - x0) * joints[i + 1], 0, z); e.roll = 0
            if prev: e.parent = prev; e.use_connect = True
            prev = e
        bpy.ops.object.mode_set(mode='OBJECT')
        bone_of, frac_of = [], []
        groups = [ob.vertex_groups.new(name=nm) for nm in names]
        for v in me.vertices:
            t = (x0 - v.co.x) / (x0 - x1); u = 0.0
            for j in joints[1:4]:
                s = min(1.0, max(0.0, (t - (j - 0.09)) / 0.18)); u += s * s * (3 - 2 * s)
            k = min(int(u), 3); fr = u - k
            bone_of.append(k); frac_of.append(round(fr if k < 3 else 0.0, 3))
            groups[k].add([v.index], 1 - fr if k < 3 else 1.0, 'REPLACE')
            if k < 3 and fr > 0: groups[k + 1].add([v.index], fr, 'REPLACE')
        ob.parent = rig; mod = ob.modifiers.new("Armature", 'ARMATURE'); mod.object = rig
        amp = [-0.05, 0.09, 0.20, 0.30]; phase = [0.0, 0.5, 1.2, 1.9]
        for i, nm in enumerate(names):
            pb = rig.pose.bones[nm]; pb.rotation_mode = 'QUATERNION'
            axis = pb.bone.matrix_local.to_3x3().inverted() @ Vector((0, 0, 1))
            for fr in range(1, 26, 2):
                pb.rotation_quaternion = Quaternion(axis, swim * amp[i] * math.sin(2 * math.pi * (fr - 1) / 24 - phase[i]))
                pb.keyframe_insert("rotation_quaternion", frame=fr)
        rig.animation_data.action.name = self.name + "_Swim"
        # The same fish for the app's own 3D drawing: vertices with their spine bone, triangles
        # with their colour, the joints of the spine and how strongly it swims.
        me.calc_loop_triangles()
        cols = [self.colors[k] if isinstance(self.colors[k], str) else self.colors[k][0] for k in self.keys]
        APP[self.name.lower()] = {
            "v": [round(c, 4) for v in me.vertices for c in v.co], "b": bone_of, "f": frac_of,
            "t": [i for lt in me.loop_triangles for i in (*lt.vertices, lt.material_index)],
            "colors": cols, "keys": self.keys, "glow": [0 if isinstance(self.colors[k], str) else 1 for k in self.keys],
            "joints": [round(x0 + (x1 - x0) * j, 4) for j in joints[:4]], "swim": swim,
        }
        return rig, ob

APP = {}
EYE = {"eye": "#FFFFFF", "pupil": "#14181F"}
FISH = []
def add(f, **kw): FISH.append(f.finish(**kw))

# 1. Clownfish: orange with white bands.
f = Fish("Clownfish", {"orange": "#F5822A", "white": "#FFFFFF", "dark": "#2A1D1A", **EYE},
         [(0.40, 0.07, 0.10, 0), (0.28, 0.12, 0.18, 0), (0.16, 0.14, 0.22, 0), (0.0, 0.14, 0.22, 0), (-0.12, 0.12, 0.20, 0), (-0.26, 0.08, 0.14, 0), (-0.38, 0.04, 0.07, 0)], 0.5)
f.body(["orange", "white", "orange", "white", "orange", "white"])
f.fin([(-0.37, 0.06), (-0.50, 0.17), (-0.60, 0.11), (-0.63, 0), (-0.60, -0.11), (-0.50, -0.17), (-0.37, -0.06)], "orange")
f.fin([(0.22, 0.18), (0.12, 0.35), (-0.02, 0.33), (-0.08, 0.26), (-0.20, 0.30), (-0.30, 0.20), (-0.30, 0.10)], "orange")
f.fin([(-0.04, -0.20), (-0.12, -0.33), (-0.27, -0.26), (-0.30, -0.10)], "orange").fin([(0.16, -0.20), (0.08, -0.34), (0.0, -0.20)], "orange")
f.pectorals(0.20, -0.05, [(0, 0.05), (0.14, 0.09), (0.20, 0), (0.14, -0.09), (0, -0.05)], "orange").eyes(0.36, 0.04, 0.035)
add(f)

# 2. Blue tang: a tall blue oval, black marking, yellow tail.
f = Fish("BlueTang", {"blue": "#1F5FD6", "navy": "#101B3D", "yellow": "#FFD11A", **EYE},
         [(0.42, 0.04, 0.09, 0), (0.30, 0.07, 0.22, 0), (0.12, 0.08, 0.30, 0), (-0.08, 0.08, 0.29, 0), (-0.26, 0.06, 0.18, 0), (-0.40, 0.03, 0.06, 0)], 0.5)
f.body(["blue", "blue", "navy", "navy", "blue"])
f.fin([(-0.39, 0.05), (-0.60, 0.20), (-0.54, 0), (-0.60, -0.20), (-0.39, -0.05)], "yellow")
f.fin([(0.26, 0.24), (0.10, 0.38), (-0.22, 0.34), (-0.34, 0.14), (-0.30, 0.10)], "blue").fin([(0.14, -0.28), (-0.02, -0.38), (-0.24, -0.33), (-0.34, -0.14), (-0.30, -0.10)], "blue")
f.pectorals(0.20, -0.04, [(0, 0.04), (0.13, 0.07), (0.17, -0.02), (0.08, -0.08), (0, -0.04)], "yellow").eyes(0.36, 0.06, 0.03)
add(f)

# 3. Goldfish: round body, big forked fan tail.
f = Fish("Goldfish", {"gold": "#FF9E1F", "light": "#FFD27A", "fin": "#FFB84D", **EYE},
         [(0.36, 0.09, 0.11, 0), (0.22, 0.17, 0.21, 0), (0.04, 0.20, 0.25, 0), (-0.14, 0.17, 0.21, 0), (-0.28, 0.09, 0.12, 0), (-0.36, 0.04, 0.06, 0)], 0.46)
f.body(["gold"] * 5, belly="light")
f.fin([(-0.35, 0.04), (-0.48, 0.20), (-0.72, 0.36), (-0.62, 0.10), (-0.56, 0), (-0.62, -0.10), (-0.72, -0.36), (-0.48, -0.20), (-0.35, -0.04)], "fin")
f.fin([(0.14, 0.24), (0.02, 0.42), (-0.16, 0.38), (-0.26, 0.22), (-0.26, 0.12)], "fin").fin([(-0.08, -0.22), (-0.14, -0.36), (-0.28, -0.26), (-0.28, -0.10)], "fin")
f.pectorals(0.16, -0.10, [(0, 0.04), (0.16, 0.06), (0.22, -0.04), (0.12, -0.12), (0, -0.04)], "fin", splay=50).eyes(0.32, 0.05, 0.04)
add(f, swim=1.15)

# 4. Angelfish: a tall thin triangle with long trailing fins.
f = Fish("Angelfish", {"silver": "#E6E9EE", "black": "#22252B", "fin": "#F4D35E", **EYE},
         [(0.30, 0.03, 0.07, 0), (0.18, 0.05, 0.20, 0), (0.02, 0.06, 0.28, 0), (-0.14, 0.05, 0.24, 0), (-0.26, 0.04, 0.12, 0), (-0.34, 0.02, 0.05, 0)], 0.38)
f.body(["silver", "black", "silver", "black", "silver"])
f.fin([(-0.33, 0.04), (-0.50, 0.16), (-0.46, 0), (-0.50, -0.16), (-0.33, -0.04)], "silver")
f.fin([(0.12, 0.22), (-0.10, 0.62), (-0.34, 0.86), (-0.22, 0.42), (-0.30, 0.12), (-0.22, 0.10)], "fin")
f.fin([(0.06, -0.26), (-0.12, -0.60), (-0.36, -0.82), (-0.22, -0.40), (-0.30, -0.12), (-0.22, -0.10)], "fin")
f.fin([(0.14, -0.22), (0.10, -0.60), (0.06, -0.24)], "fin")
f.pectorals(0.12, -0.02, [(0, 0.03), (0.12, 0.05), (0.15, -0.02), (0.07, -0.06), (0, -0.03)], "silver").eyes(0.25, 0.03, 0.028)
add(f, swim=0.8)

# 5. Pufferfish: a spiky ball.
f = Fish("Pufferfish", {"yellow": "#F2C14E", "cream": "#FFF3D6", "brown": "#B9772F", **EYE},
         [(0.26, 0.16, 0.15, 0), (0.12, 0.26, 0.25, 0), (-0.04, 0.29, 0.28, 0), (-0.20, 0.23, 0.22, 0), (-0.32, 0.10, 0.10, 0), (-0.38, 0.04, 0.04, 0)], 0.36)
f.body(["yellow"] * 5, belly="cream")
for x, w, h, zc in f.st[1:4]:
    for k in range(8):
        a = math.pi / 2 + 2 * math.pi * (k + 0.5) / 8; d = Vector((0, math.cos(a), math.sin(a)))
        f.cone((x - 0.07, w * 0.93 * d.y, h * 0.93 * d.z), (x - 0.07, w * 1.28 * d.y, h * 1.28 * d.z), 0.028, "brown")
f.fin([(-0.37, 0.04), (-0.50, 0.11), (-0.54, 0), (-0.50, -0.11), (-0.37, -0.04)], "brown")
f.fin([(-0.14, 0.22), (-0.24, 0.32), (-0.32, 0.20), (-0.30, 0.10)], "brown")
f.pectorals(0.04, -0.02, [(0, 0.03), (0.10, 0.06), (0.14, 0), (0.10, -0.06), (0, -0.03)], "brown", splay=70, inset=0.97).eyes(0.22, 0.09, 0.045)
add(f, swim=0.6)

# 6. Reef shark: long and grey, white underneath.
f = Fish("Shark", {"grey": "#6F8596", "white": "#F2F5F7", "dark": "#4A5B69", **EYE},
         [(0.62, 0.06, 0.05, -0.01), (0.42, 0.12, 0.11, 0), (0.18, 0.14, 0.14, 0), (-0.10, 0.12, 0.12, 0), (-0.36, 0.07, 0.08, 0), (-0.58, 0.03, 0.04, 0)], 0.78, sides=6)
f.body(["grey"] * 5, belly="white")
f.fin([(-0.56, 0.03), (-0.78, 0.36), (-0.70, 0.04), (-0.82, -0.18), (-0.56, -0.03)], "dark")
f.fin([(0.22, 0.13), (0.04, 0.40), (-0.06, 0.12)], "dark").fin([(-0.34, 0.08), (-0.44, 0.17), (-0.46, 0.06)], "dark").fin([(-0.30, -0.08), (-0.42, -0.17), (-0.44, -0.06)], "dark")
f.pectorals(0.26, -0.08, [(0, 0.0), (0.26, -0.16), (0.14, 0.0)], "dark", splay=60).eyes(0.52, 0.04, 0.022, white="pupil")
add(f, swim=1.1)

# 7. Betta: small body, huge flowing fins.
f = Fish("Betta", {"purple": "#6C3FC4", "red": "#E0336B", "pink": "#FF7AA8", **EYE},
         [(0.30, 0.05, 0.07, 0), (0.18, 0.08, 0.12, 0), (0.02, 0.09, 0.13, 0), (-0.14, 0.07, 0.11, 0), (-0.26, 0.04, 0.07, 0)], 0.38, sides=6)
f.body(["purple"] * 4)
f.fin([(-0.25, 0.06), (-0.40, 0.34), (-0.66, 0.40), (-0.78, 0.16), (-0.74, -0.14), (-0.62, -0.38), (-0.38, -0.32), (-0.25, -0.06)], "red")
f.fin([(0.10, 0.12), (-0.04, 0.44), (-0.26, 0.50), (-0.34, 0.30), (-0.24, 0.08)], "pink")
f.fin([(0.12, -0.12), (0.02, -0.44), (-0.22, -0.50), (-0.34, -0.30), (-0.24, -0.08)], "red").fin([(0.20, -0.10), (0.14, -0.40), (0.12, -0.11)], "pink")
f.pectorals(0.14, -0.02, [(0, 0.03), (0.12, 0.06), (0.16, -0.02), (0.08, -0.07), (0, -0.03)], "pink").eyes(0.26, 0.025, 0.026)
add(f, swim=1.2)

# 8. Neon tetra: tiny and slim, a glowing blue stripe and a red tail end.
f = Fish("NeonTetra", {"silver": "#C9D4DB", "neon": ("#28D7FF", 1.5), "red": "#E8323C", "clear": "#DCEAF2", **EYE},
         [(0.38, 0.04, 0.06, 0), (0.24, 0.06, 0.10, 0), (0.06, 0.07, 0.11, 0), (-0.14, 0.06, 0.09, 0), (-0.30, 0.03, 0.05, 0)], 0.46, sides=6)
f.body(["silver", "silver", "red", "red"])
for face, seg in f.body_faces:
    if abs(face.normal.z) < 0.5 and face.calc_center_median().z > 0.0 and seg in (-1, 0, 1, 2): face.material_index = f.mi("neon")
f.fin([(-0.29, 0.04), (-0.46, 0.14), (-0.40, 0), (-0.46, -0.14), (-0.29, -0.04)], "clear")
f.fin([(0.06, 0.10), (-0.04, 0.22), (-0.12, 0.09)], "clear").fin([(-0.06, -0.10), (-0.16, -0.20), (-0.26, -0.06)], "clear")
f.pectorals(0.20, -0.03, [(0, 0.02), (0.09, 0.04), (0.11, -0.02), (0, -0.02)], "clear").eyes(0.33, 0.02, 0.026)
add(f, swim=1.2)

# 9. Butterflyfish: a yellow disc with a black eye band and a pointed snout.
f = Fish("Butterflyfish", {"yellow": "#FFD21F", "white": "#FFF8E1", "black": "#1E1E22", **EYE},
         [(0.36, 0.025, 0.035, -0.03), (0.28, 0.05, 0.14, 0), (0.18, 0.07, 0.24, 0), (0.0, 0.08, 0.29, 0), (-0.18, 0.06, 0.22, 0), (-0.30, 0.03, 0.07, 0)], 0.5)
f.body(["white", "black", "yellow", "yellow", "yellow"], nose_color="white")
f.fin([(-0.29, 0.05), (-0.44, 0.13), (-0.47, 0), (-0.44, -0.13), (-0.29, -0.05)], "white")
f.fin([(0.20, 0.22), (0.04, 0.38), (-0.20, 0.34), (-0.30, 0.16), (-0.24, 0.10)], "yellow").fin([(0.10, -0.26), (-0.04, -0.38), (-0.22, -0.32), (-0.30, -0.14), (-0.24, -0.10)], "yellow")
f.fin([(-0.16, 0.20), (-0.22, 0.28), (-0.28, 0.20), (-0.26, 0.12)], "black")
f.pectorals(0.14, -0.04, [(0, 0.03), (0.12, 0.05), (0.15, -0.02), (0.07, -0.06), (0, -0.03)], "white").eyes(0.23, 0.05, 0.028, white="yellow")
add(f, swim=0.9)

# 10. Anglerfish: dark, toothy, with a glowing lure.
f = Fish("Anglerfish", {"dark": "#2E2A3A", "belly": "#4A3F55", "tooth": "#F4F1E6", "lure": ("#9CFFE0", 6.0), "eye": "#D8FFF2", "pupil": "#0B0D12"},
         [(0.30, 0.20, 0.20, -0.02), (0.14, 0.26, 0.27, 0), (-0.04, 0.24, 0.25, 0), (-0.20, 0.15, 0.16, 0), (-0.32, 0.07, 0.08, 0), (-0.40, 0.03, 0.04, 0)], 0.38)
f.body(["dark"] * 5, belly="belly")
for k in range(7):
    y = -0.15 + 0.05 * k; f.cone((0.33, y, -0.10), (0.36, y, 0.0), 0.022, "tooth", 3); f.cone((0.34, y + 0.025, 0.02), (0.36, y + 0.025, -0.07), 0.02, "tooth", 3)
f.cone((0.16, 0, 0.25), (0.34, 0, 0.50), 0.014, "dark").cone((0.34, 0, 0.50), (0.48, 0, 0.42), 0.012, "dark").ball((0.50, 0, 0.40), 0.05, "lure")
f.fin([(-0.39, 0.03), (-0.54, 0.14), (-0.58, 0), (-0.54, -0.14), (-0.39, -0.03)], "belly")
f.fin([(-0.02, 0.24), (-0.10, 0.36), (-0.14, 0.24), (-0.20, 0.32), (-0.24, 0.18), (-0.30, 0.22), (-0.32, 0.08)], "belly").fin([(-0.14, -0.20), (-0.24, -0.28), (-0.32, -0.08)], "belly")
f.pectorals(0.0, -0.06, [(0, 0.05), (0.14, 0.08), (0.18, -0.02), (0.10, -0.10), (0, -0.05)], "belly", splay=60, inset=0.95).eyes(0.26, 0.12, 0.035)
add(f, swim=0.7)

# ---------------------------------------------------------------- export
os.makedirs(OUT, exist_ok=True)
scene = bpy.context.scene; scene.render.fps = 24; scene.frame_start = 1; scene.frame_end = 24
report = []
for rig, ob in FISH:
    bpy.ops.object.select_all(action='DESELECT'); rig.select_set(True); ob.select_set(True); bpy.context.view_layer.objects.active = rig
    path = os.path.join(OUT, ob.name + ".glb")
    bpy.ops.export_scene.gltf(filepath=path, export_format='GLB', use_selection=True, export_animations=True, export_animation_mode='ACTIVE_ACTIONS', export_yup=True, export_apply=False)
    ob.data.calc_loop_triangles()
    report.append((ob.name, len(ob.data.loop_triangles), len(ob.data.materials), os.path.getsize(path)))
for i, (rig, ob) in enumerate(FISH):
    rig.location = ((i % 5) * 1.7 - 3.4, 0, 0.75 - (i // 5) * 1.5)
bpy.ops.wm.save_as_mainfile(filepath=os.path.join(OUT, "LowPolyFish.blend"))
if os.environ.get("APP_ASSETS"):
    import json
    os.makedirs(os.environ["APP_ASSETS"], exist_ok=True)
    for name, data in APP.items(): json.dump(data, open(os.path.join(os.environ["APP_ASSETS"], name + ".json"), "w"), separators=(",", ":"))
for r in report: print("FISH %-14s %4d triangles  %d materials  %6d bytes" % r)
