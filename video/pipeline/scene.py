#!/usr/bin/env python3
"""Blender headless scene: stylized 3D airway animation driven by timeline.json.

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
BEATS = timeline["beats"]

p_beats = {b["id"]: b for b in project["beats"]}
for tb in BEATS:
    pb = p_beats[tb["id"]]
    tb["state"] = pb["state"]
    tb["camera"] = pb["camera"]
    tb["target"] = pb.get("target", [0, 0, 0])
    tb["labels"] = pb.get("labels", [])

# camera framing: portrait vertical FOV half-extent factor = (sensor/2)/lens
LENS = 35.0
SENSOR_HALF = 12.0  # mm; sensor_fit VERTICAL fits sensor_height (24mm) vertically
HALF_H_PER_UNIT = SENSOR_HALF / LENS  # half height at depth d = d * HALF_H_PER_UNIT
LABEL_REF_DEPTH = 10.0


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
    idx, prev, cur = beat_at(t)
    if prev is None:
        loc, tgt = list(cur["camera"]), cur["target"]
    else:
        p = smoothstep((t - cur["start"]) / max(cur["end"] - cur["start"], 1e-6))
        loc = lerp3(prev["camera"], cur["camera"], p)
        tgt = lerp3(prev["target"], cur["target"], p)
    loc = list(loc)
    loc[0] += 0.06 * math.sin(t * 0.7)
    loc[2] += 0.05 * math.cos(t * 0.55)
    return Vector(loc), Vector(tgt)


def label_scale_at(beat, label, t):
    if t < beat["start"] or t > beat["end"] + 0.01:
        return 0.0
    pop = float(label.get("pop", 0.2))
    local = t - beat["start"]
    if local < pop:
        return ease_out_back(local / pop)
    out_start = beat["end"] - 0.22
    if t >= out_start:
        return 1.0 - smoothstep((t - out_start) / 0.22)
    return 1.0


# ---------------------------------------------------------------- scene setup
bpy.ops.wm.read_factory_settings(use_empty=True)
scene = bpy.context.scene

FONT_PATH = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
font = bpy.data.fonts.load(FONT_PATH) if os.path.exists(FONT_PATH) else None


def emission_mat(name, color, strength):
    m = bpy.data.materials.new(name)
    m.use_nodes = True
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


world = bpy.data.worlds.new("W")
scene.world = world
world.use_nodes = True
bg = world.node_tree.nodes["Background"]
bg.inputs[0].default_value = (0.012, 0.02, 0.05, 1)
bg.inputs[1].default_value = 1.0

# ---- airway wall: open tube + solidify (local Z = axis, rotated onto world Y)
bpy.ops.mesh.primitive_cylinder_add(vertices=72, radius=1.5, depth=6.0, end_fill_type="NOTHING")
wall = bpy.context.object
wall.name = "AirwayWall"
wall.rotation_euler = (math.pi / 2, 0, 0)
bpy.ops.object.shade_smooth()
sol = wall.modifiers.new("Wall", "SOLIDIFY")
sol.thickness = 0.62
sol.offset = 1.0
mat_wall = principled_mat("Tissue", (0.86, 0.42, 0.45), rough=0.5, subsurf=0.12)
wall.data.materials.append(mat_wall)
wall_calm = (0.86, 0.42, 0.45)
wall_inflamed = (0.88, 0.16, 0.13)

# ---- smooth muscle band
bpy.ops.mesh.primitive_torus_add(major_radius=2.16, minor_radius=0.38, major_segments=72, minor_segments=24)
muscle = bpy.context.object
muscle.name = "SmoothMuscle"
muscle.rotation_euler = (math.pi / 2, 0, 0)
bpy.ops.object.shade_smooth()
mat_muscle = principled_mat("Muscle", (0.7, 0.25, 0.3), rough=0.4)
muscle.data.materials.append(mat_muscle)
muscle_calm = (0.7, 0.25, 0.3)
muscle_tight = (0.55, 0.08, 0.1)

# ---- lumen: emissive air column + deeper glow disc at the far end
bpy.ops.mesh.primitive_cylinder_add(vertices=72, radius=1.47, depth=5.9, end_fill_type="NOTHING")
lumen = bpy.context.object
lumen.name = "Lumen"
lumen.rotation_euler = (math.pi / 2, 0, 0)
mat_lumen = emission_mat("Air", (0.45, 0.8, 1.0), 0.38)
lumen.data.materials.append(mat_lumen)
lumen_calm = (0.45, 0.8, 1.0)
lumen_inflamed = (0.8, 0.68, 0.95)

bpy.ops.mesh.primitive_circle_add(vertices=72, radius=1.47, fill_type="NGON")
back = bpy.context.object
back.name = "LumenBack"
back.rotation_euler = (-math.pi / 2, 0, 0)
back.location = (0, 2.88, 0)
mat_back = emission_mat("AirDeep", (0.55, 0.85, 1.0), 0.6)
back.data.materials.append(mat_back)

# ---- mucus blobs (radius driven relative to pinched lumen in apply_frame)
mat_mucus = principled_mat("Mucus", (0.93, 0.9, 0.6), rough=0.25)
_mb = mat_mucus.node_tree.nodes["Principled BSDF"]
if "Emission Color" in _mb.inputs:
    _mb.inputs["Emission Color"].default_value = (0.93, 0.9, 0.6, 1)
    _mb.inputs["Emission Strength"].default_value = 0.32
mucus_objs = []
mucus_specs = [
    ((0.0, -0.6, 0.15), 0.62),
    ((-0.1, 0.6, -0.2), 0.55),
    ((0.08, 1.6, 0.2), 0.5),
    ((-0.05, -1.8, -0.1), 0.58),
]
random.seed(7)
for pos, r in mucus_specs:
    bpy.ops.mesh.primitive_ico_sphere_add(subdivisions=2, radius=r, location=pos)
    o = bpy.context.object
    bpy.ops.object.shade_smooth()
    for v in o.data.vertices:
        v.co *= 1.0 + random.uniform(-0.10, 0.10)
    o.data.materials.append(mat_mucus)
    o.scale = (0, 0, 0)
    mucus_objs.append((o, Vector(pos), r))

# ---- airflow particles
mat_air = emission_mat("AirPart", (0.75, 0.95, 1.0), 1.0)
bpy.ops.mesh.primitive_ico_sphere_add(subdivisions=2, radius=0.075)
proto = bpy.context.object
proto.data.materials.append(mat_air)
proto.hide_render = True
proto.hide_viewport = True
random.seed(11)
particles = []
for i in range(34):
    o = proto.copy()
    o.data = proto.data
    o.hide_render = False
    o.hide_viewport = False
    scene.collection.objects.link(o)
    ang = random.uniform(0, 2 * math.pi)
    frac = random.uniform(0.25, 0.95)
    phase = random.uniform(0, 1)
    particles.append((o, ang, frac, phase))

# ---- trigger motes (pollen / smoke)
mat_trigger = emission_mat("Trigger", (1.0, 0.72, 0.3), 1.6)
triggers = []
random.seed(23)
for i in range(7):
    bpy.ops.mesh.primitive_ico_sphere_add(subdivisions=2, radius=random.uniform(0.14, 0.26))
    o = bpy.context.object
    bpy.ops.object.shade_smooth()
    o.data.materials.append(mat_trigger)
    ang = i * (2 * math.pi / 7) + random.uniform(-0.3, 0.3)
    y = random.uniform(-2.0, 2.0)
    triggers.append((o, ang, y))
    o.scale = (0, 0, 0)

# ---- lights
def area(name, loc, energy, color, size=5.0):
    ld = bpy.data.lights.new(name, "AREA")
    ld.energy = energy
    ld.color = color
    ld.size = size
    lo = bpy.data.objects.new(name, ld)
    scene.collection.objects.link(lo)
    lo.location = loc
    d = -Vector(loc)
    lo.rotation_euler = d.to_track_quat("-Z", "Y").to_euler()
    return lo

area("Key", (-5.0, -7.0, 5.0), 520, (1.0, 0.96, 0.9))
area("RimBlue", (6.0, -2.0, 2.5), 400, (0.55, 0.7, 1.0))
area("RimWarm", (-1.5, 6.5, -2.0), 320, (1.0, 0.5, 0.4))

# soft light inside the bore so mucus / inner wall never go pitch black
_bore = bpy.data.lights.new("Bore", "POINT")
_bore.energy = 120
_bore.color = (1.0, 0.93, 0.85)
_bore.shadow_soft_size = 1.5
_bo = bpy.data.objects.new("Bore", _bore)
scene.collection.objects.link(_bo)
_bo.location = (0, -1.2, 0)

# ---- camera
cam_data = bpy.data.cameras.new("Cam")
cam_data.lens = LENS
cam_data.sensor_fit = "VERTICAL"
cam = bpy.data.objects.new("Cam", cam_data)
scene.collection.objects.link(cam)
scene.camera = cam

# ---- labels parented to the camera (screen-space fractions of frame edge)
label_objs = []  # (obj, beat_index, label_spec)
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
        label_objs.append((o, bi, spec))

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
scene.frame_end = timeline["frames"]


def apply_frame(t):
    st = state_at(t)
    breath = 1.0 + 0.012 * math.sin(2 * math.pi * 0.45 * t)
    pinch = clamp(
        (1.0 - 0.42 * st["muscle"] - 0.30 * st["inflame"] - 0.30 * st["mucus"]) * breath,
        0.16, 1.05,
    )

    # local XY = cross-section for tube meshes (axis is local Z)
    wall.scale = (pinch, pinch, 1.0)
    sol.thickness = 0.62 * (1.0 + 0.45 * st["inflame"])
    b = mat_wall.node_tree.nodes["Principled BSDF"]
    b.inputs["Base Color"].default_value = (*lerp3(wall_calm, wall_inflamed, st["inflame"]), 1)

    muscle.scale = (pinch * 1.03, pinch * 1.03, 1.0 + 0.35 * st["muscle"])
    bm = mat_muscle.node_tree.nodes["Principled BSDF"]
    bm.inputs["Base Color"].default_value = (*lerp3(muscle_calm, muscle_tight, st["muscle"]), 1)

    lumen.scale = (pinch, pinch, 1.0)
    back.scale = (pinch, pinch, 1.0)
    em = mat_lumen.node_tree.nodes["Emission"]
    em.inputs["Color"].default_value = (*lerp3(lumen_calm, lumen_inflamed, st["inflame"] * 0.55), 1)
    em.inputs["Strength"].default_value = 0.38 - 0.12 * st["mucus"]

    hole_r = 1.5 * pinch
    for o, pos, r in mucus_objs:
        final_r = 1.35 * pinch * st["mucus"]
        s = final_r / r
        o.scale = (s, s, s)
        o.location = Vector((pos[0] * pinch, pos[1], pos[2] * pinch))
        o.hide_render = st["mucus"] <= 0.02

    lr = hole_r * 0.82
    speed = st["flow"] * 3.6
    for o, ang, frac, phase in particles:
        prog = (phase + t * speed / 5.6) % 1.0
        y = -2.75 + prog * 5.5
        rad = lr * frac
        o.location = (rad * math.cos(ang), y, rad * math.sin(ang))
        s = clamp(0.4 + st["flow"], 0.25, 1.1)
        o.scale = (s, s, s)

    # trigger motes only inside the trigger beat
    tstart = tend = None
    for b_ in BEATS:
        if b_["id"] == "trigger":
            tstart, tend = b_["start"], b_["end"]
    if tstart is not None and tstart <= t <= tend:
        prog = smoothstep((t - tstart) / max(tend - tstart, 1e-6))
        rad = 4.4 - 2.1 * prog
        grow = math.sin(math.pi * clamp(prog, 0, 1)) ** 0.5
        for o, ang, y in triggers:
            o.location = (rad * math.cos(ang), y, rad * math.sin(ang))
            s = 0.9 * grow
            o.scale = (s, s, s)
            o.rotation_euler = (t * 1.5 + ang, t * 1.2, 0)
    else:
        for o, ang, y in triggers:
            o.scale = (0, 0, 0)

    loc, tgt = camera_at(t)
    cam.location = loc
    cam.rotation_euler = (tgt - loc).to_track_quat("-Z", "Y").to_euler()

    # labels: keep in front of geometry, constant apparent size
    cam_dist = (loc - tgt).length
    depth = clamp(cam_dist - 3.2, 3.0, LABEL_REF_DEPTH)
    half_h = depth * HALF_H_PER_UNIT
    half_w = half_h * (timeline["render_width"] / timeline["render_height"])
    size_k = depth / LABEL_REF_DEPTH
    for o, bi, spec in label_objs:
        s = label_scale_at(BEATS[bi], spec, t)
        pos = spec.get("pos", [0, 0])
        xf = clamp(pos[0] if len(pos) >= 1 else 0.0, -0.9, 0.9)
        yf = pos[2] if len(pos) == 3 else (pos[1] if len(pos) == 2 else 0.0)
        yf = clamp(yf * 0.26 if len(pos) == 3 else yf, -0.9, 0.9)
        o.location = (xf * half_w, yf * half_h, -depth)
        o.scale = (s * size_k, s * size_k, s * size_k)


# ------------------------------------------------------------------ rendering
os.makedirs(args.out, exist_ok=True)
if args.frame is not None:
    scene.frame_set(args.frame)
    apply_frame((args.frame - 1) / FPS)
    scene.render.filepath = os.path.join(args.out, f"test_{args.frame:04d}.png")
    t0 = time.time()
    bpy.ops.render.render(write_still=True)
    print(f"SINGLE_FRAME_SECONDS {time.time() - t0:.2f}", flush=True)
else:
    def _hook(_s, _ctx):
        apply_frame((_s.frame_current - 1) / FPS)
    bpy.app.handlers.frame_change_pre.append(_hook)
    scene.frame_set(scene.frame_start)
    apply_frame((scene.frame_start - 1) / FPS)
    scene.render.filepath = os.path.join(args.out, "f_")
    t0 = time.time()
    bpy.ops.render.render(animation=True)
    print(f"RENDER_SECONDS {time.time() - t0:.2f}", flush=True)
