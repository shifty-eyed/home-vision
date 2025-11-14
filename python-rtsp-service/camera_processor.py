"""
Camera processor for RTSP streams using ffmpeg
"""
import subprocess
import threading
import logging
import os
import time
from datetime import datetime
from pathlib import Path
from typing import Optional
from config import CameraConfig


class CameraProcessor(threading.Thread):
    """Processes RTSP stream from a single camera"""

    def __init__(self, config: CameraConfig):
        super().__init__(daemon=True)
        self.config = config
        self.logger = logging.getLogger(f"CameraProcessor-{config.name}")
        self.running = False
        self.recording = False
        self.ffmpeg_process: Optional[subprocess.Popen] = None
        self.current_file_start_time: Optional[float] = None
        self.lock = threading.Lock()

    def run(self) -> None:
        """Main processing loop"""
        self.running = True
        self.logger.info(f"Starting camera processor for {self.config.name}")

        while self.running:
            try:
                # Check if we should be recording
                should_record = self.can_record_now()

                if should_record and not self.recording:
                    self.start_recording()
                elif not should_record and self.recording:
                    self.stop_recording()

                # Check if we need to rotate the file
                if self.recording and self.should_rotate_file():
                    self.rotate_file()

                # Small sleep to prevent busy loop
                time.sleep(1)

            except Exception as e:
                self.logger.error(f"Error in processing loop: {e}", exc_info=True)
                time.sleep(5)  # Wait before retry

        self.shutdown()

    def can_record_now(self) -> bool:
        """Determine if recording should be active based on mode"""
        mode = self.config.recording.mode

        if mode == "always":
            return True
        elif mode == "never":
            return False
        elif mode.startswith("period:"):
            # Format: period:HH-HH (e.g., period:08-18)
            try:
                period = mode.split(":", 1)[1]
                start_hour, end_hour = map(int, period.split("-"))
                current_hour = datetime.now().hour
                return start_hour <= current_hour <= end_hour
            except (ValueError, IndexError) as e:
                self.logger.error(f"Invalid period format: {mode}, error: {e}")
                return False
        elif mode == "auto":
            # TODO: Implement motion detection
            return True
        else:
            self.logger.warning(f"Unknown recording mode: {mode}")
            return False

    def should_rotate_file(self) -> bool:
        """Check if it's time to rotate the video file"""
        if self.current_file_start_time is None:
            return False

        elapsed = time.time() - self.current_file_start_time
        return elapsed >= self.config.recording.file_interval_seconds

    def rotate_file(self) -> None:
        """Rotate to a new video file"""
        self.logger.info("Rotating video file")
        self.stop_recording()
        time.sleep(0.5)  # Small delay to ensure clean stop
        self.start_recording()

    def start_recording(self) -> None:
        """Start recording from RTSP stream"""
        with self.lock:
            if self.recording:
                return

            # Generate output file path
            output_file = self.get_output_filename()
            output_dir = os.path.dirname(output_file)

            # Create output directory
            Path(output_dir).mkdir(parents=True, exist_ok=True)

            self.logger.info(f"Starting recording to: {output_file}")

            # Build ffmpeg command
            cmd = self.build_ffmpeg_command(output_file)

            try:
                # Start ffmpeg process
                self.ffmpeg_process = subprocess.Popen(
                    cmd,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    stdin=subprocess.PIPE
                )

                self.recording = True
                self.current_file_start_time = time.time()
                self.logger.info(f"Recording started for {self.config.name}")

            except Exception as e:
                self.logger.error(f"Failed to start recording: {e}", exc_info=True)
                self.recording = False

    def stop_recording(self) -> None:
        """Stop the current recording"""
        with self.lock:
            if not self.recording or self.ffmpeg_process is None:
                return

            self.logger.info(f"Stopping recording for {self.config.name}")

            try:
                # Send 'q' to ffmpeg to gracefully stop
                self.ffmpeg_process.stdin.write(b'q')
                self.ffmpeg_process.stdin.flush()

                # Wait for process to finish (with timeout)
                try:
                    self.ffmpeg_process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    self.logger.warning("FFmpeg did not stop gracefully, terminating")
                    self.ffmpeg_process.terminate()
                    try:
                        self.ffmpeg_process.wait(timeout=2)
                    except subprocess.TimeoutExpired:
                        self.ffmpeg_process.kill()

                self.logger.info(f"Recording stopped for {self.config.name}")

            except Exception as e:
                self.logger.error(f"Error stopping recording: {e}", exc_info=True)
                if self.ffmpeg_process:
                    self.ffmpeg_process.kill()

            finally:
                self.ffmpeg_process = None
                self.recording = False
                self.current_file_start_time = None

    def build_ffmpeg_command(self, output_file: str) -> list:
        """Build ffmpeg command for RTSP capture"""
        codec = self.config.recording.video_codec.lower()

        # Map codec names
        codec_map = {
            'hevc': 'libx265',
            'h265': 'libx265',
            'h264': 'libx264',
            'mpeg4': 'mpeg4'
        }

        output_codec = codec_map.get(codec, 'copy')

        cmd = [
            'ffmpeg',
            '-rtsp_transport', 'tcp',  # Use TCP for more reliable streaming
            '-i', self.config.rtsp_url,  # Input RTSP URL
            '-c:v', output_codec,  # Video codec
            '-b:v', f'{self.config.recording.video_bitrate_kbps}k',  # Video bitrate
            '-r', str(self.config.fps),  # Frame rate
            '-s', f'{self.config.frame_width}x{self.config.frame_height}',  # Resolution
        ]

        # Add codec-specific options
        if output_codec == 'libx265':
            cmd.extend([
                '-preset', 'fast',  # Encoding preset
                '-crf', '23',  # Constant Rate Factor (quality)
            ])
        elif output_codec == 'libx264':
            cmd.extend([
                '-preset', 'fast',
                '-crf', '23',
            ])

        # Audio handling - copy if available, otherwise ignore
        cmd.extend([
            '-c:a', 'aac',  # Audio codec
            '-b:a', '128k',  # Audio bitrate
        ])

        # Output format and file
        cmd.extend([
            '-f', self.config.recording.video_file_extension,
            '-y',  # Overwrite output file if exists
            output_file
        ])

        return cmd

    def get_output_filename(self) -> str:
        """Generate output filename with timestamp"""
        now = datetime.now()
        date_str = now.strftime("%Y-%m-%d")
        time_str = now.strftime("%H_%M_%S")

        output_dir = os.path.join(
            self.config.recording.video_out_path,
            self.config.name,
            date_str
        )

        filename = f"{time_str}.{self.config.recording.video_file_extension}"
        return os.path.join(output_dir, filename)

    def shutdown(self) -> None:
        """Clean shutdown of the processor"""
        self.logger.info(f"Shutting down camera processor for {self.config.name}")
        self.stop_recording()

    def stop(self) -> None:
        """Stop the processor thread"""
        self.running = False
