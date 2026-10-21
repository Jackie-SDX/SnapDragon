# Articulated 2D rigging (Salbutamol)

Turns a single full-body cartoon still into a pivot-joint puppet: independent
arms/hands/legs/feet, a swaying torso, separate brows/eyes/mouth for blinks,
expressions and lip-sync. Pure geometry + free tooling (Pillow for the part
split, Blender EEVEE headless for the FK rig) — **no ML, no paid APIs, no
large models**. Everything is deterministic: same inputs → byte-identical
frames.

## Pipeline

```
source .jpg ──split_char.py──> per-part RGBA + rig_manifest.json
                                   │
salbutamol-demo.json ──pipeline.py --renderer articulated──> articulated.py
                                   │                         (Blender FK rig)
                                   └──assemble.py──> final.mp4
```

`pipeline.py --renderer articulated` runs Stage 0 = `split_char.py`, then the
`articulated.py` Blender render, then the normal `assemble.py` stage.

## Files

| File | Role |
|------|------|
| `video/rigs/salbutamol_rig.json` | **Manual prep output.** Part polygons + joint pivots (source-image coords) for one character. |
| `video/pipeline/split_char.py` | White-keys the source, cuts each part along its polygon, inpaints the body, emits `rig_manifest.json` + part PNGs + face variants. |
| `video/pipeline/articulated.py` | Builds the Blender FK rig (empties-as-joints, per-part planes whose origin sits on the pivot), bakes per-frame keyframes from parametric gesture primitives. |
| `video/projects/salbutamol-demo.json` | Project: beat narration + the gesture score (timeline of joint rotations, blinks, lip-sync windows). |

## The manual prep step (the only hand work)

Everything is driven by `video/rigs/<char>_rig.json`. You author this once per
character in an image editor (or by eye against the source), measuring in
**source-image pixel coordinates**:

```jsonc
{
  "src": "assets/characters/…​.jpg",
  "char_height_world": 3.6,
  "parts": [
    { "name": "hand_l", "poly": [[x,y], …], "pivot": [x,y], "z": 20 }
    // … arm_l, hand_r, arm_r, shoe_l, leg_l, shoe_r, leg_r,
    //   brow_l, brow_r, eye_l, eye_r, mouth, canister …
  ],
  "body_remove": ["hand_l","arm_l", …, "eye_l","eye_r","mouth","canister"],
  "inpaint_jobs": [ /* flat-fill rectangles over removed limbs */ ],
  "body_pivot": [x, y]
}
```

- **`poly`** — a closed outline of the part in source pixels. Keep it snug;
  over-covering eats into neighbouring parts, under-covering leaves slivers
  on the body (the splitter reports leftover fragments ≥60px so you can tune).
- **`pivot`** — the joint centre in source pixels. Limb rotates about this.
  e.g. `hand_l` pivots at the wrist, `arm_l` at the shoulder, `leg_l` at the
  hip, `shoe_l` at the ankle. Face parts pivot at their centre.
- **`z`** — draw order (higher = in front): shadow 0.02 → legs → body →
  canister → arms → face.
- **`body_remove`** — parts cut *out* of the body silhouette. Limbs get a
  dilated hole so rotation never reveals a seam; face features get an exact
  hole so they sit flush. The vacated area is inpainted flat.

### Tips from the Salbutamol pass

- Eyeball the overlay preview: `split_char.py --preview --out overlay.png`
  draws every polygon + pivot on the source so you can tune before cutting.
- Watch for leftover **fragments** (the splitter prints connected components
  ≥80px still on the body). Widen the offending polygon to swallow them.
- Closed-eye / open-mouth **variants** (`eye_*_closed.png`, `mouth_o.png`) are
  auto-drawn in a matching flat cartoon style by `split_char.py` — no manual
  art needed.

## Animation model (`articulated.py`)

Each part plane's Blender **object origin is placed exactly on its joint
pivot**, then parented: `body → arm_l → hand_l`, `body → leg_l → shoe_l`,
`body → face features`. Rotating a parent swings the whole child chain (FK).

Motion is a list of **gesture primitives** in the project JSON, evaluated per
frame and baked as keyframes:

- `rot` / `rot_seq` — ease a joint to N degrees (or through keyframed poses:
  the double-take head tilt).
- `osc` — sinusoidal wiggle (arm wag, leg shuffle, torso counter-sway).
- `squash_event` / `squash_hold` — cartoon anticipation squash-&-stretch on
  the body root (anticipation → overshoot → settle).
- `raise` — brows slide up (reaction).
- blinks — swap open→closed eye sprites for ~0.1s.
- lip-sync — mouth `scale_y` oscillates on a deterministic syllable-rate
  during the narration `talk_segments`; an `O` mouth swaps in for the surprise
  beat.

Because everything is a pure function of frame time (no randomness, no
accumulated state), re-rendering reproduces the identical video.

## Rendering the demo

```bash
# local
python3 video/pipeline/pipeline.py \
  --project video/projects/salbutamol-demo.json \
  --work work --renderer articulated --samples 4

# CI (deterministic, no merge) — dispatch from the session branch
gh workflow run video-puppet --ref oc/session-161 \
  -f project=video/projects/salbutamol-demo.json -f renderer=articulated
```

Output: `work/final.mp4`, 1080×1920, 24fps, ~6.4s, ~4.5MB, one beat
("Point, wiggle, shuffle, and breathe. Easy. Salbutamol to the rescue.").

## Limitations

- Pivots/polygons are **hand-authored per character** (the one manual step).
  A new character needs a new `*_rig.json`.
- Inpainting is a **flat fill**, so a rotating limb can briefly reveal a solid
  patch where the source had detail behind it. Fine for fast gestures; obvious
  on slow large rotations.
- Face variants (closed eyes, O-mouth) are stylised flat fills, not traced
  from the source — they match in colour but are simpler than the original art.
- Body squash scales the whole child chain uniformly; there is no secondary
  physics (cloth, hair, inertia) — motion is fully authored/deterministic.
