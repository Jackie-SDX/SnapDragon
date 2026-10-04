#!/usr/bin/env python3
"""Assemble final MP4: frames + narration + procedural BGM + ASS subtitles.

Everything here is free: FFmpeg filters only, BGM is synthesized (public domain /
CC0 by construction), captions styled with libass using DejaVu fonts.
"""
import argparse
import glob
import json
import os
import subprocess
import sys

BPM_LIKE = 0  # procedural pad only, no beat


def run(cmd, **kw):
    print("+", " ".join(cmd), flush=True)
    r = subprocess.run(cmd, **kw)
    if r.returncode != 0:
        sys.exit(r.returncode)


def probe_duration(path):
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path],
        capture_output=True, text=True, check=True,
    )
    return float(out.stdout.strip())


def wrap(text, max_chars=26):
    words, lines, cur = text.split(), [], ""
    for w in words:
        if len(cur) + len(w) + 1 <= max_chars:
            cur = f"{cur} {w}".strip()
        else:
            if cur:
                lines.append(cur)
            cur = w
    if cur:
        lines.append(cur)
    return lines


def ts(t):
    h = int(t // 3600)
    m = int((t % 3600) // 60)
    s = t % 60
    return f"{h}:{m:02d}:{s:05.2f}"


def build_ass(timeline, project, out_path):
    events = []
    for tb, pb in zip(timeline["beats"], project["beats"]):
        start, dur = tb["start"], tb["dur"]
        lines = wrap(pb["narration"])
        total_chars = sum(len(l) for l in lines) or 1
        t = start
        for line in lines:
            d = dur * len(line) / total_chars
            events.append((t, t + d, line))
            t += d
    style = (
        "[Script Info]\n"
        "ScriptType: v4.00+\n"
        "PlayResX: 1080\n"
        "PlayResY: 1920\n"
        "WrapStyle: 0\n"
        "ScaledBorderAndShadow: yes\n\n"
        "[V4+ Styles]\n"
        "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, "
        "Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, "
        "Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n"
        "Style: Cap,DejaVu Sans,68,&H00FFFFFF,&H00FFFFFF,&H00101018,&H96000000,"
        "-1,0,0,0,100,100,0,0,1,5,2,2,70,70,430,1\n\n"
        "[Events]\n"
        "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n"
    )
    with open(out_path, "w") as f:
        f.write(style)
        for i, (s, e, line) in enumerate(events):
            f.write(f"Dialogue: 0,{ts(s)},{ts(e)},Cap,,0,0,0,,{line}\n")
    return len(events)


def build_narration(timeline, project, out_wav, workdir):
    """Place per-beat wavs at their start times on a silent bed."""
    total = timeline["total"]
    parts = []
    inputs = ["-f", "lavfi", "-t", f"{total}", "-i", "anullsrc=r=44100:cl=stereo"]
    delays = []
    for tb in timeline["beats"]:
        wav = os.path.join(workdir, tb["audio"])
        inputs += ["-i", wav]
        delays.append(tb)
    n = len(timeline["beats"])
    fc = []
    for i, tb in enumerate(timeline["beats"]):
        fc.append(f"[{i+1}:a]aresample=44100,adelay={int(tb['start']*1000)}|{int(tb['start']*1000)}[v{i}]")
    fc.append("".join(f"[v{i}]" for i in range(n)) + f"amix=inputs={n}:normalize=0[mx]")
    fc.append("[0:a][mx]amix=inputs=2:normalize=0:dropout_transition=0,apad,atrim=0:{}[nar]".format(total))
    run(["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", *inputs,
         "-filter_complex", ";".join(fc), "-map", "[nar]", "-ac", "2", "-ar", "44100", out_wav])


def build_sfx(timeline, out_wav):
    """Whoosh transition at each hard cut (beat boundaries, skip first)."""
    total = timeline["total"]
    cuts = [b["end"] for b in timeline["beats"][:-1]]
    if not cuts:
        run(["ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
             "-f", "lavfi", "-t", f"{total}", "-i", "anullsrc=r=44100:cl=stereo",
             "-ac", "2", "-ar", "44100", out_wav])
        return 0
    inputs = ["-f", "lavfi", "-t", f"{total}", "-i", "anullsrc=r=44100:cl=stereo"]
    for _ in cuts:
        inputs += ["-f", "lavfi", "-t", "0.7", "-i",
                   "anoisesrc=color=pink:duration=0.7:amplitude=0.5"]
    fc = []
    n = len(cuts)
    for i, ct in enumerate(cuts):
        d = int(max(ct - 0.10, 0) * 1000)
        fc.append(
            f"[{i+1}:a]highpass=f=320,lowpass=f=5600,"
            f"afade=t=in:d=0.13:curve=qsin,afade=t=out:st=0.30:d=0.40,"
            f"volume=0.5,adelay={d}|{d}[w{i}]"
        )
    fc.append("".join(f"[w{i}]" for i in range(n)) + f"amix=inputs={n}:normalize=0[sx]")
    fc.append(f"[0:a][sx]amix=inputs=2:normalize=0:dropout_transition=0,apad,atrim=0:{total}[out]")
    run(["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", *inputs,
         "-filter_complex", ";".join(fc), "-map", "[out]", "-ac", "2", "-ar", "44100", out_wav])
    return n


def build_bgm(total, out_wav):
    """Synthesize a soft ambient pad with FFmpeg (generated = CC0/public domain)."""
    # three slow sine layers + filtered noise bed, gently evolving
    fc = (
        "sine=frequency=110:duration={t}[a];"
        "sine=frequency=164.81:duration={t}[b];"
        "sine=frequency=220:duration={t}[c];"
        "anoisesrc=color=brown:duration={t}:amplitude=0.35[n];"
        "[a]volume=0.30,tremolo=f=0.13:d=0.55[a1];"
        "[b]volume=0.20,tremolo=f=0.11:d=0.45[b1];"
        "[c]volume=0.13,tremolo=f=0.1:d=0.4[c1];"
        "[n]lowpass=f=420,volume=0.5[n1];"
        "[a1][b1][c1][n1]amix=inputs=4:normalize=0,"
        "lowpass=f=900,aecho=0.7:0.5:220:0.18,"
        "afade=t=in:d=2.5,afade=t=out:st={fo}:d=3,volume=0.42[out]"
    ).format(t=round(total + 1, 2), fo=round(max(total - 3.5, 0), 2))
    run(["ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
         "-filter_complex", fc, "-map", "[out]", "-ac", "2", "-ar", "44100", out_wav])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--project", required=True)
    ap.add_argument("--timeline", required=True)
    ap.add_argument("--work", required=True)
    ap.add_argument("--frames", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    project = json.load(open(args.project))
    timeline = json.load(open(args.timeline))
    total = timeline["total"]
    fps = timeline["fps"]
    W, H = timeline["width"], timeline["height"]
    rw, rh = timeline["render_width"], timeline["render_height"]

    narr = os.path.join(args.work, "narration.wav")
    bgm = os.path.join(args.work, "bgm.wav")
    sfx = os.path.join(args.work, "sfx.wav")
    mix = os.path.join(args.work, "mix.wav")
    ass = os.path.join(args.work, "captions.ass")

    build_narration(timeline, project, narr, args.work)
    build_bgm(total, bgm)
    n_cuts = build_sfx(timeline, sfx)
    n_events = build_ass(timeline, project, ass)
    print(f"captions: {n_events} events, whooshes: {n_cuts}", flush=True)

    # mix narration (loud) + bgm (ducked) + cut whooshes
    run(["ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
         "-i", narr, "-i", bgm, "-i", sfx,
         "-filter_complex",
         "[1:a]volume=0.55[bg];[2:a]volume=0.8[sx];"
         "[0:a][bg][sx]amix=inputs=3:normalize=0:dropout_transition=0,"
         "loudnorm=I=-16:TP=-1.5:LRA=9,atrim=0:{}[m]".format(total),
         "-map", "[m]", "-ac", "2", "-ar", "48000", mix])

    # frames + captions + scale + encode
    seq = sorted(glob.glob(os.path.join(args.frames, "f_*.png")))
    if not seq:
        print(f"no rendered frames in {args.frames}", flush=True)
        sys.exit(2)
    pad = len(os.path.basename(seq[0]).split("_")[1].split(".")[0])
    print(f"frames: {len(seq)} (padding {pad})", flush=True)
    vf = (
        f"scale={W}:{H}:flags=lanczos,"
        f"subtitles={ass}:fontsdir=/usr/share/fonts/truetype/dejavu,"
        "eq=contrast=1.05:saturation=1.10,"
        "colorbalance=rs=0.03:bs=-0.03:rm=0.02:bm=-0.02,"
        "vignette=angle=PI/4.6,"
        "format=yuv420p,"
        "noise=alls=6:allf=t+u"
    )
    run(["ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
         "-framerate", str(fps), "-i", os.path.join(args.frames, f"f_%0{pad}d.png"),
         "-i", mix,
         "-vf", vf,
         "-c:v", "libx264", "-preset", "medium", "-crf", "19",
         "-c:a", "aac", "-b:a", "192k",
         "-movflags", "+faststart", "-shortest",
         args.out])

    size = os.path.getsize(args.out)
    dur = probe_duration(args.out)
    meta = {
        "output": args.out,
        "duration_s": round(dur, 2),
        "size_bytes": size,
        "size_mb": round(size / 1e6, 2),
        "resolution": f"{W}x{H}",
        "fps": fps,
        "captions": n_events,
    }
    with open(os.path.join(args.work, "output-meta.json"), "w") as f:
        json.dump(meta, f, indent=2)
    print(json.dumps(meta, indent=2))


if __name__ == "__main__":
    main()
