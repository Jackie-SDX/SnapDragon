#!/usr/bin/env python3
"""Blender headless scene v2: three-zone medical short driven by timeline.json.

Zones (world space): body = mannequin bust with x-ray chest at origin,
tree = airway exterior (trachea/bronchi/cartilage) at x=+60,
bore = airway interior tube at x=-60. One camera, hard cuts per beat,
per-shot push-ins, optional DOF, Newtonian particle airflow with force
fields (wind + turbulence) and collision.

Run:  blender -b -P scene.py -- --project p.json --timeline t.json --out frames/
"""
import argparse
import json
import math
import os
import random
import sys
import time

import bpy
from mathutils import Vector

parser = argparse.ArgumentParser()
parser.add_argument("--project", required=True)
parser.add_argument("--timeline", required=True)
parser.add_argument("--out", required=True)
parser.add_argument("--samples", type=int, default=8)
parser.add_argument("--frame", type=int, default=None, help="render single frame (1-based)")
parser.add_argument("--denoise", action="store_true")
args, _unknown = parser.parse_known_args(sys.argv[sys.argv.index("--") + 1:])

project = json.load(open(args.project))
timeline = json.load(open(args.timeline))
FPS = timeline["fps"]
FRAMES = timeline["frames"]
BEATS = timeline["beats"]

p_beats = {b["id"]: b for b in project["beats"]}
for tb in BEATS:
    pb = p_beats[tb["id"]]
    tb["state"] = pb["state"]
    tb["camera"] = pb["camera"]
    tb["camera_end"] = pb.get("camera_end", pb["camera"])
    tb["target"] = pb.get("target", [0, 0, 0])
    tb["target_end"] = pb.get("target_end", tb["target"])
    tb["fstop"] = float(pb.get("fstop", 0) or 0)
    tb["labels"] = pb.get("labels", [])

# camera framing: portrait vertical FOV half-extent factor = (sensor/2)/lens
LENS = 35.0
SENSOR_HALF = 12.0  # mm; sensor_fit VERTICAL fits sensor_height (24mm) vertically
HALF_H_PER_UNIT = SENSOR_HALF / LENS  # half height at depth d = d * HALF_H_PER_UNIT
LABEL_REF_DEPTH = 10.0

ZONE_BODY = Vector((0.0, 0.0, 0.0))
ZONE_TREE = Vector((60.0, 0.0, 0.0))
ZONE_BORE = Vector((-60.0, 0.0, 0.0))


def clamp(v, a, b):
    return max(a, min(b, v))


def smoothstep(p):
    p = clamp(p, 0.0, 1.0)
    return p * p * (3 - 2 * p)


def ease_out_back(p):
    p = clamp(p, 0.0, 1.0)
    c1, c3 = 1.70158, 2.70158
    return 1 + c3 * (p - 1) ** 3 + c1 * (p - 1) ** 2


def beat_at(t):
    if t < BEATS[0]["start"]:
        return 0, None, BEATS[0]
    for i, b in enumerate(BEATS):
        if t <= b["end"]:
            return i, (BEATS[i - 1] if i > 0 else None), b
    return len(BEATS) - 1, (BEATS[-2] if len(BEATS) > 1 else None), BEATS[-1]


def state_at(t):
    idx, prev, cur = beat_at(t)
    if prev is None:
        return dict(cur["state"])
    span = max(cur["end"] - cur["start"], 1e-6)
    p = smoothstep((t - cur["start"]) / (span * 0.65))
    return {k: prev["state"][k] + (v - prev["state"][k]) * p for k, v in cur["state"].items()}


def lerp3(a, b, p):
    return [a[i] + (b[i] - a[i]) * p for i in range(3)]


def camera_at(t):
    """Hard cut per beat; within a beat ease from camera -> camera_end (push-in)."""
    idx, prev, cur = beat_at(t)
    p = smoothstep((t - cur["start"]) / max(cur["end"] - cur["start"], 1e-6))
    loc = lerp3(cur["camera"], cur["camera_end"], p)
    tgt = lerp3(cur["target"], cur["target_end"], p)
    loc = list(loc)
    loc[0] += 0.05 * math.sin(t * 0.7)
    loc[2] += 0.04 * math.cos(t * 0.55)
    return Vector(loc), Vector(tgt), cur


def label_scale_at(beat, label, t):
    if t < beat["start"] or t > beat["end"] + 0.01:
        return 0.0
    pop = float(label.get("pop", 0.2))
    local = t - beat["start"]
    if local < pop:
        return ease_out_back(local / pop)
    # optional early fade ("out" = local seconds when fade begins) so titles
    # can clear the frame before a push-in fills it with bright geometry
    default_out = beat["end"] - beat["start"] - 0.22
    out_start = float(label.get("out", default_out))
    if t - beat["start"] >= out_start:
        return max(0.0, 1.0 - smoothstep((t - beat["start"] - out_start) / 0.22))
    return 1.0


# ---------------------------------------------------------------- scene setup
bpy.ops.wm.read_factory_settings(use_empty=True)
scene = bpy.context.scene

FONT_PATH = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
font = bpy.data.fonts.load(FONT_PATH) if os.path.exists(FONT_PATH) else None


def emission_mat(name, color, strength):
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    if hasattr(m, "shadow_method"):
        m.shadow_method = "NONE"  # emissive objects must not cast shadows
    nt = m.node_tree
    nt.nodes.clear()
    out = nt.nodes.new("ShaderNodeOutputMaterial")
    em = nt.nodes.new("ShaderNodeEmission")
    em.inputs["Color"].default_value = (*color, 1)
    em.inputs["Strength"].default_value = strength
    nt.links.new(em.outputs["Emission"], out.inputs["Surface"])
    return m


def principled_mat(name, color, rough=0.45, subsurf=0.0):
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    b = m.node_tree.nodes["Principled BSDF"]
    b.inputs["Base Color"].default_value = (*color, 1)
    b.inputs["Roughness"].default_value = rough
    if subsurf > 0 and "Subsurface Weight" in b.inputs:
        b.inputs["Subsurface Weight"].default_value = subsurf
        b.inputs["Subsurface Radius"].default_value = (0.4, 0.12, 0.1)
    return m


def zone_empty(name, loc):
    e = bpy.data.objects.new(name, None)
    scene.collection.objects.link(e)
    e.location = loc
    return e


def parent_to(obj, parent):
    obj.parent = parent


def tube_between(p1, p2, r, name, mat, verts=32):
    p1, p2 = Vector(p1), Vector(p2)
    d = p2 - p1
    bpy.ops.mesh.primitive_cylinder_add(vertices=verts, radius=r, depth=d.length, location=(p1 + p2) / 2)
    o = bpy.context.object
    o.name = name
    o.rotation_euler = d.to_track_quat("Z", "Y").to_euler()
    bpy.ops.object.shade_smooth()
    o.data.materials.append(mat)
    return o


def area_light(name, loc, energy, color, size, target=(0, 0, 0), parent=None):
    ld = bpy.data.lights.new(name, "AREA")
    ld.energy = energy
    ld.color = color
    ld.size = size
    lo = bpy.data.objects.new(name, ld)
    scene.collection.objects.link(lo)
    lo.location = loc
    d = Vector(target) - Vector(loc)
    lo.rotation_euler = d.to_track_quat("-Z", "Y").to_euler()
    if parent is not None:
        lo.parent = parent
    return lo


world = bpy.data.worlds.new("W")
scene.world = world
world.use_nodes = True
bg = world.node_tree.nodes["Background"]
bg.inputs[0].default_value = (0.012, 0.02, 0.05, 1)
bg.inputs[1].default_value = 1.0

zb = zone_empty("ZoneBody", ZONE_BODY)
zt = zone_empty("ZoneTree", ZONE_TREE)
zz = zone_empty("ZoneBore", ZONE_BORE)

# =============================================================== BORE (interior tube, local origin)
# open tube + solidify (local Z = axis, rotated onto world Y)
bpy.ops.mesh.primitive_cylinder_add(vertices=72, radius=1.5, depth=6.0, end_fill_type="NOTHING")
wall = bpy.context.object
wall.name = "AirwayWall"
wall.rotation_euler = (math.pi / 2, 0, 0)
bpy.ops.object.shade_smooth()
sol = wall.modifiers.new("Wall", "SOLIDIFY")
sol.thickness = 0.62
sol.offset = 1.0
wall.modifiers.new("Collide", "COLLISION")
mat_wall = principled_mat("Tissue", (0.86, 0.42, 0.45), rough=0.5, subsurf=0.12)
wall.data.materials.append(mat_wall)
wall_calm = (0.86, 0.42, 0.45)
wall_inflamed = (0.88, 0.16, 0.13)
parent_to(wall, zz)

# smooth muscle band
bpy.ops.mesh.primitive_torus_add(major_radius=2.16, minor_radius=0.38, major_segments=72, minor_segments=24)
muscle = bpy.context.object
muscle.name = "SmoothMuscle"
muscle.rotation_euler = (math.pi / 2, 0, 0)
bpy.ops.object.shade_smooth()
mat_muscle = principled_mat("Muscle", (0.7, 0.25, 0.3), rough=0.4)
muscle.data.materials.append(mat_muscle)
muscle_calm = (0.7, 0.25, 0.3)
muscle_tight = (0.55, 0.08, 0.1)
parent_to(muscle, zz)

# lumen: deeper glow disc at the far end (no side column - keeps walls visible)
bpy.ops.mesh.primitive_circle_add(vertices=72, radius=1.47, fill_type="NGON")
back = bpy.context.object
back.name = "LumenBack"
back.rotation_euler = (-math.pi / 2, 0, 0)
back.location = (0, 2.88, 0)
mat_back = emission_mat("AirDeep", (0.55, 0.85, 1.0), 0.85)
back.data.materials.append(mat_back)
parent_to(back, zz)

# mucus blobs (radius driven relative to pinched lumen in apply_frame)
mat_mucus = principled_mat("Mucus", (0.93, 0.9, 0.6), rough=0.25)
_mb = mat_mucus.node_tree.nodes["Principled BSDF"]
if "Emission Color" in _mb.inputs:
    _mb.inputs["Emission Color"].default_value = (0.93, 0.9, 0.6, 1)
    _mb.inputs["Emission Strength"].default_value = 0.45
mucus_objs = []
mucus_specs = [
    ((0.0, -0.6, 0.15), 0.62),
    ((-0.1, 0.6, -0.2), 0.55),
    ((0.08, 1.6, 0.2), 0.5),
    ((-0.05, -1.8, -0.1), 0.58),
]
random.seed(7)
for pos, r in mucus_specs:
    bpy.ops.mesh.primitive_ico_sphere_add(subdivisions=3, radius=r, location=pos)
    o = bpy.context.object
    bpy.ops.object.shade_smooth()
    for v in o.data.vertices:
        v.co *= 1.0 + random.uniform(-0.10, 0.10)
    o.data.materials.append(mat_mucus)
    o.scale = (0, 0, 0)
    parent_to(o, zz)
    mucus_objs.append((o, Vector(pos), r))

# soft light inside the bore
_bd = bpy.data.lights.new("Bore", "POINT")
_bd.energy = 120
_bd.color = (1.0, 0.93, 0.85)
_bd.shadow_soft_size = 1.5
_bo = bpy.data.objects.new("Bore", _bd)
scene.collection.objects.link(_bo)
_bo.location = ZONE_BORE + Vector((0, -1.2, 0))

area_light("BoreKey", (-3, -4, 3), 300, (1.0, 0.97, 0.92), 4.0, target=(0, 0, 0), parent=zz)
area_light("BoreRim", (3, 1, 2.5), 260, (0.55, 0.7, 1.0), 3.0, target=(0, 0, 0.3), parent=zz)
# front fill: keeps the mouth rim + near outer wall reading as tissue, not
# as navy background (unlit wall otherwise matches the world ambient color)
area_light("BoreFill", (1.8, -3.6, 1.2), 90, (1.0, 0.94, 0.88), 5.0,
           target=(0, -2.6, 0), parent=zz)

# deep light so the far wall doesn't go pitch black
_dd = bpy.data.lights.new("BoreDeep", "POINT")
_dd.energy = 45
_dd.color = (1.0, 0.85, 0.8)
_dd.shadow_soft_size = 1.2
_do = bpy.data.objects.new("BoreDeep", _dd)
scene.collection.objects.link(_do)
_do.location = ZONE_BORE + Vector((0, 1.8, 0))

# ---- airflow particle system (Newtonian + wind/turbulence + wall collision)
mat_air = emission_mat("AirPart", (0.75, 0.95, 1.0), 1.2)
bpy.ops.mesh.primitive_ico_sphere_add(subdivisions=2, radius=1.0)
air_proto = bpy.context.object
air_proto.name = "AirProto"
air_proto.data.materials.append(mat_air)
air_proto.hide_render = True
air_proto.hide_viewport = True

bpy.ops.mesh.primitive_circle_add(vertices=32, radius=0.5, fill_type="NGON", location=(0, -3.05, 0))
air_emit = bpy.context.object
air_emit.name = "AirEmitter"
air_emit.rotation_euler = (-math.pi / 2, 0, 0)
parent_to(air_emit, zz)
air_emit.show_instancer_for_render = False
air_emit.show_instancer_for_viewport = False
_psm = air_emit.modifiers.new("Airflow", "PARTICLE_SYSTEM")
ps = air_emit.particle_systems[0]
pst = ps.settings
pst.count = 360
pst.frame_start = 1
pst.frame_end = FRAMES
pst.lifetime = 240
pst.emit_from = "FACE"
pst.physics_type = "NEWTON"
pst.normal_factor = 2.0
pst.factor_random = 0.5
pst.mass = 1.0
pst.drag_factor = 0.06
pst.effector_weights.gravity = 0.0
pst.render_type = "OBJECT"
pst.instance_object = air_proto
pst.particle_size = 0.018
pst.size_random = 0.5
ps.seed = 7

bpy.ops.object.effector_add(type="WIND", location=(0, -4.2, 0), rotation=(-math.pi / 2, 0, 0))
wind = bpy.context.object
wind.name = "AirWind"
wind.field.strength = 5.0
wind.field.flow = 0.4
parent_to(wind, zz)

bpy.ops.object.effector_add(type="TURBULENCE", location=(0, 0, 0))
turb = bpy.context.object
turb.name = "AirTurb"
turb.field.strength = 1.2
turb.field.size = 1.1
parent_to(turb, zz)

# =============================================================== BODY (mannequin bust, local origin)
mat_shell = principled_mat("Mannequin", (0.9, 0.91, 0.94), rough=0.45)
_ss = mat_shell.node_tree.nodes["Principled BSDF"]
if "Specular IOR Level" in _ss.inputs:
    _ss.inputs["Specular IOR Level"].default_value = 0.22

# torso: semi-transparent shell so the glowing airway reads as x-ray
mat_chest = principled_mat("ChestShell", (0.82, 0.87, 0.96), rough=0.35)
_cb = mat_chest.node_tree.nodes["Principled BSDF"]
_cb.inputs["Alpha"].default_value = 0.16
if "Specular IOR Level" in _cb.inputs:
    _cb.inputs["Specular IOR Level"].default_value = 0.22
mat_chest.blend_method = "BLEND"
if hasattr(mat_chest, "shadow_method"):
    mat_chest.shadow_method = "NONE"

bpy.ops.mesh.primitive_cube_add(location=(0, 0, 1.6))
torso = bpy.context.object
torso.name = "Torso"
torso.scale = (1.3, 0.7, 1.4)
bev = torso.modifiers.new("Bevel", "BEVEL")
bev.width = 0.28
bev.segments = 4
bpy.ops.object.shade_smooth()
torso.data.materials.append(mat_chest)
parent_to(torso, zb)

# shoulders + arms + neck + head (solid mannequin parts)
for sx in (-1, 1):
    bpy.ops.mesh.primitive_uv_sphere_add(radius=0.5, location=(sx * 1.45, 0, 2.75), segments=32, ring_count=16)
    o = bpy.context.object
    o.name = f"Shoulder{'L' if sx < 0 else 'R'}"
    bpy.ops.object.shade_smooth()
    o.data.materials.append(mat_shell)
    parent_to(o, zb)
    bpy.ops.mesh.primitive_cylinder_add(radius=0.3, depth=1.7, location=(sx * 1.66, 0, 1.85))
    a = bpy.context.object
    a.name = f"Arm{'L' if sx < 0 else 'R'}"
    a.rotation_euler = (0, sx * math.radians(14), 0)
    bpy.ops.object.shade_smooth()
    a.data.materials.append(mat_shell)
    parent_to(a, zb)

bpy.ops.mesh.primitive_cylinder_add(radius=0.34, depth=0.65, location=(0, 0, 2.75))
neck = bpy.context.object
neck.name = "Neck"
bpy.ops.object.shade_smooth()
neck.data.materials.append(mat_shell)
parent_to(neck, zb)

bpy.ops.mesh.primitive_uv_sphere_add(radius=0.85, location=(0, 0, 3.6), segments=48, ring_count=24)
head = bpy.context.object
head.name = "Head"
head.scale = (1.0, 0.95, 1.15)
bpy.ops.object.shade_smooth()
head.data.materials.append(mat_shell)
parent_to(head, zb)

# nostrils (dark ovals low on the face, motes converge here)
mat_nostril = principled_mat("Nostril", (0.05, 0.05, 0.06), rough=0.7)
for sx in (-1, 1):
    bpy.ops.mesh.primitive_uv_sphere_add(radius=0.06, location=(sx * 0.11, -0.75, 3.30), segments=16, ring_count=8)
    n = bpy.context.object
    n.name = f"Nostril{'L' if sx < 0 else 'R'}"
    n.scale = (1.0, 0.5, 0.7)
    bpy.ops.object.shade_smooth()
    n.data.materials.append(mat_nostril)
    parent_to(n, zb)

# interior glowing airway inside the chest (x-ray)
mat_body_air = emission_mat("BodyAir", (0.45, 0.8, 1.0), 1.4)
body_air = zone_empty("BodyAirRig", (0, 0, 0))
parent_to(body_air, zb)
parent_to(tube_between((0, 0, 3.3), (0, 0, 1.95), 0.15, "BodyTrachea", mat_body_air), body_air)
for z in (3.1, 2.8, 2.5, 2.2):
    bpy.ops.mesh.primitive_torus_add(major_radius=0.18, minor_radius=0.03, location=(0, 0, z), major_segments=32, minor_segments=8)
    r = bpy.context.object
    r.name = f"BodyRing{int(z * 10)}"
    bpy.ops.object.shade_smooth()
    r.data.materials.append(mat_body_air)
    parent_to(r, body_air)
for sx in (-1, 1):
    parent_to(tube_between((0, 0, 1.95), (sx * 0.5, 0, 1.45), 0.11, f"BodyBronch{'L' if sx < 0 else 'R'}", mat_body_air), body_air)

# lungs
mat_lung = principled_mat("Lung", (0.95, 0.55, 0.6), rough=0.5, subsurf=0.15)
_lb = mat_lung.node_tree.nodes["Principled BSDF"]
if "Emission Color" in _lb.inputs:
    _lb.inputs["Emission Color"].default_value = (0.95, 0.55, 0.6, 1)
    _lb.inputs["Emission Strength"].default_value = 0.18
lung_calm = (0.95, 0.55, 0.6)
lung_red = (0.9, 0.2, 0.18)
lungs = []
for sx in (-1, 1):
    bpy.ops.mesh.primitive_uv_sphere_add(radius=1.0, location=(sx * 0.6, 0, 1.6), segments=32, ring_count=16)
    lg = bpy.context.object
    lg.name = f"Lung{'L' if sx < 0 else 'R'}"
    bpy.ops.object.shade_smooth()
    lg.data.materials.append(mat_lung)
    parent_to(lg, zb)
    lungs.append((lg, Vector((sx * 0.6, 0, 1.6)), Vector((0.52, 0.38, 0.78))))

# trigger motes (keyframed convergence into the nose)
mat_trigger = emission_mat("Trigger", (1.0, 0.6, 0.15), 0.7)
triggers = []
random.seed(23)
NOSE = Vector((0, -0.74, 3.30))
for i in range(7):
    bpy.ops.mesh.primitive_ico_sphere_add(subdivisions=3, radius=random.uniform(0.10, 0.17))
    o = bpy.context.object
    o.name = f"Mote{i}"
    bpy.ops.object.shade_smooth()
    o.data.materials.append(mat_trigger)
    ang = i * (2 * math.pi / 7) + random.uniform(-0.3, 0.3)
    p0 = Vector((math.cos(ang) * 1.6, -2.9, 3.5 + math.sin(ang) * 1.3))
    triggers.append((o, p0, NOSE))
    o.scale = (0, 0, 0)
    parent_to(o, zb)

# body dust emitter (slow drifting motes, brownian physics)
bpy.ops.mesh.primitive_cube_add(location=(0, -1.6, 2.9))
body_dust_emit = bpy.context.object
body_dust_emit.name = "BodyDustEmit"
body_dust_emit.scale = (3.2, 2.2, 2.2)
parent_to(body_dust_emit, zb)
body_dust_emit.show_instancer_for_render = False
body_dust_emit.show_instancer_for_viewport = False

area_light("BodyKey", (-4, -5, 5.5), 480, (1.0, 0.97, 0.92), 4.0, target=(0, 0, 2.6), parent=zb)
area_light("BodyRim", (4.5, 2.5, 4.0), 400, (0.5, 0.65, 1.0), 3.0, target=(0, 0, 2.6), parent=zb)
area_light("BodyFill", (0, -6, 0.6), 80, (0.7, 0.8, 1.0), 5.0, target=(0, 0, 2.2), parent=zb)
area_light("BodyKick", (2, -3, -1), 100, (1.0, 0.55, 0.4), 3.0, target=(0, 0, 2.0), parent=zb)

# =============================================================== TREE (airway exterior, local origin)
mat_tissue = principled_mat("TreeTissue", (0.86, 0.42, 0.45), rough=0.5, subsurf=0.12)
tissue_calm = (0.86, 0.42, 0.45)
tissue_inflamed = (0.88, 0.16, 0.13)
mat_cart = principled_mat("Cartilage", (0.72, 0.8, 0.95), rough=0.3)
mat_tree_muscle = principled_mat("TreeMuscle", (0.7, 0.25, 0.3), rough=0.4)
mat_alv = principled_mat("Alveoli", (0.95, 0.6, 0.65), rough=0.55)

tree_body = zone_empty("TreeBody", (0, 0, 0))
parent_to(tree_body, zt)
tree_muscle = zone_empty("TreeMuscleRig", (0, 0, 0))
parent_to(tree_muscle, zt)

# trachea: z 4.3 -> 1.7, rounded top cap
bpy.ops.mesh.primitive_cylinder_add(vertices=48, radius=0.5, depth=2.6, location=(0, 0, 3.0))
trachea = bpy.context.object
trachea.name = "Trachea"
bpy.ops.object.shade_smooth()
trachea.data.materials.append(mat_tissue)
parent_to(trachea, tree_body)
bpy.ops.mesh.primitive_uv_sphere_add(radius=0.5, location=(0, 0, 4.3), segments=32, ring_count=16)
tcap = bpy.context.object
tcap.name = "TracheaCap"
tcap.scale = (1, 1, 0.6)
bpy.ops.object.shade_smooth()
tcap.data.materials.append(mat_tissue)
parent_to(tcap, tree_body)

# cartilage rings along the trachea
for i, z in enumerate((4.1, 3.8, 3.5, 3.2, 2.9, 2.6, 2.3, 2.0)):
    bpy.ops.mesh.primitive_torus_add(major_radius=0.545, minor_radius=0.075, location=(0, 0, z), major_segments=48, minor_segments=12)
    r = bpy.context.object
    r.name = f"CartRing{i}"
    bpy.ops.object.shade_smooth()
    r.data.materials.append(mat_cart)
    parent_to(r, tree_body)

# bronchial tree
CARINA = Vector((0, 0, 1.85))  # start primaries up inside the trachea to hide the joint seam
prim_ends = []
for sx in (-1, 1):
    e = Vector((sx * 0.8, 0, 0.5))
    prim_ends.append(e)
    parent_to(tube_between(CARINA, e, 0.34, f"Primary{'L' if sx < 0 else 'R'}", mat_tissue, 32), tree_body)
    # cartilage on primary bronchi
    mid = (CARINA + e) / 2
    axis = (e - CARINA).normalized()
    bpy.ops.mesh.primitive_torus_add(major_radius=0.38, minor_radius=0.055, location=mid, major_segments=40, minor_segments=10)
    r = bpy.context.object
    r.name = f"CartPrim{'L' if sx < 0 else 'R'}"
    r.rotation_euler = axis.to_track_quat("Z", "Y").to_euler()
    bpy.ops.object.shade_smooth()
    r.data.materials.append(mat_cart)
    parent_to(r, tree_body)
    # secondaries
    secA = Vector((sx * 1.5, 0, -0.2))
    secB = Vector((sx * 1.65, 0, 0.3))
    parent_to(tube_between(e, secA, 0.2, f"SecA{'L' if sx < 0 else 'R'}", mat_tissue, 24), tree_body)
    parent_to(tube_between(e, secB, 0.18, f"SecB{'L' if sx < 0 else 'R'}", mat_tissue, 24), tree_body)
    # alveoli clusters at branch tips
    for tip in (secA, secB):
        for k in range(3):
            off = Vector((sx * 0.12 * (k - 1), 0.1 * (k - 1), -0.12 * k))
            bpy.ops.mesh.primitive_uv_sphere_add(radius=0.13, location=tip + off, segments=16, ring_count=8)
            al = bpy.context.object
            al.name = f"Alv{sx}_{tip.z}_{k}"
            bpy.ops.object.shade_smooth()
            al.data.materials.append(mat_alv)
            parent_to(al, tree_body)

# muscle bands (their own rig so they can bulge along the tube axis)
band_specs = [
    ((0, 0, 1.95), Vector((0, 0, 1)), 0.56),
    ((-0.4, 0, 1.175), (Vector(prim_ends[0]) - CARINA).normalized(), 0.39),
    ((0.4, 0, 1.175), (Vector(prim_ends[1]) - CARINA).normalized(), 0.39),
]
bands = []
for i, (pos, axis, major) in enumerate(band_specs):
    bpy.ops.mesh.primitive_torus_add(major_radius=major, minor_radius=0.1, location=pos, major_segments=48, minor_segments=12)
    bnd = bpy.context.object
    bnd.name = f"MuscleBand{i}"
    bnd.rotation_euler = axis.to_track_quat("Z", "Y").to_euler()
    bpy.ops.object.shade_smooth()
    bnd.data.materials.append(mat_tree_muscle)
    parent_to(bnd, tree_muscle)
    bands.append(bnd)

area_light("TreeKey", (-4, -6, 6), 500, (1.0, 0.97, 0.92), 4.0, target=(0, 0, 2.2), parent=zt)
area_light("TreeRim", (4, 3, 4.5), 420, (0.5, 0.65, 1.0), 3.0, target=(0, 0, 2.2), parent=zt)
area_light("TreeBounce", (0, -2, -2.5), 90, (1.0, 0.6, 0.55), 4.0, target=(0, 0, 1.8), parent=zt)

# =============================================================== dust prototypes + emitters
mat_dust = emission_mat("Dust", (1.0, 0.95, 0.85), 2.0)
bpy.ops.mesh.primitive_ico_sphere_add(subdivisions=1, radius=1.0)
dust_proto = bpy.context.object
dust_proto.name = "DustProto"
dust_proto.data.materials.append(mat_dust)
dust_proto.hide_render = True
dust_proto.hide_viewport = True


def dust_system(emit, count, seed, size):
    m = emit.modifiers.new("Dust", "PARTICLE_SYSTEM")
    s = emit.particle_systems[0].settings
    s.count = count
    s.frame_start = 1
    s.frame_end = FRAMES
    s.lifetime = FRAMES + 100
    s.emit_from = "VOLUME"
    s.physics_type = "NEWTON"
    s.normal_factor = 0.0
    s.factor_random = 0.02
    s.brownian_factor = 0.35
    s.drag_factor = 0.12
    s.effector_weights.gravity = 0.0
    s.render_type = "OBJECT"
    s.instance_object = dust_proto
    s.particle_size = size
    s.size_random = 0.6
    emit.particle_systems[0].seed = seed
    return s


dust_system(body_dust_emit, 55, 11, 0.016)

bpy.ops.mesh.primitive_cube_add(location=(0, -1.5, 2.5))
tree_dust_emit = bpy.context.object
tree_dust_emit.name = "TreeDustEmit"
tree_dust_emit.scale = (2.6, 2.4, 2.6)
parent_to(tree_dust_emit, zt)
tree_dust_emit.show_instancer_for_render = False
tree_dust_emit.show_instancer_for_viewport = False
dust_system(tree_dust_emit, 35, 13, 0.016)

# =============================================================== camera + labels
cam_data = bpy.data.cameras.new("Cam")
cam_data.lens = LENS
cam_data.sensor_fit = "VERTICAL"
cam_data.dof.use_dof = False
cam = bpy.data.objects.new("Cam", cam_data)
scene.collection.objects.link(cam)
scene.camera = cam

label_objs = []  # (obj, backing, beat_index, label_spec)
mat_label_bg = emission_mat("LabelBG", (0.012, 0.02, 0.05), 1.0)
for bi, b in enumerate(BEATS):
    for li, spec in enumerate(b.get("labels", [])):
        tc = bpy.data.curves.new(f"lbl_{bi}_{li}", type="FONT")
        tc.body = spec["text"]
        if font:
            tc.font = font
        tc.size = float(spec.get("size", 0.3))
        tc.align_x = "CENTER"
        tc.align_y = "CENTER"
        tc.extrude = 0.012
        tc.space_character = 1.04
        mat = emission_mat(f"lblmat_{bi}_{li}", spec.get("color", [1, 1, 1]), 1.0)
        tc.materials.append(mat)
        o = bpy.data.objects.new(f"Label_{bi}_{li}", tc)
        scene.collection.objects.link(o)
        o.parent = cam
        # dark backing pill so text stays readable over light geometry
        bpy.ops.mesh.primitive_plane_add(size=1.0, location=(0, 0, 0))
        bgp = bpy.context.object
        bgp.name = f"LabelBG_{bi}_{li}"
        pw = len(spec["text"]) * tc.size * 0.63 + tc.size * 0.55
        ph = tc.size * 1.62
        bgp.scale = (pw, ph, 1.0)
        bgp.location = (0, 0, -0.45)
        bgp.data.materials.append(mat_label_bg)
        bgp.parent = o
        label_objs.append((o, bgp, bi, spec))

# ---- render settings (EEVEE under xvfb software GL)
scene.render.engine = "BLENDER_EEVEE"
scene.render.resolution_x = timeline["render_width"]
scene.render.resolution_y = timeline["render_height"]
scene.render.resolution_percentage = 100
scene.render.fps = FPS
scene.render.image_settings.file_format = "PNG"
scene.render.film_transparent = False
ee = scene.eevee
ee.taa_render_samples = args.samples
ee.taa_samples = 16
if hasattr(ee, "use_gtao"):
    ee.use_gtao = True
    ee.gtao_distance = 3.0
    ee.gtao_factor = 1.15
if hasattr(ee, "use_bloom"):
    ee.use_bloom = True
    ee.bloom_threshold = 1.35
    ee.bloom_intensity = 0.06
    ee.bloom_radius = 6.0
scene.view_settings.view_transform = "Standard"
scene.view_settings.look = "None"

scene.frame_start = 1
scene.frame_end = FRAMES

TRIGGER_BEAT = next((b for b in BEATS if b["id"] == "trigger"), None)


def apply_frame(t):
    st = state_at(t)
    breath = 1.0 + 0.012 * math.sin(2 * math.pi * 0.45 * t)
    pinch = clamp(
        (1.0 - 0.42 * st["muscle"] - 0.30 * st["inflame"] - 0.30 * st["mucus"]) * breath,
        0.16, 1.05,
    )

    # ---- bore interior (local coords, parented to zone)
    wall.scale = (pinch, pinch, 1.0)
    sol.thickness = 0.62 * (1.0 + 0.45 * st["inflame"])
    b = mat_wall.node_tree.nodes["Principled BSDF"]
    b.inputs["Base Color"].default_value = (*lerp3(wall_calm, wall_inflamed, st["inflame"]), 1)

    muscle.scale = (pinch * 1.03, pinch * 1.03, 1.0 + 0.35 * st["muscle"])
    bm = mat_muscle.node_tree.nodes["Principled BSDF"]
    bm.inputs["Base Color"].default_value = (*lerp3(muscle_calm, muscle_tight, st["muscle"]), 1)

    _bd.energy = 55.0 + 80.0 * st["flow"]

    back.scale = (pinch, pinch, 1.0)

    hole_r = 1.5 * pinch
    pscl = max(pinch, 0.35)
    for o, pos, r in mucus_objs:
        final_r = 0.85 * pinch * st["mucus"]
        s = final_r / r
        o.scale = (s, s, s)
        o.location = Vector((pos[0] * pscl, pos[1], pos[2] * pscl))
        o.hide_render = st["mucus"] <= 0.02

    # airflow emitter tracks the hole so particles are never born outside the wall
    es = clamp(1.8 * pinch, 0.42, 1.8)
    air_emit.scale = (es, es, 1.0)
    wind.field.strength = 1.5 + 7.0 * st["flow"]
    turb.field.strength = 0.5 + 0.9 * st["flow"]

    # ---- exterior tree
    tree_body.scale = (pinch, pinch, 1.0)
    tree_muscle.scale = (pinch * 1.04, pinch * 1.04, 1.0)
    for bnd in bands:
        bnd.scale = (1.0, 1.0, 1.0 + 0.35 * st["muscle"])
    bt = mat_tissue.node_tree.nodes["Principled BSDF"]
    bt.inputs["Base Color"].default_value = (*lerp3(tissue_calm, tissue_inflamed, st["inflame"]), 1)
    btm = mat_tree_muscle.node_tree.nodes["Principled BSDF"]
    btm.inputs["Base Color"].default_value = (*lerp3(muscle_calm, muscle_tight, st["muscle"]), 1)

    # ---- body x-ray
    pinch_body = clamp(pinch, 0.45, 1.05)
    body_air.scale = (pinch_body, pinch_body, 1.0)
    ea = mat_body_air.node_tree.nodes["Emission"]
    ea.inputs["Color"].default_value = (*lerp3((0.45, 0.8, 1.0), (1.0, 0.45, 0.3), st["inflame"] * 0.7), 1)
    ea.inputs["Strength"].default_value = 1.3 + 0.5 * st["flow"]
    lk = (1.0 - 0.12 * st["inflame"]) * (1.0 + 0.02 * math.sin(2 * math.pi * 0.45 * t + 0.6))
    for lg, base_pos, base_s in lungs:
        lg.scale = (base_s.x * lk, base_s.y * lk, base_s.z * lk)
        lg.location = base_pos
    bl = mat_lung.node_tree.nodes["Principled BSDF"]
    lc = lerp3(lung_calm, lung_red, st["inflame"])
    bl.inputs["Base Color"].default_value = (*lc, 1)
    if "Emission Color" in bl.inputs:
        bl.inputs["Emission Color"].default_value = (*lc, 1)

    # ---- trigger motes converge into the nose
    if TRIGGER_BEAT is not None and TRIGGER_BEAT["start"] <= t <= TRIGGER_BEAT["end"]:
        prog = smoothstep((t - TRIGGER_BEAT["start"]) / max(TRIGGER_BEAT["end"] - TRIGGER_BEAT["start"], 1e-6))
        grow = math.sin(math.pi * clamp(prog, 0.0, 1.0) ** 0.8) ** 0.6 * 0.55
        for o, p0, p1 in triggers:
            o.location = p0.lerp(p1, prog)
            o.scale = (grow, grow, grow)
            o.rotation_euler = (t * 1.5, t * 1.2, 0)
    else:
        for o, p0, p1 in triggers:
            o.scale = (0, 0, 0)

    # ---- camera: hard cut + push-in + handheld micro-motion
    loc, tgt, cur = camera_at(t)
    cam.location = loc
    cam.rotation_euler = (tgt - loc).to_track_quat("-Z", "Y").to_euler()
    cam_dist = (loc - tgt).length
    if cur["fstop"] > 0:
        cam_data.dof.use_dof = True
        cam_data.dof.aperture_fstop = cur["fstop"]
        cam_data.dof.focus_distance = cam_dist
    else:
        cam_data.dof.use_dof = False

    # ---- labels: camera-parented, just outside the subject, constant apparent size
    depth = clamp(cam_dist - 3.2, 1.0, LABEL_REF_DEPTH)
    half_h = depth * HALF_H_PER_UNIT
    half_w = half_h * (timeline["render_width"] / timeline["render_height"])
    size_k = depth / LABEL_REF_DEPTH
    for o, bgp, bi, spec in label_objs:
        s = label_scale_at(BEATS[bi], spec, t)
        visible = s > 0.002
        o.hide_render = not visible
        bgp.hide_render = not visible
        if os.environ.get("OC_HIDE_LABELS"):
            o.hide_render = True
            bgp.hide_render = True
        if not visible:
            continue
        pos = spec.get("pos", [0, 0])
        xf = clamp(pos[0] if len(pos) >= 1 else 0.0, -0.9, 0.9)
        yf = pos[2] if len(pos) == 3 else (pos[1] if len(pos) == 2 else 0.0)
        yf = clamp(yf * 0.26 if len(pos) == 3 else yf, -0.9, 0.9)
        o.location = (xf * half_w, yf * half_h, -depth)
        o.scale = (s * size_k, s * size_k, s * size_k)


# ------------------------------------------------------------------ rendering
os.makedirs(args.out, exist_ok=True)
if os.environ.get("OC_HIDE_MUSCLE"):
    muscle.hide_render = True
if os.environ.get("OC_HIDE_BACK"):
    back.hide_render = True
if args.frame is not None:
    # step frames sequentially so the particle sim builds its cache up to target
    for f in range(1, args.frame + 1):
        scene.frame_set(f)
    apply_frame((args.frame - 1) / FPS)
    if os.environ.get("OC_RAY"):
        # ray_cast follows viewport visibility: park render-hidden labels behind camera
        for o, bgp, bi, spec in label_objs:
            if o.hide_render:
                o.hide_viewport = True
                bgp.hide_viewport = True
        air_emit.hide_viewport = True
        deps = bpy.context.evaluated_depsgraph_get()
        aspect = timeline["render_width"] / timeline["render_height"]
        origin = cam.matrix_world.translation
        if os.environ.get("OC_RAY_GRID"):
            x0, x1, dx, y0, y1, dy = (float(v) for v in os.environ["OC_RAY_GRID"].split(","))
            gxs = [x0 + i * dx for i in range(int((x1 - x0) / dx) + 1)]
            gys = [y0 + i * dy for i in range(int((y1 - y0) / dy) + 1)]
        else:
            gxs, gys = (0.86, 0.90, 0.94, 0.98, 1.0), (0.77, 0.80, 0.83, 0.86)
        grid = {}
        for xf in gxs:
            for yf in gys:
                v = Vector((
                    (xf - 0.5) * 2 * HALF_H_PER_UNIT * aspect,
                    (yf - 0.5) * 2 * HALF_H_PER_UNIT,
                    -1.0,
                ))
                d = (cam.matrix_world.to_3x3() @ v).normalized()
                hit, loc, _n, _i, ob, _m = scene.ray_cast(deps, origin, d, distance=1e4)
                if not hit:
                    ch = "."
                elif ob.hide_render:
                    ch = "h"
                elif ob.name == "AirwayWall":
                    ch = "W"
                elif ob.name.startswith("Label"):
                    ch = "L"
                else:
                    ch = ob.name[0]
                grid[(round(xf, 3), round(yf, 3))] = (ch, ob.name if hit else "-")
                print(f"RAY {xf:.3f},{yf:.3f} -> {ch} {ob.name if hit else ''} "
                      f"{'' if not hit else f'({loc.x:.2f},{loc.y:.2f},{loc.z:.2f})'}", flush=True)
        print("MAP")
        for yf in sorted(gys, reverse=True):
            row = "".join(grid[(round(x, 3), round(yf, 3))][0] for x in gxs)
            print(f"{yf:.3f} {row}")
        print("RAY_DONE", flush=True)
        sys.exit(0)
    scene.render.filepath = os.path.join(args.out, f"test_{args.frame:04d}.png")
    t0 = time.time()
    bpy.ops.render.render(write_still=True)
    print(f"SINGLE_FRAME_SECONDS {time.time() - t0:.2f}", flush=True)
else:
    def _hook(_s, _ctx):
        apply_frame((_s.frame_current - 1) / FPS)
    bpy.app.handlers.frame_change_pre.append(_hook)
    if os.environ.get("OC_FRAME_RANGE"):
        # partial re-render: step continuously from frame 1 so the particle
        # sim follows the exact same path as a full render, and write only the
        # requested frames as stills; mutating scene.frame_start/frame_end
        # would invalidate point caches and change the particle state
        rf0, rf1 = (int(v) for v in os.environ["OC_FRAME_RANGE"].split(","))
        t0 = time.time()
        for f in range(1, rf1 + 1):
            scene.frame_set(f)
            apply_frame((f - 1) / FPS)
            if f >= rf0:
                scene.render.filepath = os.path.join(args.out, f"f_{f:04d}.png")
                bpy.ops.render.render(write_still=True)
                print(f"RANGE_FRAME {f} {time.time() - t0:.1f}s", flush=True)
        print(f"RENDER_SECONDS {time.time() - t0:.2f}", flush=True)
        sys.exit(0)
    scene.frame_set(scene.frame_start)
    apply_frame((scene.frame_start - 1) / FPS)
    scene.render.filepath = os.path.join(args.out, "f_")
    t0 = time.time()
    bpy.ops.render.render(animation=True)
    print(f"RENDER_SECONDS {time.time() - t0:.2f}", flush=True)
