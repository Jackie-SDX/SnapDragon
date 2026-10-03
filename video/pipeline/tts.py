#!/usr/bin/env python3
"""Narration synthesis with Piper TTS (free, local). Builds timeline.json."""
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import urllib.request

VOICE_BASE = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/joe/medium"
VOICE_FILES = {
    "en_US-joe-medium.onnx": "58afce0321b8d9c46d7cdf9c16500cc55a793b4220212dba6b70fb788b3baf06",
    "en_US-joe-medium.onnx.json": "3d6d5410b3795cb1950595247ef8f06190719e6fdbfa3a2356d8ec368e1aad33",
}
START_PAD = 0.30
BEAT_GAP = 0.16
END_PAD = 1.40


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def ensure_voice(voice_dir, voice_id):
    os.makedirs(voice_dir, exist_ok=True)
    for fname, expected in VOICE_FILES.items():
        if not fname.startswith(voice_id):
            continue
        path = os.path.join(voice_dir, fname)
        if not (os.path.exists(path) and sha256(path) == expected):
            print(f"downloading {fname}", flush=True)
            urllib.request.urlretrieve(f"{VOICE_BASE}/{fname}", path + ".part")
            os.replace(path + ".part", path)
            actual = sha256(path)
            if actual != expected:
                raise SystemExit(f"sha256 mismatch for {fname}: {actual}")
    model = os.path.join(voice_dir, f"{voice_id}.onnx")
    if not os.path.exists(model):
        raise SystemExit(f"voice model missing: {model}")
    return model


def duration_of(path):
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path],
        capture_output=True, text=True, check=True,
    )
    return float(out.stdout.strip())


def synthesize(model, text, out_wav, length_scale):
    env = dict(os.environ)
    proc = subprocess.run(
        ["piper", "-m", model, "-f", out_wav, "--length-scale", str(length_scale)],
        input=(text + "\n").encode(), capture_output=True, env=env,
    )
    if proc.returncode != 0 or not os.path.exists(out_wav):
        sys.stderr.write(proc.stderr.decode(errors="replace")[-2000:])
        raise SystemExit(f"piper failed for: {text[:60]}")
    return duration_of(out_wav)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--project", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--voice-dir", required=True)
    args = ap.parse_args()

    project = json.load(open(args.project))
    voice_id = project["voice"]["id"]
    length_scale = float(project.get("voice", {}).get("length_scale", 1.0))
    model = ensure_voice(args.voice_dir, voice_id)

    audio_dir = os.path.join(args.out, "audio")
    os.makedirs(audio_dir, exist_ok=True)

    beats = project["beats"]
    t = START_PAD
    timeline_beats = []
    for b in beats:
        wav = os.path.join(audio_dir, f"{b['id']}.wav")
        dur = synthesize(model, b["narration"], wav, length_scale)
        timeline_beats.append({
            "id": b["id"],
            "start": round(t, 3),
            "dur": round(dur, 3),
            "end": round(t + dur, 3),
            "audio": os.path.relpath(wav, args.out),
        })
        t += dur + BEAT_GAP
    total = t - BEAT_GAP + END_PAD

    timeline = {
        "fps": project["fps"],
        "width": project["output_width"],
        "height": project["output_height"],
        "render_width": project["render_width"],
        "render_height": project["render_height"],
        "total": round(total, 3),
        "frames": int(round(total * project["fps"])),
        "beats": timeline_beats,
    }
    with open(os.path.join(args.out, "timeline.json"), "w") as f:
        json.dump(timeline, f, indent=2)
    print(json.dumps(timeline, indent=2))
    print(f"TOTAL {total:.2f}s frames={timeline['frames']}", flush=True)


if __name__ == "__main__":
    main()
