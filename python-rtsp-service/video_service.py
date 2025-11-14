"""
Main RTSP Video Service - manages multiple camera processors
"""
import os
import time
import shutil
import logging
import signal
import sys
from pathlib import Path
from typing import List
from datetime import datetime

from config import Config
from camera_processor import CameraProcessor


class VideoService:
    """Main service that manages multiple RTSP camera processors"""

    def __init__(self, config_file: str = "config.json"):
        self.config = Config(config_file)
        self.processors: List[CameraProcessor] = []
        self.running = False
        self.logger = logging.getLogger("VideoService")

        # Setup signal handlers for graceful shutdown
        signal.signal(signal.SIGINT, self.signal_handler)
        signal.signal(signal.SIGTERM, self.signal_handler)

    def signal_handler(self, signum, frame):
        """Handle shutdown signals"""
        self.logger.info(f"Received signal {signum}, shutting down...")
        self.shutdown()
        sys.exit(0)

    def start(self) -> None:
        """Start the video service"""
        try:
            # Load configuration
            self.config.load()
            self.logger.info(f"Loaded configuration for {len(self.config.cameras)} camera(s)")

            # Create and start camera processors
            for camera_config in self.config.cameras:
                processor = CameraProcessor(camera_config)
                self.processors.append(processor)
                processor.start()
                self.logger.info(f"Started processor for camera: {camera_config.name}")

            self.running = True
            self.logger.info("Video service started successfully")

            # Main loop - monitor disk space and keep service running
            self.run_maintenance_loop()

        except Exception as e:
            self.logger.error(f"Failed to start video service: {e}", exc_info=True)
            self.shutdown()
            raise

    def run_maintenance_loop(self) -> None:
        """Main maintenance loop"""
        disk_check_interval = 60  # Check disk space every 60 seconds
        last_disk_check = 0

        while self.running:
            try:
                current_time = time.time()

                # Periodic disk space management
                if current_time - last_disk_check >= disk_check_interval:
                    self.ensure_disk_space()
                    last_disk_check = current_time

                # Sleep to prevent busy loop
                time.sleep(1)

            except Exception as e:
                self.logger.error(f"Error in maintenance loop: {e}", exc_info=True)
                time.sleep(5)

    def ensure_disk_space(self) -> None:
        """Ensure sufficient disk space by deleting old files if necessary"""
        # Get unique output directories from all cameras
        output_dirs = set()
        for camera_config in self.config.cameras:
            output_dirs.add(camera_config.recording.video_out_path)

        for output_dir in output_dirs:
            if not os.path.exists(output_dir):
                continue

            try:
                bytes_to_free = self.get_bytes_to_free(output_dir)

                if bytes_to_free > 0:
                    self.logger.info(f"Need to free {self.format_bytes(bytes_to_free)} in {output_dir}")
                    self.delete_old_files(output_dir, bytes_to_free)

            except Exception as e:
                self.logger.error(f"Error managing disk space for {output_dir}: {e}", exc_info=True)

    def get_bytes_to_free(self, directory: str) -> int:
        """Calculate how many bytes need to be freed"""
        # Get the first camera config that uses this directory (for settings)
        camera_config = None
        for cam in self.config.cameras:
            if cam.recording.video_out_path == directory:
                camera_config = cam
                break

        if not camera_config:
            return 0

        recording_config = camera_config.recording

        # Calculate directory size
        dir_size = self.get_directory_size(directory)

        # Check occupied space limit
        oversize_occupied = 0
        if recording_config.limit_occupied_space:
            max_size = recording_config.max_occupied_space_gb * 1024 * 1024 * 1024
            oversize_occupied = max(dir_size - max_size, 0)

        # Check free disk space
        stat = shutil.disk_usage(directory)
        free_space = stat.free
        target_free_space = recording_config.keep_free_disk_space_gb * 1024 * 1024 * 1024
        oversize_free = max(target_free_space - free_space, 0)

        return max(oversize_occupied, oversize_free)

    def get_directory_size(self, directory: str) -> int:
        """Get total size of a directory in bytes"""
        total_size = 0
        for dirpath, dirnames, filenames in os.walk(directory):
            for filename in filenames:
                filepath = os.path.join(dirpath, filename)
                if os.path.isfile(filepath):
                    total_size += os.path.getsize(filepath)
        return total_size

    def delete_old_files(self, directory: str, bytes_to_free: int) -> None:
        """Delete oldest files until enough space is freed"""
        # Get all files with their modification times
        files = []
        for dirpath, dirnames, filenames in os.walk(directory):
            for filename in filenames:
                filepath = os.path.join(dirpath, filename)
                if os.path.isfile(filepath):
                    try:
                        mtime = os.path.getmtime(filepath)
                        size = os.path.getsize(filepath)
                        files.append((filepath, mtime, size))
                    except OSError:
                        pass

        # Sort by modification time (oldest first)
        files.sort(key=lambda x: x[1])

        # Delete files until we've freed enough space
        freed = 0
        for filepath, mtime, size in files:
            if freed >= bytes_to_free:
                break

            try:
                self.logger.info(f"Deleting old file: {filepath} ({self.format_bytes(size)})")
                os.remove(filepath)
                freed += size
            except OSError as e:
                self.logger.error(f"Failed to delete {filepath}: {e}")

        # Clean up empty directories
        self.cleanup_empty_directories(directory)

        self.logger.info(f"Freed {self.format_bytes(freed)} of disk space")

    def cleanup_empty_directories(self, directory: str) -> None:
        """Remove empty subdirectories"""
        for dirpath, dirnames, filenames in os.walk(directory, topdown=False):
            if dirpath == directory:
                continue

            try:
                if not os.listdir(dirpath):
                    self.logger.info(f"Removing empty directory: {dirpath}")
                    os.rmdir(dirpath)
            except OSError:
                pass

    @staticmethod
    def format_bytes(bytes_value: int) -> str:
        """Format bytes to human-readable string"""
        for unit in ['B', 'KB', 'MB', 'GB', 'TB']:
            if bytes_value < 1024.0:
                return f"{bytes_value:.2f} {unit}"
            bytes_value /= 1024.0
        return f"{bytes_value:.2f} PB"

    def shutdown(self) -> None:
        """Shutdown all camera processors"""
        self.logger.info("Shutting down video service...")
        self.running = False

        for processor in self.processors:
            processor.stop()

        # Wait for all processors to finish
        for processor in self.processors:
            processor.join(timeout=5)

        self.logger.info("Video service stopped")


def setup_logging():
    """Setup logging configuration"""
    logging.basicConfig(
        level=logging.INFO,
        format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
        handlers=[
            logging.StreamHandler(sys.stdout),
            logging.FileHandler('video_service.log')
        ]
    )


def main():
    """Main entry point"""
    setup_logging()
    logger = logging.getLogger("main")

    logger.info("Starting RTSP Video Service")

    try:
        service = VideoService("config.json")
        service.start()
    except KeyboardInterrupt:
        logger.info("Received keyboard interrupt")
    except Exception as e:
        logger.error(f"Service error: {e}", exc_info=True)
        sys.exit(1)


if __name__ == "__main__":
    main()
