"""
Configuration reader for RTSP Video Service
"""
import json
import logging
from typing import List, Dict, Any, Optional
from dataclasses import dataclass, field

logger = logging.getLogger(__name__)


@dataclass
class RecordingConfig:
    """Recording configuration settings"""
    mode: str = "always"  # never, always, period:HH-HH, auto
    video_codec: str = "hevc"  # hevc (H.265), h264, mpeg4
    video_bitrate_kbps: int = 8000
    video_out_path: str = "./video-out"
    video_file_extension: str = "mp4"
    file_interval_seconds: int = 300  # 5 minutes
    limit_occupied_space: bool = True
    max_occupied_space_gb: int = 50
    keep_free_disk_space_gb: int = 10


@dataclass
class CameraConfig:
    """Camera configuration settings"""
    name: str
    rtsp_url: str
    frame_width: int = 2592
    frame_height: int = 1944
    fps: int = 15
    recording: Optional[RecordingConfig] = None

    def __post_init__(self):
        if self.recording is None:
            self.recording = RecordingConfig()


@dataclass
class GlobalConfig:
    """Global configuration settings"""
    frame_width: int = 2592
    frame_height: int = 1944
    fps: int = 15
    recording: RecordingConfig = field(default_factory=RecordingConfig)


class Config:
    """Configuration manager for RTSP Video Service"""

    def __init__(self, config_file: str = "config.json"):
        self.config_file = config_file
        self.global_config: GlobalConfig = GlobalConfig()
        self.cameras: List[CameraConfig] = []

    def load(self) -> None:
        """Load configuration from JSON file"""
        try:
            with open(self.config_file, 'r') as f:
                data = json.load(f)

            # Load global config
            global_data = data.get('global', {})
            self._load_global_config(global_data)

            # Load camera configs
            cameras_data = data.get('cameras', [])
            self._load_camera_configs(cameras_data)

            logger.info(f"Loaded configuration for {len(self.cameras)} camera(s)")

        except FileNotFoundError:
            logger.error(f"Configuration file not found: {self.config_file}")
            raise
        except json.JSONDecodeError as e:
            logger.error(f"Invalid JSON in configuration file: {e}")
            raise

    def _load_global_config(self, data: Dict[str, Any]) -> None:
        """Load global configuration"""
        self.global_config = GlobalConfig(
            frame_width=data.get('frameWidth', 2592),
            frame_height=data.get('frameHeight', 1944),
            fps=data.get('fps', 15),
            recording=self._load_recording_config(data.get('recording', {}))
        )

    def _load_recording_config(self, data: Dict[str, Any]) -> RecordingConfig:
        """Load recording configuration"""
        return RecordingConfig(
            mode=data.get('mode', 'always'),
            video_codec=data.get('videoCodec', 'hevc'),
            video_bitrate_kbps=data.get('videoBitrateKbps', 8000),
            video_out_path=data.get('videoOutPath', './video-out'),
            video_file_extension=data.get('videoFileExtension', 'mp4'),
            file_interval_seconds=data.get('fileIntervalSeconds', 300),
            limit_occupied_space=data.get('limitOccupiedSpace', True),
            max_occupied_space_gb=data.get('maxOccupiedSpaceGB', 50),
            keep_free_disk_space_gb=data.get('keepFreeDiskSpaceGB', 10)
        )

    def _load_camera_configs(self, cameras_data: List[Dict[str, Any]]) -> None:
        """Load camera configurations"""
        self.cameras = []
        for cam_data in cameras_data:
            # Start with global defaults
            camera_config = CameraConfig(
                name=cam_data['name'],
                rtsp_url=cam_data['rtspUrl'],
                frame_width=cam_data.get('frameWidth', self.global_config.frame_width),
                frame_height=cam_data.get('frameHeight', self.global_config.frame_height),
                fps=cam_data.get('fps', self.global_config.fps),
            )

            # Merge recording config (camera-specific overrides global)
            if 'recording' in cam_data:
                camera_recording = self._merge_recording_config(
                    self.global_config.recording,
                    cam_data['recording']
                )
                camera_config.recording = camera_recording
            else:
                camera_config.recording = self.global_config.recording

            self.cameras.append(camera_config)

    def _merge_recording_config(self, global_rec: RecordingConfig,
                                camera_rec: Dict[str, Any]) -> RecordingConfig:
        """Merge global and camera-specific recording configs"""
        return RecordingConfig(
            mode=camera_rec.get('mode', global_rec.mode),
            video_codec=camera_rec.get('videoCodec', global_rec.video_codec),
            video_bitrate_kbps=camera_rec.get('videoBitrateKbps', global_rec.video_bitrate_kbps),
            video_out_path=camera_rec.get('videoOutPath', global_rec.video_out_path),
            video_file_extension=camera_rec.get('videoFileExtension', global_rec.video_file_extension),
            file_interval_seconds=camera_rec.get('fileIntervalSeconds', global_rec.file_interval_seconds),
            limit_occupied_space=camera_rec.get('limitOccupiedSpace', global_rec.limit_occupied_space),
            max_occupied_space_gb=camera_rec.get('maxOccupiedSpaceGB', global_rec.max_occupied_space_gb),
            keep_free_disk_space_gb=camera_rec.get('keepFreeDiskSpaceGB', global_rec.keep_free_disk_space_gb)
        )

    def get_cameras(self) -> List[CameraConfig]:
        """Get all camera configurations"""
        return self.cameras

    def get_global(self) -> GlobalConfig:
        """Get global configuration"""
        return self.global_config
