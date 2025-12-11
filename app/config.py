import json
import logging
from pathlib import Path
from typing import Any

from pydantic import BaseModel, ConfigDict, Field, ValidationError

logger = logging.getLogger(__name__)


class CameraConfig(BaseModel):
    model_config = ConfigDict(extra="forbid")

    id: str
    url: str
    segment_minutes: float = Field(..., gt=0)
    detection_interval: float = Field(0, ge=0)
    enabled: bool = True


class AppConfig(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    output_dir: Path
    scratch_dir: Path
    cameras: list[CameraConfig] = Field(default_factory=list)
    max_occupied_space_mb: int = Field(100, alias="max_occupied_space_mb")


class Config:
    def __init__(self, config_path: str | Path):
        self.config_path = Path(config_path)
        self._config: AppConfig
        self._load()

    def _load(self) -> None:
        if not self.config_path.exists():
            raise FileNotFoundError(f"Config file not found: {self.config_path}")

        with open(self.config_path, "r") as f:
            data = json.load(f)

        try:
            self._config = AppConfig.model_validate(data)
            self._cameras = [cam for cam in self._config.cameras if cam.enabled]
        except ValidationError as exc:
            raise ValueError(f"Configuration validation error: {exc}") from exc

    def __getitem__(self, key: str) -> Any:
        return getattr(self._config, key)

    def get(self, key: str, default: Any = None) -> Any:
        return getattr(self._config, key, default)

    @property
    def output_dir(self) -> Path:
        return self._config.output_dir

    @property
    def scratch_dir(self) -> Path:
        return self._config.scratch_dir

    @property
    def cameras(self) -> list[CameraConfig]:
        return self._cameras

    @property
    def max_occupied_space_mb(self) -> int:
        return self._config.max_occupied_space_mb

