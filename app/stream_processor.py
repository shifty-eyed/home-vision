import logging
import subprocess
import threading
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from queue import Empty, Full, Queue
from typing import Any

import numpy as np

from app.config import CameraConfig, Config

logger = logging.getLogger(__name__)


@dataclass
class ProcessWrapper:
    cam_id: str
    process: subprocess.Popen | None = None
    reader_thread: threading.Thread | None = None
    frame_count: int = 0
    last_frame: np.ndarray | None = field(default=None, repr=False)


class StreamProcessor:
    def __init__(self, config: Config):
        self.config = config
        self.cameras: dict[str, ProcessWrapper] = {}
        self.frame_queue: Queue[tuple[str, np.ndarray]] = Queue(maxsize=100)
        self.stop_event = threading.Event()
        self.processing_thread: threading.Thread | None = None
        self._lock = threading.Lock()

    def start_all(self) -> None:
        self.processing_thread = threading.Thread(
            target=self._processing_loop, name="frame-processor", daemon=True
        )
        self.processing_thread.start()

        for cam_config in self.config.cameras:
            try:
                self.start_camera(cam_config)
            except Exception as e:
                logger.error(f"Failed to start camera {cam_config.name}: {e}")

    def start_camera(self, cam: CameraConfig) -> None:
        """Start recording and frame extraction for a single camera."""
        # Create output directory structure: {output_dir}/{year}/{month}/{day}/{camera}/
        now = datetime.now()
        output_base = self.config.output_dir / f"{now.year}_{now.month:02d}_{now.day:02d}" / cam.id
        output_base.mkdir(parents=True, exist_ok=True)
        logger.info(f"Created output directory for {cam.id}: {output_base}")

        # Output file pattern for segment rotation
        output_pattern = str(output_base / f"{cam.id}_%H_%M_%S.mp4")

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

        cam_process = ProcessWrapper(cam_id=cam.id, process=process)

        if enable_detection:
            cam_process.reader_thread = threading.Thread(
                target=self._frame_reader,
                args=(cam.id, process),
                name=f"reader-{cam.id}",
                daemon=True,
            )
            cam_process.reader_thread.start()

        with self._lock:
            self.cameras[cam.id] = cam_process

        logger.info(f"Camera {cam.id} started successfully")

    def _frame_reader(self, cam: CameraConfig, process: subprocess.Popen) -> None:
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

            with self._lock:
                if cam.id in self.cameras:
                    self.cameras[cam.id].frame_count += 1
                    self.cameras[cam.id].last_frame = frame

            # Queue frame for processing (non-blocking, drop if full)
            try:
                self.frame_queue.put((cam.id, frame), timeout=0.5)
            except Full:
                logger.debug(f"Camera {cam.id}: frame queue full, dropping frame")

        logger.info(f"Frame reader for {cam.id} exiting")

    def _processing_loop(self) -> None:
        logger.info("Processing thread started")

        while not self.stop_event.is_set():
            try:
                cam_name, frame = self.frame_queue.get(timeout=1.0)
                # Placeholder for detection processing
                # Future: run OpenCV detection algorithm here
                logger.debug(f"Processing frame from {cam_name}, shape: {frame.shape}")
            except Empty:
                continue
            except Exception as e:
                logger.error(f"Error processing frame: {e}")

        logger.info("Processing thread exiting")

    def stop_camera(self, cam_id: str) -> None:
        with self._lock:
            cam_process = self.cameras.get(cam_id)
            if not cam_process:
                logger.warning(f"Camera {cam_id} not found")
                return

        logger.info(f"Stopping camera {cam_id}")

        # Terminate process
        if cam_process.process and cam_process.process.poll() is None:
            cam_process.process.terminate()
            try:
                cam_process.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                logger.warning(f"Camera {cam_id}: force killing process")
                cam_process.process.kill()
                cam_process.process.wait()

        # Wait for reader thread
        if cam_process.reader_thread and cam_process.reader_thread.is_alive():
            cam_process.reader_thread.join(timeout=2)

        with self._lock:
            del self.cameras[cam_id]

        logger.info(f"Camera {cam_id} stopped")

    def stop_all(self) -> None:
        """Stop all cameras and the processing thread."""
        logger.info("Stopping all cameras...")
        self.stop_event.set()

        for cam_id in list(self.cameras.keys()):
            self.stop_camera(cam_id)

        # Wait for processing thread
        if self.processing_thread and self.processing_thread.is_alive():
            self.processing_thread.join(timeout=5)

        logger.info("All cameras stopped")

    def get_status(self) -> dict[str, Any]:
        """Get current status of all cameras."""
        with self._lock:
            camera_status = {}
            for cam_id, cam_process in self.cameras.items():
                process_alive = cam_process.process is not None and cam_process.process.poll() is None
                camera_status[cam_id] = {
                    "name": cam_process.cam_id,
                    "frames_processed": cam_process.frame_count,
                    "process_alive": process_alive,
                    "reader_alive": cam_process.reader_thread is not None and cam_process.reader_thread.is_alive(),
                }

            return {
                "active_cameras": len(self.cameras),
                "queue_size": self.frame_queue.qsize(),
                "cameras": camera_status,
            }

