#!/usr/bin/env python3
"""Separate a flattened cartoon character into rig-ready part PNGs.

Workflow (repeatable, $0, Pillow+NumPy only):
  1. Author a rig spec JSON: polygons + pivots measured on the source image
     (use --preview to overlay the polygons and tune coordinates).
  2. Run this script: white-keys the background, masks each part polygon,
     erodes alpha edges 1px, inpaints removed features on the body layer
     (copy-from-offset patch), and writes cropped RGBA part PNGs + a rig
     manifest (pivots relative to each crop).

Manual preparation documented in video/RIGGING.md.
"""
import argparse
import json
import os

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

MN_OPAQUE, MN_CLEAR, CHROMA_MAX = 232, 250, 18


def key_white(arr):
    a = arr.astype(np.float32)
    mn, mx = a.min(axis=2), a.max(axis=2)
    whiteness = np.clip((mn - MN_OPAQUE) / float(MN_CLEAR - MN_OPAQUE), 0.0, 1.0)
    lowchroma = np.clip((CHROMA_MAX - (mx - mn)) / float(CHROMA_MAX), 0.0, 1.0)
    return 1.0 - whiteness * lowchroma


def erode3(alpha):
    padded = np.pad(alpha, 1, mode="edge")
    out = np.full_like(alpha, 1.0)
    for dy in (0, 1, 2):
        for dx in (0, 1, 2):
            out = np.minimum(out, padded[dy:dy + alpha.shape[0], dx:dx + alpha.shape[1]])
    return out


def poly_mask(size, poly, erode=True):
    m = Image.new("L", size, 0)
    ImageDraw.Draw(m).polygon([tuple(p) for p in poly], fill=255)
    a = np.asarray(m).astype(np.float32) / 255.0
    return erode3(a) if erode else a


def bbox_of(mask, pad=6):
    ys, xs = np.where(mask > 0.5)
    y0, y1 = int(ys.min()), int(ys.max()) + 1
    x0, x1 = int(xs.min()), int(xs.max()) + 1
    y0 = max(0, y0 - pad); x0 = max(0, x0 - pad)
    y1 = min(mask.shape[0], y1 + pad); x1 = min(mask.shape[1], x1 + pad)
    return x0, y0, x1, y1


def crop_part(rgba, mask, poly, pivot):
    x0, y0, x1, y1 = bbox_of(mask)
    out = np.asarray(rgba).copy()
    out[..., 3] = (out[..., 3].astype(np.float32) * mask).astype(np.uint8)
    crop = Image.fromarray(out[y0:y1, x0:x1], "RGBA")
    return crop, (pivot[0] - x0, pivot[1] - y0)


def dilate3(mask):
    padded = np.pad(mask, 1, mode="edge")
    out = np.zeros_like(mask)
    for dy in (0, 1, 2):
        for dx in (0, 1, 2):
            out = np.maximum(out, padded[dy:dy + mask.shape[0], dx:dx + mask.shape[1]])
    return out


def inpaint_patches(rgba, spec):
    """Erase each feature polygon on the body, fill with the body's flat blue.

    Sample the median of clearly-blue pixels in the body bbox so the fill
    matches the (gently varying) torso; face parts then sit exactly on top.
    """
    a = np.asarray(rgba).astype(np.float32).copy()
    H, W = a.shape[:2]
    r, g, b, _ = a[..., 0], a[..., 1], a[..., 2], a[..., 3]
    blueish = (b > 110) & (b > r + 40) & (b > g + 10) & (r < 160)
    fill = np.median(a[blueish], axis=0) if blueish.any() else np.array([40, 130, 210, 255], np.float32)
    fill[3] = 255.0
    for job in spec["inpaint"]:
        hole = next(p for p in spec["parts"] if p["name"] == job["hole"])
        hm = poly_mask((W, H), hole["poly"], erode=False) > 0.5
        soft = Image.fromarray((hm * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(3))
        sf = (np.asarray(soft).astype(np.float32) / 255.0)[..., None]
        patch = np.empty_like(a)
        patch[:] = fill
        a = a * (1.0 - sf) + patch * sf
    return a.astype(np.uint8)


def draw_closed_eye(size, w, h):
    """Cartoon closed-eye: thick downward-bowing arc (lid closed), black."""
    im = Image.new("RGBA", size, (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    black = (18, 18, 24, 255)
    lw = max(7, h // 6)
    d.arc([6, h * 0.15, w - 6, h * 1.05], start=195, end=345, fill=black, width=lw)
    mx = w // 2
    d.line([(mx - w // 5, h * 0.62), (mx - w // 3, h * 0.78)], fill=black, width=max(5, lw - 2))
    return im


def draw_mouth_o(size, w, h):
    """Surprised 'O' mouth matching the art: dark red cavity + teeth strip."""
    im = Image.new("RGBA", size, (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    outline = (18, 18, 24, 255)
    cavity = (96, 22, 34, 255)
    teeth = (250, 250, 250, 255)
    m = 8
    d.ellipse([m, m, w - m, h - m], fill=cavity, outline=outline, width=max(7, h // 9))
    d.rounded_rectangle([w * 0.24, m + h * 0.10, w * 0.76, m + h * 0.30],
                        radius=h // 8, fill=teeth)
    return im


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--spec", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--preview", help="write polygon overlay preview PNG here and exit")
    args = ap.parse_args()

    spec = json.load(open(args.spec))
    src = Image.open(spec["src"]).convert("RGB")
    W, H = src.size

    if args.preview:
        im = src.copy()
        d = ImageDraw.Draw(im)
        rng = np.random.default_rng(7)
        for p in spec["parts"]:
            c = tuple(int(v) for v in rng.integers(40, 255, 3))
            d.polygon([tuple(pt) for pt in p["poly"]], outline=c, width=6)
            px, py = p["pivot"]
            d.ellipse([px - 12, py - 12, px + 12, py + 12], fill=c)
            d.text((px + 14, py - 10), p["name"], fill=c)
        im.save(args.preview)
        print(f"preview -> {args.preview}")
        return

    os.makedirs(args.out, exist_ok=True)
    arr = np.asarray(src)
    alpha = erode3(key_white(arr))
    rgba = np.dstack([arr, (alpha * 255).astype(np.uint8)])

    k = spec.get("char_height_world", 3.6)
    manifest = {"src": spec["src"], "parts": {}}

    for p in spec["parts"]:
        mask = poly_mask((W, H), p["poly"])
        crop, pivot = crop_part(rgba, mask, p["poly"], p["pivot"])
        path = os.path.join(args.out, p["name"] + ".png")
        crop.save(path)
        manifest["parts"][p["name"]] = {
            "png": p["name"] + ".png",
            "pivot": [int(pivot[0]), int(pivot[1])],
            "pivot_src": [int(p["pivot"][0]), int(p["pivot"][1])],
            "size": list(crop.size),
            "z": p.get("z", 10),
        }
        print(f"{p['name']}: {crop.size} pivot={pivot}")

    # body = full keyed sprite minus every part polygon, features inpainted
    body_alpha = alpha.copy()
    features = {"brow_l", "brow_r", "eye_l", "eye_r", "mouth"}
    for name in spec["body_remove"]:
        p = next(q for q in spec["parts"] if q["name"] == name)
        if name in features:
            # exact (eroded) hole so the face part sits flush, no gray ring
            body_alpha = np.minimum(body_alpha, 1.0 - poly_mask((W, H), p["poly"], erode=True))
        else:
            # dilated hole for limbs so rotating parts never reveal a seam
            body_alpha = np.minimum(body_alpha, 1.0 - dilate3(poly_mask((W, H), p["poly"], erode=True)))
    body_rgba = np.dstack([arr, (erode3(body_alpha) * 255).astype(np.uint8)])
    body_rgba = inpaint_patches(body_rgba, spec)
    # crop body to remaining silhouette
    bmask = body_rgba[..., 3] > 128
    x0, y0, x1, y1 = bbox_of(bmask.astype(np.float32), pad=8)
    body_crop = Image.fromarray(body_rgba[y0:y1, x0:x1], "RGBA")
    body_crop.save(os.path.join(args.out, "body.png"))
    bp = spec.get("body_pivot", [(x0 + x1) // 2, y0 + int((y1 - y0) * 0.72)])
    manifest["parts"]["body"] = {
        "png": "body.png",
        "pivot": [int(bp[0] - x0), int(bp[1] - y0)],
        "pivot_src": [int(bp[0]), int(bp[1])],
        "size": list(body_crop.size),
        "z": 10,
    }
    print(f"body: {body_crop.size}")

    # face variants drawn in matching cartoon style
    for name in ("eye_l", "eye_r"):
        info = manifest["parts"][name]
        w, h = info["size"]
        draw_closed_eye((w, h), w, h).save(os.path.join(args.out, f"{name}_closed.png"))
    mw, mh = manifest["parts"]["mouth"]["size"]
    draw_mouth_o((mw, mh), mw, mh).save(os.path.join(args.out, "mouth_o.png"))

    # world placement: feet-to-top height maps to char_height_world
    full = alpha > 0.5
    fx0, fy0, fx1, fy1 = bbox_of(full.astype(np.float32), pad=0)
    char_h_px = fy1 - fy0
    manifest["world"] = {
        "char_height_px": int(char_h_px),
        "char_height_world": k,
        "origin_px": [int(fx0), int(fy1)],  # bottom-left of character
    }
    with open(os.path.join(args.out, "rig_manifest.json"), "w") as f:
        json.dump(manifest, f, indent=2)
    print(f"rig manifest -> {args.out}/rig_manifest.json (char_h_px={char_h_px})")


if __name__ == "__main__":
    main()
