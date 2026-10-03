#!/usr/bin/env python3
"""Free-first end-to-end short video pipeline (no paid APIs).

Stages: tts (Piper) -> render (Blender EEVEE headless) -> assemble (FFmpeg).
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))


def log(msg):
    print(f"\n=== {msg} ===", flush=True)


def run(cmd, **kw):
    print("+", " ".join(str(c) for c in cmd), flush=True)
    t0 = time.time()
    r = subprocess.run([str(c) for c in cmd], **kw)
    dt = time.time() - t0
    if r.returncode != 0:
        print(f"FAILED ({dt:.1f}s)", flush=True)
        sys.exit(r.returncode)
    print(f"done in {dt:.1f}s", flush=True)
    return dt


def find_blender():
    if os.environ.get("BLENDER_BIN"):
        return os.environ["BLENDER_BIN"]
    return shutil.which("blender") or "/usr/bin/blender"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--project", required=True)
    ap.add_argument("--work", required=True)
    ap.add_argument("--samples", type=int, default=8)
    ap.add_argument("--skip-tts", action="store_true")
    ap.add_argument("--skip-render", action="store_true")
    ap.add_argument("--skip-assemble", action="store_true")
    args = ap.parse_args()

    project_path = os.path.abspath(args.project)
    work = os.path.abspath(args.work)
    frames = os.path.join(work, "frames")
    os.makedirs(work, exist_ok=True)
    os.makedirs(frames, exist_ok=True)

    timings = {}
    timeline_path = os.path.join(work, "timeline.json")

    if not args.skip_tts:
        log("Stage 1/3: narration (Piper TTS, free)")
        timings["tts"] = run([
            sys.executable, os.path.join(HERE, "tts.py"),
            "--project", project_path, "--out", work,
            "--voice-dir", os.path.join(work, "voices"),
        ])
        sys.stdout.flush()

    timeline = json.load(open(timeline_path))
    print(f"duration={timeline['total']}s frames={timeline['frames']}", flush=True)

    if not args.skip_render:
        log("Stage 2/3: 3D render (Blender EEVEE, headless)")
        blender = find_blender()
        timings["render"] = run([
            "xvfb-run", "-a", blender, "-b",
            "-P", os.path.join(HERE, "scene.py"), "--",
            "--project", project_path,
            "--timeline", timeline_path,
            "--out", frames,
            "--samples", args.samples,
        ])

    if not args.skip_assemble:
        log("Stage 3/3: assemble (FFmpeg)")
        out_mp4 = os.path.join(work, "final.mp4")
        timings["assemble"] = run([
            sys.executable, os.path.join(HERE, "assemble.py"),
            "--project", project_path,
            "--timeline", timeline_path,
            "--work", work,
            "--frames", frames,
            "--out", out_mp4,
        ])

    timings["total_wall"] = round(sum(timings.values()), 1)
    with open(os.path.join(work, "timings.json"), "w") as f:
        json.dump(timings, f, indent=2)
    log("PIPELINE COMPLETE")
    print(json.dumps(timings, indent=2), flush=True)


if __name__ == "__main__":
    main()
