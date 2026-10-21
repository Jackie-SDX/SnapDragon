#!/usr/bin/env python3
"""Prepare character images for puppet rendering.

Keys out the near-white background (cartoon line-art on white), erodes the
alpha edge by 1px to kill JPEG halos, crops to the character bounding box,
and writes RGBA PNGs plus a manifest with aspect ratios.

Free/Open: Pillow + NumPy (both permissive licences: MIT-CMU / BSD-3).
Deterministic: pure pixel math, no randomness.
"""
import argparse
import json
import os

import numpy as np
from PIL import Image

# White-key thresholds: a pixel is background when all channels are bright
# AND chroma is low (so pale character colours like light blue stay opaque).
MN_OPAQUE = 232   # min-channel below this is always fully opaque
MN_CLEAR = 250    # min-channel above this (with low chroma) is fully clear
CHROMA_MAX = 18   # max-min channel spread considered "whiteish"


def key_white(arr):
    """arr: HxWx3 uint8 -> HxW float32 alpha in [0,1]."""
    a = arr.astype(np.float32)
    mn = a.min(axis=2)
    mx = a.max(axis=2)
    chroma = mx - mn
    # ramp 232->250 over min-channel
    whiteness = np.clip((mn - MN_OPAQUE) / float(MN_CLEAR - MN_OPAQUE), 0.0, 1.0)
    lowchroma = np.clip((CHROMA_MAX - chroma) / float(CHROMA_MAX), 0.0, 1.0)
    alpha = 1.0 - whiteness * lowchroma
    return alpha


def erode3(alpha):
    """3x3 minimum filter (via shifts) — removes 1px white fringe."""
    padded = np.pad(alpha, 1, mode="edge")
    out = np.full_like(alpha, 1.0)
    for dy in (0, 1, 2):
        for dx in (0, 1, 2):
            out = np.minimum(out, padded[dy:dy + alpha.shape[0], dx:dx + alpha.shape[1]])
    return out


def process(src, dst, pad_frac=0.03):
    im = Image.open(src).convert("RGB")
    arr = np.asarray(im)
    alpha = erode3(key_white(arr))
    mask = alpha > 0.5
    if not mask.any():
        raise SystemExit(f"fully transparent after keying: {src}")
    ys, xs = np.where(mask)
    y0, y1 = int(ys.min()), int(ys.max()) + 1
    x0, x1 = int(xs.min()), int(xs.max()) + 1
    # small padding around the crop so fingertips/hats never touch the edge
    ph = int((y1 - y0) * pad_frac) + 2
    pw = int((x1 - x0) * pad_frac) + 2
    y0 = max(0, y0 - ph); y1 = min(arr.shape[0], y1 + ph)
    x0 = max(0, x0 - pw); x1 = min(arr.shape[1], x1 + pw)

    rgba = np.dstack([arr, (alpha * 255.0).astype(np.uint8)])
    crop = Image.fromarray(rgba[y0:y1, x0:x1], "RGBA")
    crop.save(dst)
    w, h = crop.size
    return {
        "src": os.path.basename(src),
        "png": os.path.basename(dst),
        "width": w,
        "height": h,
        "aspect": round(w / h, 4),
        "src_bbox": [x0, y0, x1, y1],
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--chars", required=True, help="directory of character JPGs")
    ap.add_argument("--out", required=True, help="output directory for RGBA PNGs")
    ap.add_argument("--manifest", required=True, help="manifest JSON path")
    args = ap.parse_args()

    os.makedirs(args.out, exist_ok=True)
    entries = {}
    for fn in sorted(os.listdir(args.chars)):
        if not fn.lower().endswith((".jpg", ".jpeg", ".png")):
            continue
        stem = os.path.splitext(fn)[0]
        dst = os.path.join(args.out, stem + ".png")
        info = process(os.path.join(args.chars, fn), dst)
        entries[stem] = info
        print(f"{stem}: {info['width']}x{info['height']} aspect={info['aspect']}")

    with open(args.manifest, "w") as f:
        json.dump({"characters": entries}, f, indent=2)
    print(f"manifest -> {args.manifest} ({len(entries)} characters)")


if __name__ == "__main__":
    main()
