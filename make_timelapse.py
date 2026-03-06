#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import math
import re
import shutil
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from pathlib import Path
from typing import Any, Iterable


SOURCE_FPS = 25
FILENAME_PATTERN = re.compile(r"^([^_]+)_(\d{4})_(\d{2})_(\d{2})_(\d{2})_(\d{2})_(\d{2})\.mp4$")

DEFAULT_RUN_CONFIG_JSON = r"""
{
  "config": "config/config.json",
  "video_source_dir": "/mnt/recorder",
  "cam_id": "cam1-garage",
  "time_range_from": "2026-03-23 08:12",
  "time_range_to": "2026-03-23 09:30",
  "output_file": "soveling15.mp4",
  "duration": 15,
  "fps": 30
}
""".strip()

try:
    DEFAULT_RUN_CONFIG: dict[str, Any] = json.loads(DEFAULT_RUN_CONFIG_JSON)
except Exception as e:
    raise RuntimeError(f"DEFAULT_RUN_CONFIG_JSON is invalid JSON: {e}") from e


@dataclass(frozen=True)
class Segment:
    path: Path
    start_dt: datetime


def _parse_local_datetime(s: str) -> datetime:
    s = s.strip()
    formats = (
        "%Y-%m-%d %H:%M:%S",
        "%Y-%m-%dT%H:%M:%S",
        "%Y-%m-%d %H:%M",
        "%Y-%m-%dT%H:%M",
    )
    for fmt in formats:
        try:
            return datetime.strptime(s, fmt)
        except ValueError:
            continue
    raise ValueError(
        "Invalid datetime format. Use one of: "
        "'YYYY-MM-DD HH:MM[:SS]' or 'YYYY-MM-DDTHH:MM[:SS]' (local time)."
    )


def _daterange_inclusive(start_day: date, end_day: date) -> Iterable[date]:
    day = start_day
    while day <= end_day:
        yield day
        day += timedelta(days=1)


def _segment_start_from_filename(name: str) -> datetime | None:
    m = FILENAME_PATTERN.match(name)
    if not m:
        return None
    # Pattern: cam_id_YYYY_MM_DD_HH_MM_SS.mp4
    year = int(m.group(2))
    month = int(m.group(3))
    day = int(m.group(4))
    hour = int(m.group(5))
    minute = int(m.group(6))
    second = int(m.group(7))
    return datetime(year, month, day, hour, minute, second)


def _require_tool(name: str) -> str:
    path = shutil.which(name)
    if not path:
        raise RuntimeError(f"Required tool not found in PATH: {name}")
    return path


def _run(cmd: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        cmd,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )


def _ffprobe_duration_seconds(ffprobe: str, path: Path) -> float:
    cmd = [
        ffprobe,
        "-v",
        "error",
        "-show_entries",
        "format=duration",
        "-of",
        "default=noprint_wrappers=1:nokey=1",
        str(path),
    ]
    cp = _run(cmd)
    if cp.returncode != 0:
        raise RuntimeError(f"ffprobe failed for {path}:\n{cp.stderr.strip()}")
    try:
        return float(cp.stdout.strip())
    except ValueError as e:
        raise RuntimeError(f"Could not parse ffprobe duration for {path}: {cp.stdout!r}") from e


def _discover_segments(output_dir: Path, cam_id: str, start_dt: datetime, end_dt: datetime) -> list[Segment]:
    segs: list[Segment] = []
    # Include the day before start_dt to catch segments that start just before midnight
    # but overlap into the requested time range.
    scan_start = (start_dt - timedelta(days=1)).date()
    for d in _daterange_inclusive(scan_start, end_dt.date()):
        day_dir = output_dir / f"{d.year}_{d.month:02d}_{d.day:02d}" / cam_id
        if not day_dir.exists():
            continue
        for p in day_dir.glob("*.mp4"):
            if not p.is_file():
                continue
            seg_start = _segment_start_from_filename(p.name)
            if seg_start is None:
                continue
            # Ensure cam_id matches too (filename includes cam_id, but directory does as well)
            m = FILENAME_PATTERN.match(p.name)
            if not m or m.group(1) != cam_id:
                continue
            segs.append(Segment(path=p, start_dt=seg_start))
    segs.sort(key=lambda s: s.start_dt)
    return segs


def _overlaps(a0: datetime, a1: datetime, b0: datetime, b1: datetime) -> bool:
    # half-open intervals: [a0, a1) overlaps [b0, b1)
    return a0 < b1 and b0 < a1


def _select_overlapping_segments(
    segments: list[Segment],
    ffprobe: str,
    range_from: datetime,
    range_to: datetime,
) -> list[tuple[Segment, datetime, datetime]]:
    """
    Returns list of tuples: (segment, effective_start_dt, effective_end_dt)
    where effective_* are clamped to [range_from, range_to].
    """
    if not segments:
        return []

    selected: list[tuple[Segment, datetime, datetime]] = []
    for i, seg in enumerate(segments):
        seg_start = seg.start_dt
        if i + 1 < len(segments):
            seg_end = segments[i + 1].start_dt
        else:
            seg_end = seg_start + timedelta(seconds=_ffprobe_duration_seconds(ffprobe, seg.path))

        if not _overlaps(seg_start, seg_end, range_from, range_to):
            continue

        eff_start = max(seg_start, range_from)
        eff_end = min(seg_end, range_to)
        if eff_end > eff_start:
            selected.append((seg, eff_start, eff_end))

    return selected


def _compute_stride(range_from: datetime, range_to: datetime, duration_s: int, fps: int) -> tuple[int, int, int]:
    target_frames = duration_s * fps
    source_seconds = max(0.0, (range_to - range_from).total_seconds())
    source_frames = int(math.floor(source_seconds * SOURCE_FPS))
    if target_frames <= 0:
        raise ValueError("duration and fps must result in at least 1 target frame")
    stride = max(1, source_frames // target_frames) if source_frames > 0 else 1
    return target_frames, source_frames, stride


def _ffmpeg_extract_frames(
    ffmpeg: str,
    segment_path: Path,
    clip_from_dt: datetime,
    clip_to_dt: datetime,
    segment_start_dt: datetime,
    range_from_dt: datetime,
    stride: int,
    frames_dir: Path,
    start_number: int,
) -> int:
    clip_from_offset = max(0.0, (clip_from_dt - segment_start_dt).total_seconds())
    clip_duration = max(0.0, (clip_to_dt - clip_from_dt).total_seconds())
    if clip_duration <= 0:
        return 0

    # Align sampling across segments based on assumed SOURCE_FPS.
    global_start_frame = int(round(max(0.0, (clip_from_dt - range_from_dt).total_seconds()) * SOURCE_FPS))
    select_expr = f"select=not(mod(n+{global_start_frame}\\,{stride}))"

    out_pattern = frames_dir / "frame_%08d.jpg"

    cmd = [
        ffmpeg,
        "-hide_banner",
        "-loglevel",
        "error",
        "-y",
        "-i",
        str(segment_path),
        "-ss",
        f"{clip_from_offset:.3f}",
        "-t",
        f"{clip_duration:.3f}",
        "-vf",
        select_expr,
        "-vsync",
        "vfr",
        "-q:v",
        "2",
        "-start_number",
        str(start_number),
        "-progress",
        "pipe:1",
        str(out_pattern),
    ]

    cp = _run(cmd)
    if cp.returncode != 0:
        raise RuntimeError(f"ffmpeg frame extraction failed for {segment_path}:\n{cp.stderr.strip()}")

    frames_written = 0
    for line in cp.stdout.splitlines():
        if line.startswith("frame="):
            try:
                frames_written = int(line.split("=", 1)[1].strip())
            except ValueError:
                continue
    return max(0, frames_written)


def _ffmpeg_render_video(
    ffmpeg: str,
    frames_dir: Path,
    fps: int,
    target_frames: int,
    output_file: Path,
) -> None:
    pattern = frames_dir / "frame_%08d.jpg"

    suffix = output_file.suffix.lower()
    if suffix in {".mpg", ".mpeg"}:
        # MPEG Program Stream container typically expects MPEG-1/2 video.
        codec_args = ["-c:v", "mpeg2video", "-q:v", "2", "-pix_fmt", "yuv420p"]
        container_args: list[str] = []
    else:
        codec_args = ["-c:v", "libx264", "-pix_fmt", "yuv420p"]
        container_args = ["-movflags", "+faststart"]

    cmd = [
        ffmpeg,
        "-hide_banner",
        "-loglevel",
        "error",
        "-y",
        "-framerate",
        str(fps),
        "-i",
        str(pattern),
        "-frames:v",
        str(target_frames),
        *codec_args,
        *container_args,
        str(output_file),
    ]
    cp = _run(cmd)
    if cp.returncode != 0:
        raise RuntimeError(f"ffmpeg render failed:\n{cp.stderr.strip()}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Build a timelapse mp4 from stored camera segments.")
    parser.add_argument(
        "--config",
        default=str(DEFAULT_RUN_CONFIG.get("config", "config/config.json")),
        help="Path to config JSON (used only when --video-source-dir is empty).",
    )
    parser.add_argument(
        "--video-source-dir",
        default=str(DEFAULT_RUN_CONFIG.get("video_source_dir", "/mnt/recorder")),
        help="Root directory that contains YYYY_MM_DD/cam_id/*.mp4. If empty, uses output_dir from --config.",
    )
    parser.add_argument(
        "cam_id",
        nargs="?",
        default=str(DEFAULT_RUN_CONFIG.get("cam_id", "")),
        help="Camera id (e.g. cam1-front)",
    )
    parser.add_argument(
        "time_range_from",
        nargs="?",
        default=str(DEFAULT_RUN_CONFIG.get("time_range_from", "")),
        help="Start datetime, local time (e.g. '2026-02-23 10:00:00')",
    )
    parser.add_argument(
        "time_range_to",
        nargs="?",
        default=str(DEFAULT_RUN_CONFIG.get("time_range_to", "")),
        help="End datetime, local time (e.g. '2026-02-23 12:00:00')",
    )
    parser.add_argument(
        "output_file",
        nargs="?",
        default=str(DEFAULT_RUN_CONFIG.get("output_file", "timelapse.mp4")),
        help="Output video path",
    )
    parser.add_argument(
        "duration",
        nargs="?",
        type=int,
        default=int(DEFAULT_RUN_CONFIG.get("duration", 10)),
        help="Timelapse duration in seconds (int)",
    )
    parser.add_argument("--fps", type=int, default=int(DEFAULT_RUN_CONFIG.get("fps", 30)), help="Output FPS")
    args = parser.parse_args(argv)

    ffmpeg = _require_tool("ffmpeg")
    ffprobe = _require_tool("ffprobe")

    if not args.cam_id:
        raise SystemExit("cam_id is required (set it in DEFAULT_RUN_CONFIG_JSON or pass it on the command line)")
    if not args.time_range_from or not args.time_range_to:
        raise SystemExit(
            "time_range_from and time_range_to are required "
            "(set them in DEFAULT_RUN_CONFIG_JSON or pass them on the command line)"
        )

    range_from = _parse_local_datetime(args.time_range_from)
    range_to = _parse_local_datetime(args.time_range_to)
    if range_to <= range_from:
        raise SystemExit("time_range_to must be after time_range_from")
    if args.duration <= 0:
        raise SystemExit("duration must be > 0")
    if args.fps <= 0:
        raise SystemExit("fps must be > 0")

    if args.video_source_dir and str(args.video_source_dir).strip():
        video_source_dir = Path(args.video_source_dir).expanduser().resolve()
    else:
        config_path = Path(args.config)
        if not config_path.exists():
            raise SystemExit(
                f"Config file not found: {config_path}. "
                f"Either provide a valid --config or pass --video-source-dir."
            )

        try:
            data: dict[str, Any] = json.loads(config_path.read_text())
        except Exception as e:
            raise SystemExit(f"Failed to read config JSON at {config_path}: {e}")

        try:
            video_source_dir = Path(data["output_dir"])
        except Exception as e:
            raise SystemExit(f"Config missing/invalid 'output_dir': {e}")

    segments = _discover_segments(video_source_dir, args.cam_id, range_from, range_to)
    if not segments:
        raise SystemExit(f"No segments found for cam_id={args.cam_id!r} in {video_source_dir}")

    selected = _select_overlapping_segments(segments, ffprobe, range_from, range_to)
    if not selected:
        raise SystemExit("No segments overlap the requested time range.")

    target_frames, source_frames, stride = _compute_stride(range_from, range_to, args.duration, args.fps)
    print(
        f"Found {len(segments)} segment(s); selected {len(selected)} overlapping segment(s).\n"
        f"Time range seconds={(range_to - range_from).total_seconds():.3f}, "
        f"source_fps={SOURCE_FPS}, source_frames≈{source_frames}, "
        f"target_frames={target_frames} (duration={args.duration}s @ {args.fps}fps), "
        f"stride={stride} (every {stride}th frame)"
    , flush=True)

    output_file = Path(args.output_file).expanduser().resolve()
    output_file.parent.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(prefix="timelapse_frames_") as tmp:
        frames_dir = Path(tmp)
        start_number = 1
        total_written = 0
        for seg, eff_start, eff_end in selected:
            written = _ffmpeg_extract_frames(
                ffmpeg=ffmpeg,
                segment_path=seg.path,
                clip_from_dt=eff_start,
                clip_to_dt=eff_end,
                segment_start_dt=seg.start_dt,
                range_from_dt=range_from,
                stride=stride,
                frames_dir=frames_dir,
                start_number=start_number,
            )
            if written:
                start_number += written
                total_written += written
            print(f"Extracted {written} frame(s) from {seg.path.name}", flush=True)

        if total_written <= 0:
            raise SystemExit("No frames extracted; check time range and inputs.")

        _ffmpeg_render_video(
            ffmpeg=ffmpeg,
            frames_dir=frames_dir,
            fps=args.fps,
            target_frames=target_frames,
            output_file=output_file,
        )

    print(f"Wrote {output_file}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

