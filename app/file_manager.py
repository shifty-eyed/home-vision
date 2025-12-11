import logging
import os
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
                
                # Ensure output directory doesn't exceed max size
                self.ensureSpace()
                
            except Exception as e:
                logger.error(f"Error moving file {file_path_str}: {e}", exc_info=True)
                continue
    
    def ensureSpace(self) -> None:
        output_dir = self._config.output_dir
        max_size_mb = self._config.max_occupied_space_mb
        max_size_bytes = max_size_mb * 1024 * 1024
        
        if not output_dir.exists() or max_size_mb <= 0:
            return
        
        current_size = self._get_dir_size(output_dir)
        if current_size <= max_size_bytes:
            return
        
        logger.info(
            f"Output directory size ({current_size / (1024 * 1024):.2f} MB) exceeds limit ({max_size_mb} MB). Removing older files..."
        )
        
        all_files = self._get_files_sorted_by_age(output_dir)
        removed_count = 0
        for file_path in all_files:
            if current_size <= max_size_bytes:
                break
            
            try:
                file_size = file_path.stat().st_size
                file_path.unlink()
                current_size -= file_size
                removed_count += 1
                logger.info(f"Removed old file: {file_path} ({file_size / (1024 * 1024):.2f} MB)")
            except Exception as e:
                logger.error(f"Error removing file {file_path}: {e}", exc_info=True)
        
        self._cleanup_empty_dirs(output_dir)
        
        logger.info(
            f"Removed {removed_count} files. Current size: {current_size / (1024 * 1024):.2f} MB"
        )
    
    def _get_dir_size(self, directory: Path) -> int:
        total_size = 0
        try:
            for dirpath, dirnames, filenames in os.walk(directory):
                for filename in filenames:
                    filepath = Path(dirpath) / filename
                    try:
                        total_size += filepath.stat().st_size
                    except (OSError, FileNotFoundError):
                        continue
        except Exception as e:
            logger.error(f"Error calculating directory size for {directory}: {e}", exc_info=True)
        return total_size
    
    def _get_files_sorted_by_age(self, directory: Path) -> list[Path]:
        files_with_mtime = []
        try:
            for dirpath, dirnames, filenames in os.walk(directory):
                for filename in filenames:
                    filepath = Path(dirpath) / filename
                    try:
                        if filepath.is_file():
                            mtime = filepath.stat().st_mtime
                            files_with_mtime.append((mtime, filepath))
                    except (OSError, FileNotFoundError):
                        continue
        except Exception as e:
            logger.error(f"Error listing files in {directory}: {e}", exc_info=True)
            return []
        
        files_with_mtime.sort(key=lambda x: x[0])
        return [filepath for _, filepath in files_with_mtime]
    
    def _cleanup_empty_dirs(self, directory: Path) -> None:
        try:
            for dirpath, dirnames, filenames in os.walk(directory, topdown=False):
                path = Path(dirpath)
                try:
                    if not any(path.iterdir()) and path != directory:
                        path.rmdir()
                        logger.debug(f"Removed empty directory: {path}")
                except OSError:
                    pass
        except Exception as e:
            logger.error(f"Error cleaning up empty directories in {directory}: {e}", exc_info=True)
