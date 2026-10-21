#!/usr/bin/env python3
"""Articulated 2D puppet renderer (Blender EEVEE headless, deterministic).

Builds a pivot-joint FK rig from a split_char.py rig manifest: every part
plane's object origin sits on its joint pivot; parenting forms the chain
(body -> limbs -> extremities, body -> face features). Motion comes from
parametric gesture primitives evaluated per frame (rot/osc/squash/blink/
lipsync) and baked as keyframes — bit-reproducible.

CLI contract matches scene.py/puppet.py: --project --timeline --out --chars
(--chars = rig dir containing rig_manifest.json and part PNGs).
"""
import argparse
import json
import math
import os
import sys

import bpy
from mathutils import Matrix


# ---------------------------------------------------------------- easing

def clamp01(t):
    return max(0.0, min(1.0, t))


def ease_out_cubic(t):
    t = clamp01(t)
    return 1.0 - (1.0 - t) ** 3


def ease_in_out(t):
    t = clamp01(t)
    return t * t * (3.0 - 2.0 * t)


def ease_out_back(t, s=1.70158):
    t = clamp01(t) - 1.0
    return t * t * ((s + 1.0) * t + s) + 1.0


def squash_stretch(t, dur=0.5, amount=0.20):
    """Anticipation crouch -> overshoot stretch -> settle. Returns (sx, sy)."""
    if t <= 0.0 or t > dur:
        return 1.0, 1.0
    p = t / dur
    if p < 0.3:
        k = ease_out_cubic(p / 0.3)
        return 1.0 + amount * 0.9 * k, 1.0 - amount * k
    if p < 0.7:
        e = ease_out_back((p - 0.3) / 0.4)
        sx = (1.0 + amount * 0.9) + ((1.0 - amount * 0.5) - (1.0 + amount * 0.9)) * e
        sy = (1.0 - amount) + ((1.0 + amount * 0.8) - (1.0 - amount)) * e
        return sx, sy
    e = ease_out_cubic((p - 0.7) / 0.3)
    sx = (1.0 - amount * 0.5) * (1 - e) + e
    sy = (1.0 + amount * 0.8) * (1 - e) + e
    return sx, sy


# ---------------------------------------------------------------- gestures

def eval_joint(gestures, joint, t):
    """Sum parametric primitives for one joint at time t (seconds)."""
    deg = 0.0
    for g in gestures:
        if g["joint"] != joint:
            continue
        t0, t1 = g["t"]
        if t < t0 or t > t1:
            continue
        u = (t - t0) / max(t1 - t0, 1e-6)
        kind = g["action"]
        if kind == "rot":
            e = {"linear": u, "out": ease_out_cubic(u),
                 "inout": ease_in_out(u), "back": ease_out_back(u)}[g.get("ease", "out")]
            deg += g["deg"] * e
        elif kind == "rot_seq":
            keys = g["keys"]  # [[t, deg], ...] absolute times
            for (ka, va), (kb, vb) in zip(keys, keys[1:]):
                if ka <= t <= kb:
                    e = ease_in_out((t - ka) / max(kb - ka, 1e-6))
                    deg += va + (vb - va) * e
                    break
        elif kind == "osc":
            amp = g["amp"] * math.sin(2.0 * math.pi * g.get("hz", 2.0) * (t - t0) + g.get("phase", 0.0))
            env = ease_out_cubic(min(u * 4.0, 1.0)) * ease_out_cubic(min((1.0 - u) * 4.0, 1.0))
            deg += amp * env
    return deg


def eval_scale(gestures, joint, t):
    """Body squash channel: returns (sx, sy) multiplier."""
    sx = sy = 1.0
    for g in gestures:
        if g["joint"] != joint:
            continue
        t0, t1 = g["t"]
        if t < t0 or t > t1:
            continue
        if g["action"] == "squash_event":
            gsx, gsy = squash_stretch(t - t0, dur=g.get("dur", 0.5), amount=g.get("amount", 0.2))
            sx *= gsx
            sy *= gsy
        elif g["action"] == "squash_hold":
            a = g.get("amount", 0.15)
            e = ease_in_out(min(u := (t - t0) / max(t1 - t0, 1e-6), 1.0))
            hold = math.sin(math.pi * clamp01((t - t0) / (t1 - t0))) ** 0.5
            sx *= 1.0 + a * hold
            sy *= 1.0 - a * hold
    return sx, sy


def blink_active(blinks, t):
    for b in blinks:
        if b[0] <= t <= b[1]:
            return True
    return False


def mouth_state(project_mouth, t):
    """Returns 'talk' | 'o' | 'idle'."""
    for seg in project_mouth.get("o_segments", []):
        if seg[0] <= t <= seg[1]:
            return "o"
    for seg in project_mouth.get("talk_segments", []):
        if seg[0] <= t <= seg[1]:
            return "talk"
    return "idle"


def lipsync_scale_y(t, seg):
    """Deterministic syllable-rate mouth openness within a talk segment."""
    t0, t1 = seg
    u = (t - t0) / max(t1 - t0, 1e-6)
    env = ease_out_cubic(min(u * 6.0, 1.0)) * ease_out_cubic(min((1.0 - u) * 6.0, 1.0))
    wob = 0.5 + 0.5 * math.sin(2.0 * math.pi * 3.1 * (t - t0) + 0.7 * math.sin(2.0 * math.pi * 1.7 * (t - t0)))
    return 1.0 - 0.55 * env * (0.35 + 0.65 * wob)


# ---------------------------------------------------------------- blender

def parse_args():
    argv = sys.argv
    argv = argv[argv.index("--") + 1:] if "--" in argv else []
    ap = argparse.ArgumentParser()
    ap.add_argument("--project", required=True)
    ap.add_argument("--timeline", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--chars", required=True)
    ap.add_argument("--samples", type=int, default=4)
    return ap.parse_args(argv)


def new_scene(width, height, fps, samples):
    bpy.ops.wm.read_factory_settings(use_empty=True)
    scene = bpy.context.scene
    for eng in ("BLENDER_EEVEE_NEXT", "BLENDER_EEVEE"):
        try:
            scene.render.engine = eng
            break
        except TypeError:
            continue
    scene.render.resolution_x = width
    scene.render.resolution_y = height
    scene.render.fps = fps
    if hasattr(scene, "eevee"):
        scene.eevee.taa_render_samples = samples
    scene.view_settings.view_transform = "Standard"
    return scene


def make_material(name, tex_path=None, color=None, alpha=1.0):
    mat = bpy.data.materials.new(name)
    mat.use_nodes = True
    nt = mat.node_tree
    nt.nodes.clear()
    out = nt.nodes.new("ShaderNodeOutputMaterial")
    mix = nt.nodes.new("ShaderNodeMixShader")
    trans = nt.nodes.new("ShaderNodeBsdfTransparent")
    emit = nt.nodes.new("ShaderNodeEmission")
    emit.inputs["Strength"].default_value = 1.0
    if tex_path:
        tex = nt.nodes.new("ShaderNodeTexImage")
        tex.image = bpy.data.images.load(tex_path)
        tex.interpolation = "Linear"
        nt.links.new(tex.outputs["Color"], emit.inputs["Color"])
        nt.links.new(tex.outputs["Alpha"], mix.inputs["Fac"])
    else:
        emit.inputs["Color"].default_value = (*color, 1.0)
        mix.inputs["Fac"].default_value = alpha
    nt.links.new(trans.outputs["BSDF"], mix.inputs[1])
    nt.links.new(emit.outputs["Emission"], mix.inputs[2])
    nt.links.new(mix.outputs["Shader"], out.inputs["Surface"])
    if hasattr(mat, "blend_method"):
        mat.blend_method = "BLEND"
    if hasattr(mat, "show_transparent_back"):
        mat.show_transparent_back = False
    return mat


def px_to_world(px, py, origin, k):
    ox, oy = origin
    return ((px - ox) * k, (oy - py) * k)


def add_part(scene, name, png, info, origin, k, z_layer, parent=None):
    """Plane whose object origin sits exactly on the part's joint pivot."""
    w, h = info["size"]
    ppx, ppy = info["pivot"]
    mat = make_material(f"m_{name}", tex_path=png)
    bpy.ops.mesh.primitive_plane_add(size=1.0, location=(0, 0, 0))
    obj = bpy.context.active_object
    obj.name = name
    # scale verts to world size, then shift so origin = pivot pixel
    # (y flipped: pixel-down -> local-up). Do NOT use object scale: the
    # pivot offset would be multiplied by it and catapult the part away.
    obj.data.transform(Matrix.Diagonal((w * k, h * k, 1.0, 1.0)))
    dx = (w / 2.0 - ppx) * k
    dy = -(h / 2.0 - ppy) * k
    obj.data.transform(Matrix.Translation((dx, dy, 0.0)))
    wx, wy = px_to_world(ppx, ppy, origin, k)
    obj.location = (wx, wy, z_layer * 0.002)
    obj.data.materials.append(mat)
    if parent is not None:
        obj.parent = parent
        bpy.context.view_layer.update()
        obj.matrix_parent_inverse = parent.matrix_world.inverted()
        bpy.context.view_layer.update()
    return obj


def key_bool(obj, frame, value):
    obj.hide_render = value
    obj.keyframe_insert(data_path="hide_render", frame=frame)


def set_constant(obj):
    ad = obj.animation_data
    if not ad or not ad.action:
        return
    for fc in ad.action.fcurves:
        if fc.data_path == "hide_render":
            for kp in fc.keyframe_points:
                kp.interpolation = "CONSTANT"


# ---------------------------------------------------------------- main

PARENT_MAP = {
    "canister": "body", "brow_l": "body", "brow_r": "body",
    "eye_l": "body", "eye_r": "body", "mouth": "body",
    "arm_l": "body", "arm_r": "body", "leg_l": "body", "leg_r": "body",
    "hand_l": "arm_l", "hand_r": "arm_r",
    "shoe_l": "leg_l", "shoe_r": "leg_r",
}
JOINT_OF = {
    "body": "body", "canister": "canister",
    "brow_l": "brow_l", "brow_r": "brow_r",
    "arm_l": "shoulder_l", "hand_l": "wrist_l",
    "arm_r": "shoulder_r", "hand_r": "wrist_r",
    "leg_l": "hip_l", "shoe_l": "ankle_l",
    "leg_r": "hip_r", "shoe_r": "ankle_r",
}


def main():
    args = parse_args()
    project = json.load(open(args.project))
    timeline = json.load(open(args.timeline))
    manifest = json.load(open(os.path.join(args.chars, "rig_manifest.json")))

    fps = timeline["fps"]
    W, H = timeline["render_width"], timeline["render_height"]
    stage = project.get("stage", {})
    view_h = stage.get("view_height", 8.0)
    bg = stage.get("bg", [0.97, 0.965, 0.95])

    scene = new_scene(W, H, fps, args.samples)
    scene.frame_start = 1
    scene.frame_end = timeline["frames"]
    scene.render.filepath = os.path.join(args.out, "f_")
    os.makedirs(args.out, exist_ok=True)

    cam_data = bpy.data.cameras.new("cam")
    cam_data.type = "ORTHO"
    cam_data.sensor_fit = "VERTICAL"
    cam_data.ortho_scale = view_h
    cam = bpy.data.objects.new("cam", cam_data)
    cam.location = (0.0, 0.2, 10.0)
    scene.collection.objects.link(cam)
    scene.camera = cam

    mat_bg = make_material("bg", color=tuple(bg))
    bpy.ops.mesh.primitive_plane_add(size=1.0, location=(0, 0, -0.5))
    bgp = bpy.context.active_object
    bgp.name = "bgplane"
    bgp.scale = (view_h * 3, view_h * 3, 1)
    bgp.data.materials.append(mat_bg)

    world = manifest["world"]
    origin = tuple(world["origin_px"])
    k = world["char_height_world"] / float(world["char_height_px"])
    root = project["rig"]
    chars_dir = args.chars

    # build in dependency order: body first, then parents before children
    built = {}
    order = ["body"] + [p for p in PARENT_MAP]
    for name in order:
        if name not in manifest["parts"]:
            continue
        info = manifest["parts"][name]
        parent_obj = built.get(PARENT_MAP.get(name))
        z = info.get("z", 10)
        obj = add_part(scene, name, os.path.join(chars_dir, info["png"]), info,
                       origin, k, z, parent=parent_obj)
        built[name] = obj

    # blink overlay sprites (closed eyes), parented like open eyes
    for name in ("eye_l", "eye_r"):
        info = dict(manifest["parts"][name])
        info["png"] = f"{name}_closed.png"
        obj = add_part(scene, f"{name}_closed", os.path.join(chars_dir, info["png"]),
                       info, origin, k, info.get("z", 10) + 1, parent=built["body"])
        built[f"{name}_closed"] = obj
    # mouth 'O' swap
    info = dict(manifest["parts"]["mouth"])
    info["png"] = "mouth_o.png"
    built["mouth_o"] = add_part(scene, "mouth_o", os.path.join(chars_dir, info["png"]),
                                info, origin, k, info.get("z", 10) + 1, parent=built["body"])

    gestures = root.get("gestures", [])
    blinks = root.get("blinks", [])
    mouth_cfg = root.get("mouth", {})
    talk = mouth_cfg.get("talk_segments", [])
    root_y = root.get("y", -1.9)
    root_x = root.get("x", 0.0)
    # shift whole rig: body pivot world + offset
    bx, by = built["body"].location.x, built["body"].location.y
    dx, dy = root_x - bx, root_y - by
    for obj in built.values():
        if obj.parent is None:
            obj.location.x += dx
            obj.location.y += dy

    cams = project.get("camera", {})
    cs0, cs1 = cams.get("scale", [8.2, 7.2])
    campan = cams.get("pan", [0.0, 0.3, 0.0, 0.2])

    frames = timeline["frames"]
    for f in range(1, frames + 1):
        t = f / fps
        # body squash (root joint)
        sx, sy = eval_scale(gestures, "body", t)
        brot = eval_joint(gestures, "body", t)
        body = built["body"]
        body.scale = (sx, sy, 1.0)
        body.rotation_euler = (0, 0, math.radians(brot))
        body.keyframe_insert(data_path="scale", frame=f)
        body.keyframe_insert(data_path="rotation_euler", frame=f)

        for part, joint in JOINT_OF.items():
            if part == "body" or part not in built:
                continue
            deg = eval_joint(gestures, joint, t)
            built[part].rotation_euler = (0, 0, math.radians(deg))
            built[part].keyframe_insert(data_path="rotation_euler", frame=f)

        # brows: vertical raise
        for brow in ("brow_l", "brow_r"):
            raise_px = 0.0
            for g in gestures:
                if g["joint"] == brow and g["action"] == "raise":
                    t0, t1 = g["t"]
                    if t0 <= t <= t1:
                        e = ease_in_out((t - t0) / max(t1 - t0, 1e-6))
                        raise_px = g.get("px", 12) * math.sin(math.pi * e)
            obj = built[brow]
            wx, wy = px_to_world(*manifest["parts"][brow]["pivot"], origin, k)
            base = root_x + 0  # brows inherit body transform; use local delta
            # local y offset in parent(body) space:
            obj.location.z = obj.location.z  # keep z
            obj.delta_location = (0.0, raise_px * k, 0.0)
            obj.keyframe_insert(data_path="delta_location", frame=f)

        # blinks: swap open/closed visibility
        bl = blink_active(blinks, t)
        for eye in ("eye_l", "eye_r"):
            key_bool(built[eye], f, bl)
            key_bool(built[f"{eye}_closed"], f, not bl)
        set_constant(built["eye_l"]); set_constant(built["eye_r"])
        set_constant(built["eye_l_closed"]); set_constant(built["eye_r_closed"])

        # mouth: talk (scale_y) / O swap / idle
        st = mouth_state(mouth_cfg, t)
        mouth = built["mouth"]
        mo = built["mouth_o"]
        key_bool(mouth, f, st != "o")
        key_bool(mo, f, st == "o")
        set_constant(mouth); set_constant(mo)
        my = 1.0
        if st == "talk":
            for seg in talk:
                if seg[0] <= t <= seg[1]:
                    my = lipsync_scale_y(t, seg)
                    break
        elif st == "o":
            my = 1.0
        mouth.scale = (1.0, my, 1.0)
        mouth.keyframe_insert(data_path="scale", frame=f)

        # camera push
        u = f / max(frames, 1)
        cam_data.ortho_scale = cs0 + (cs1 - cs0) * ease_out_cubic(u)
        cam.location = (campan[0] + (campan[2] - campan[0]) * u,
                        campan[1] + (campan[3] - campan[1]) * u, 10.0)
        cam.keyframe_insert(data_path="location", frame=f)
        cam_data.keyframe_insert(data_path="ortho_scale", frame=f)

    print(f"rig parts={len(built)} frames={frames} engine={scene.render.engine}", flush=True)
    bpy.ops.render.render(animation=True)


if __name__ == "__main__":
    main()
