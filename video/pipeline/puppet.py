#!/usr/bin/env python3
"""2D cutout-puppet renderer (Blender EEVEE headless, fully deterministic).

Character PNGs (alpha-keyed by prep_chars.py) become textured planes under an
orthographic camera. Motion is computed as pure functions of time — bob,
tremble, squash-and-stretch pops, slide-ins, flex pulses — and baked as
per-frame keyframes, so renders are bit-reproducible.

Same CLI contract as scene.py: --project --timeline --out --samples
(--chars points at the prepped RGBA PNG dir + manifest.json).
"""
import argparse
import json
import math
import os
import sys

import bpy


# ---------------------------------------------------------------- motion math

def clamp01(t):
    return max(0.0, min(1.0, t))


def ease_out_cubic(t):
    t = clamp01(t)
    return 1.0 - (1.0 - t) ** 3


def ease_out_back(t, s=1.70158):
    t = clamp01(t) - 1.0
    return t * t * ((s + 1.0) * t + s) + 1.0


def squash_stretch(t, dur=0.45, squash=0.22):
    """Event-driven cartoon squash -> overshoot stretch -> settle."""
    if t <= 0.0 or t > dur:
        return 1.0, 1.0
    p = t / dur
    if p < 0.35:
        k = ease_out_cubic(p / 0.35)
        sy = 1.0 - squash * k
        sx = 1.0 + squash * 0.8 * k
    elif p < 0.75:
        e = ease_out_back((p - 0.35) / 0.4)
        sy = (1.0 - squash) + ((1.0 + squash * 0.75) - (1.0 - squash)) * e
        sx = (1.0 + squash * 0.8) + ((1.0 - squash * 0.5) - (1.0 + squash * 0.8)) * e
    else:
        e = ease_out_cubic((p - 0.75) / 0.25)
        sy = (1.0 + squash * 0.75) * (1.0 - e) + 1.0 * e
        sx = (1.0 - squash * 0.5) * (1.0 - e) + 1.0 * e
    return sx, sy


def tremble(t, amp, seed):
    """Deterministic multi-sine jitter (scared characters)."""
    x = amp * (math.sin(t * 37.0 + seed) + 0.5 * math.sin(t * 53.3 + seed * 2.1))
    y = 0.7 * amp * (math.sin(t * 41.7 + seed * 1.3) + 0.5 * math.sin(t * 61.1 + seed * 3.7))
    rot = 8.0 * amp * math.sin(t * 47.3 + seed * 1.9)
    return x, y, rot


def bob(t, amp, freq, seed):
    return amp * math.sin(2.0 * math.pi * freq * t + seed)


def flex_pulse(t, amp, freq):
    s = amp * math.sin(2.0 * math.pi * freq * t)
    return 1.0 + s, 1.0 - 0.7 * s


def slide_ease(p):
    """Fast attack, slight overshoot, settle — classic cartoon entrance."""
    p = clamp01(p)
    if p < 0.7:
        return ease_out_cubic(p / 0.7) * 1.06
    return 1.06 - 0.06 * ease_out_cubic((p - 0.7) / 0.3)


# ---------------------------------------------------------------- scene setup

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
    scene.render.resolution_percentage = 100
    scene.render.fps = fps
    scene.render.film_transparent = False
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


def add_plane(name, mat, width, height, loc, z):
    bpy.ops.mesh.primitive_plane_add(size=1.0, location=(loc[0], loc[1], z))
    obj = bpy.context.active_object
    obj.name = name
    obj.scale = (width, height, 1.0)
    obj.data.materials.append(mat)
    return obj


def key_bool(obj, frame, value):
    obj.hide_render = value
    obj.keyframe_insert(data_path="hide_render", frame=frame)


def set_constant_interpolation(obj):
    ad = obj.animation_data
    if not ad or not ad.action:
        return
    for fc in ad.action.fcurves:
        if fc.data_path == "hide_render":
            for kp in fc.keyframe_points:
                kp.interpolation = "CONSTANT"


# ---------------------------------------------------------------- beat build

def build_beat(scene, beat, tbeat, char_meta, chars_dir, seed_base):
    """Create one beat's sprites/shadows; returns list of (obj, beatcfg, t0)."""
    t0 = tbeat["start"]
    dur = tbeat["end"] - tbeat["start"]
    made = []
    for j, cfg in enumerate(beat["chars"]):
        info = char_meta[cfg["ref"]]
        tex = os.path.join(chars_dir, info["png"])
        h = cfg.get("h", 3.2)
        w = h * info["aspect"]
        seed = seed_base + j * 17
        mat_sprite = make_material(f"m_{beat['id']}_{j}", tex_path=tex)
        sprite = add_plane(f"s_{beat['id']}_{j}", mat_sprite, w, h, (0, 0), 0.10)
        mat_sh = make_material(f"ms_{beat['id']}_{j}", color=(0.10, 0.10, 0.14), alpha=0.16)
        shadow = add_plane(f"sh_{beat['id']}_{j}", mat_sh, 1.0, 0.22, (0, 0), 0.02)
        made.append((sprite, shadow, cfg, w, h, seed, t0, dur))
    return made


def animate_beat(items, f_start, f_end, fps):
    """Bake per-frame transforms for one beat's sprites/shadows."""
    for sprite, shadow, cfg, w, h, seed, t0, dur in items:
        move = cfg.get("move", "idle")
        trem = cfg.get("tremble", 0.0)
        bob_amp = cfg.get("bob", 0.04)
        squash_at = cfg.get("squash_at", [])
        enter = cfg.get("enter", "cut")
        x_final = cfg.get("x", 0.0)
        y_feet = cfg.get("y", -1.5)
        yc = y_feet + h / 2.0
        slide_from = cfg.get("slide_from", 5.0)
        flex_amp = cfg.get("flex", 0.0)
        run = cfg.get("run", None)  # [x0, x1]

        for f in range(f_start, f_end + 1):
            t = f / fps - t0  # seconds since beat audio start
            sx = sy = 1.0
            dx = dy = 0.0
            rot = 0.0

            if enter == "slide_right":
                p = slide_ease(t / 0.55)
                dx += slide_from + (x_final - slide_from) * p - x_final
            if move == "tremble":
                tx, ty, trot = tremble(t, trem, seed)
                dx += tx
                dy += ty
                rot += trot
            if move == "flex":
                fsx, fsy = flex_pulse(t, flex_amp if flex_amp else 0.035, 2.1)
                sx *= fsx
                sy *= fsy
            if run:
                p = clamp01(t / dur)
                dx += run[0] + (run[1] - run[0]) * p - x_final
                dy += bob(t, 0.09, 3.2, seed)
                rot += 4.0 * math.sin(2.0 * math.pi * 3.2 * t + seed)

            dy += bob(t, bob_amp, 1.15, seed * 0.7)

            for st in squash_at:
                esx, esy = squash_stretch(t - st)
                sx *= esx
                sy *= esy

            if enter in ("slide_right", "pop"):
                esx, esy = squash_stretch(t, dur=0.5, squash=0.18)
                sx *= esx
                sy *= esy
                if enter == "pop" and t < 0.4:
                    e = ease_out_back(t / 0.4)
                    s = 0.0 + (1.0 - 0.0) * e
                    sx *= s if s > 0.01 else 0.01
                    sy *= s if s > 0.01 else 0.01

            sprite.location = (x_final + dx, yc + dy, 0.10)
            sprite.scale = (w * sx, h * sy, 1.0)
            sprite.rotation_euler = (0.0, 0.0, math.radians(rot))
            sprite.keyframe_insert(data_path="location", frame=f)
            sprite.keyframe_insert(data_path="scale", frame=f)
            sprite.keyframe_insert(data_path="rotation_euler", frame=f)

            shadow.location = (x_final + dx, y_feet + 0.02, 0.02)
            spread = 1.0 + (1.0 - sy) * 0.9
            shadow.scale = (w * 0.55 * spread * max(sx, 0.4), 0.22, 1.0)
            shadow.keyframe_insert(data_path="location", frame=f)
            shadow.keyframe_insert(data_path="scale", frame=f)


def animate_camera(cam, cam_data, beat, f_start, f_end):
    cams = beat.get("camera", {})
    s0, s1 = cams.get("scale", [8.0, 8.0])
    pan = cams.get("pan", [0.0, 0.2, 0.0, 0.2])  # x0 y0 x1 y1
    cam_data.ortho_scale = s0
    cam.location = (pan[0], pan[1], 10.0)
    cam.keyframe_insert(data_path="location", frame=f_start)
    cam_data.keyframe_insert(data_path="ortho_scale", frame=f_start)
    cam_data.ortho_scale = s1
    cam.location = (pan[2], pan[3], 10.0)
    cam.keyframe_insert(data_path="location", frame=f_end)
    cam_data.keyframe_insert(data_path="ortho_scale", frame=f_end)


# ---------------------------------------------------------------- main

def main():
    args = parse_args()
    project = json.load(open(args.project))
    timeline = json.load(open(args.timeline))
    manifest = json.load(open(os.path.join(args.chars, "manifest.json")))
    char_meta = {}
    for alias, stem in project.get("characters", {}).items():
        char_meta[alias] = manifest["characters"][stem]

    fps = timeline["fps"]
    W, H = timeline["render_width"], timeline["render_height"]
    view_h = float(project.get("stage", {}).get("view_height", 8.0))
    bg = project.get("stage", {}).get("bg", [0.97, 0.965, 0.95])

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
    add_plane("bgplane", mat_bg, view_h * 3, view_h * 3, (0, 0), -0.5)

    # visual windows: beat i covers [start_i, start_{i+1}) so cuts never flash bg
    tl_beats = timeline["beats"]
    windows = []
    for i, tb in enumerate(tl_beats):
        f0 = max(1, int(round(tb["start"] * fps)))
        if i + 1 < len(tl_beats):
            f1 = int(round(tl_beats[i + 1]["start"] * fps)) - 1
        else:
            f1 = timeline["frames"]
        windows.append((f0, max(f0, f1)))

    all_made = []
    for i, (beat, tb, (f0, f1)) in enumerate(zip(project["beats"], tl_beats, windows)):
        assert beat["id"] == tb["id"], f"beat id mismatch: {beat['id']} vs {tb['id']}"
        items = build_beat(scene, beat, tb, char_meta, args.chars, seed_base=3 + i * 31)
        for sprite, shadow, *_rest in items:
            key_bool(sprite, 1, True)
            key_bool(shadow, 1, True)
            key_bool(sprite, f0, False)
            key_bool(shadow, f0, False)
            if f1 + 1 <= timeline["frames"]:
                key_bool(sprite, f1 + 1, True)
                key_bool(shadow, f1 + 1, True)
            set_constant_interpolation(sprite)
            set_constant_interpolation(shadow)
        animate_beat(items, f0, f1, fps)
        animate_camera(cam, cam_data, beat, f0, f1)
        all_made.extend(items)
        print(f"beat {beat['id']}: frames {f0}-{f1} chars={len(items)}", flush=True)

    print(f"rendering {scene.frame_end} frames at {W}x{H} engine={scene.render.engine}", flush=True)
    bpy.ops.render.render(animation=True)


if __name__ == "__main__":
    main()
