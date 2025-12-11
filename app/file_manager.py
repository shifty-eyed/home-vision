import logging
import re
import shutil
from pathlib import Path
from queue import Empty, Queue

from app.config import Config

logger = logging.getLogger(__name__)


class FileManager:
    
    def __init__(self, config: Config):
        self._config = config
    
    def move(self, file_queue: Queue[str]) -> None:
        output_dir = self._config.output_dir
        files_to_move = []
        
        while True:
            try:
                file_path = file_queue.get_nowait()
                files_to_move.append(file_path)
            except Empty:
                break
        
        for file_path_str in files_to_move:
            try:
                file_path = Path(file_path_str)
                filename = file_path.name
                # Pattern: cam_id_YYYY_MM_DD_HH_MM_SS.mp4
                pattern = re.compile(r"^([^_]+)_(\d{4})_(\d{2})_(\d{2})_(\d{2})_(\d{2})_(\d{2})\.mp4$")
                match = pattern.match(filename)
                
                if not match:
                    logger.error(f"Could not parse filename pattern: {filename}")
                    continue
                
                cam_id = match.group(1)
                year = int(match.group(2))
                month = int(match.group(3))
                day = int(match.group(4))
                
                target_dir = output_dir / f"{year}_{month:02d}_{day:02d}" / cam_id
                target_dir.mkdir(parents=True, exist_ok=True)
                
                # Move file
                target_path = target_dir / filename
                shutil.move(str(file_path), str(target_path))
                logger.info(f"Moved file: {file_path} -> {target_path}")
                
            except Exception as e:
                logger.error(f"Error moving file {file_path_str}: {e}", exc_info=True)
                continue
