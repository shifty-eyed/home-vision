import io
import logging
import re
import subprocess
import threading
import time
from collections import deque
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from queue import Empty, Full, Queue
from typing import Any

import numpy as np
from concurrent_collections import ConcurrentDictionary

from app.config import CameraConfig, Config
from app.file_manager import FileManager

logger = logging.getLogger(__name__)


@dataclass
class ProcessWrapper:
    cam: CameraConfig
    process: subprocess.Popen | None = None
    reader_thread: threading.Thread | None = None
    stderr_thread: threading.Thread | None = None
    stderr_buffer: deque[str] = field(default_factory=lambda: deque(maxlen=300))
    frame_count: int = 0
    last_frame: np.ndarray | None = field(default=None, repr=False)
    current_file: str | None = None


class StreamProcessor:
    def __init__(self, config: Config):
        self.config = config
        self.cameras: ConcurrentDictionary[str, ProcessWrapper] = ConcurrentDictionary()
        self.frame_queue: Queue[tuple[str, np.ndarray]] = Queue(maxsize=100)
        self.file_queue: Queue[str] = Queue()
        self.stop_event = threading.Event()
        self.processing_thread: threading.Thread | None = None
        self.monitor_thread: threading.Thread | None = None
        self.file_manager = FileManager(config)
        self.file_manager.process_leftover_files()

    def start_all(self) -> None:
        self.processing_thread = threading.Thread(
            target=self._processing_loop, name="frame-processor", daemon=True
        )
        self.processing_thread.start()

        self.monitor_thread = threading.Thread(
            target=self._monitor_loop, name="file-monitor", daemon=True
        )
        self.monitor_thread.start()

        for cam in self.config.cameras:
            try:
                self.start_camera(cam)
            except Exception as e:
                logger.error(f"Failed to start camera {cam.name}: {e}")

    def start_camera(self, cam: CameraConfig) -> None:
        """Start recording and frame extraction for a single camera."""
        # Create scratch directory if it doesn't exist
        self.config.scratch_dir.mkdir(parents=True, exist_ok=True)
        logger.info(f"Using scratch directory: {self.config.scratch_dir}")

        output_pattern = str(self.config.scratch_dir / f"{cam.id}_%Y_%m_%d_%H_%M_%S.mp4")

        # Build FFmpeg command that:
        # 1. Copies H.265 video to segmented files (audio disabled)
        # 2. Optionally outputs every Nth frame as raw RGB to stdout (if enabled)
        ffmpeg_cmd = [
            "ffmpeg",
            "-rtsp_transport", "tcp",
            "-i", cam.url,
            # Output 1: Copy stream to segmented files
            "-c:v", "copy",
            "-an",  # drop audio
            "-f", "segment",
            "-segment_time", str(cam.segment_minutes * 60),
            "-segment_format", "mp4",
            "-strftime", "1",
            "-reset_timestamps", "1",
            output_pattern,
        ]

        # Output 2: Extract frames for detection only if enabled
        enable_detection = cam.detection_interval and cam.detection_interval > 0
        if enable_detection:
            ffmpeg_cmd += [
                "-vf",
                f"select=not(mod(n\\,{cam.detection_interval})),scale=640:480",
                "-vsync",
                "vfr",
                "-f",
                "rawvideo",
                "-pix_fmt",
                "rgb24",
                "pipe:1",
            ]

        logger.info(f"Starting camera {cam.id}: {' '.join(ffmpeg_cmd)}")

        process = subprocess.Popen(
            ffmpeg_cmd,
            stdout=subprocess.PIPE if enable_detection else subprocess.DEVNULL,
            stderr=subprocess.PIPE,
            bufsize=10**8,
        )

        cam_process = ProcessWrapper(cam=cam, process=process)

        self.cameras[cam.id] = cam_process

        if enable_detection:
            cam_process.reader_thread = threading.Thread(
                target=self._frame_detection_reader,
                args=(cam, process),
                name=f"reader-{cam.id}",
                daemon=True,
            )
            cam_process.reader_thread.start()

        cam_process.stderr_thread = threading.Thread(
            target=self._stderr_reader,
            args=(cam.id,),
            name=f"stderr-{cam.id}",
            daemon=True,
        )
        cam_process.stderr_thread.start()

        logger.info(f"Camera {cam.id} started successfully")

    def _frame_detection_reader(self, cam: CameraConfig, process: subprocess.Popen) -> None:
        frame_size = 640 * 480 * 3  # RGB24

        while not self.stop_event.is_set():
            if process.stdout is None:
                break

            raw_frame = process.stdout.read(frame_size)
            if len(raw_frame) != frame_size:
                if len(raw_frame) > 0:
                    logger.warning(f"Camera {cam.id}: incomplete frame ({len(raw_frame)}/{frame_size} bytes)")
                else:
                    logger.info(f"Camera {cam.id}: stream ended")
                break

            frame = np.frombuffer(raw_frame, dtype=np.uint8).reshape((480, 640, 3))

            if cam.id in self.cameras:
                self.cameras[cam.id].frame_count += 1
                self.cameras[cam.id].last_frame = frame

            # Queue frame for processing (non-blocking, drop if full)
            try:
                self.frame_queue.put((cam.id, frame), timeout=0.5)
            except Full:
                logger.debug(f"Camera {cam.id}: frame queue full, dropping frame")

        logger.info(f"Frame reader for {cam.id} exiting")

    def _stderr_reader(self, cam_id: str) -> None:
        cam_process = self.cameras.get(cam_id)
        
        if not cam_process or not cam_process.process or cam_process.process.stderr is None:
            logger.warning(f"Camera {cam_id} process not found")
            return

        segment_pattern = re.compile(r"\[segment @ [^\]]+\] Opening '([^']+)' for writing")

        stderr_lines = io.TextIOWrapper(cam_process.process.stderr, encoding='utf-8', errors='ignore', line_buffering=True)

        for line in stderr_lines:
            if self.stop_event.is_set():
                break
            line = line.strip()
            if line and not "size=" in line and not "time=" in line and not "bitrate=" in line:
                cam_process.stderr_buffer.append(line)
                
                match = segment_pattern.search(line)
                if match:
                    new_file = match.group(1)

                    if cam_process.current_file is not None and cam_process.current_file != new_file:
                        self.file_queue.put(cam_process.current_file, timeout=0.1)

                    cam_process.current_file = new_file
                    logger.info(f"Camera {cam_id}: New segment file: {new_file}")

    def _processing_loop(self) -> None:
        while not self.stop_event.is_set():
            try:
                cam_name, frame = self.frame_queue.get(timeout=1.0)
                # Placeholder for detection processing
                # Future: run OpenCV detection algorithm here
                logger.debug(f"Processing frame from {cam_name}, shape: {frame.shape}")
            except Empty:
                time.sleep(0.1)
                continue

    def _monitor_loop(self) -> None:
        while not self.stop_event.is_set():
            if not self.file_queue.empty():
                self.file_manager.move(self.file_queue)
            self.stop_event.wait(timeout=1.0)


    def stop_camera(self, cam_id: str) -> None:
        cam_process = self.cameras.get(cam_id)
        if not cam_process:
            return

        logger.info(f"Stopping camera {cam_id}")

        del self.cameras[cam_id]

        # Terminate process
        if cam_process.process and cam_process.process.poll() is None:
            cam_process.process.terminate()
            try:
                cam_process.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                logger.warning(f"Camera {cam_id}: force killing process")
                cam_process.process.kill()
                cam_process.process.wait()

        if cam_process.reader_thread and cam_process.reader_thread.is_alive():
            if threading.current_thread() is not cam_process.reader_thread:
                cam_process.reader_thread.join(timeout=2)

        if cam_process.stderr_thread and cam_process.stderr_thread.is_alive():
            if threading.current_thread() is not cam_process.stderr_thread:
                cam_process.stderr_thread.join(timeout=2)

        logger.info(f"Camera {cam_id} stopped")

    def shutdown(self) -> None:
        logger.info("Stopping all cameras...")
        self.stop_event.set()

        for cam_id in list(self.cameras.keys()):
            self.stop_camera(cam_id)

        if self.processing_thread and self.processing_thread.is_alive():
            self.processing_thread.join(timeout=5)

        if self.monitor_thread and self.monitor_thread.is_alive():
            self.monitor_thread.join(timeout=5)

        logger.info("All cameras stopped")


    def get_logs(self, cam_id: str) -> str:
        cam_process = self.cameras.get(cam_id)
        if not cam_process:
            raise KeyError(cam_id)
        return "\n".join(cam_process.stderr_buffer)

