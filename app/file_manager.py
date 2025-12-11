import logging
import re
import shutil
from pathlib import Path
from queue import Empty, Queue

logger = logging.getLogger(__name__)


class FileManager:
    """Manages moving completed video files from scratch directory to target directory."""

    @staticmethod
    def move(file_queue: Queue[str], output_dir: Path) -> None:
        """
        Process all files in the queue and move them to target directory.
        
        Args:
            file_queue: Queue containing file paths to move
            output_dir: Base output directory for organized storage
        """
        files_to_move = []
        
        # Drain the queue
        while True:
            try:
                file_path = file_queue.get_nowait()
                files_to_move.append(file_path)
            except Empty:
                break
        
        if not files_to_move:
            return
        
        logger.info(f"Processing {len(files_to_move)} file(s) for moving")
        
        for file_path_str in files_to_move:
            try:
                file_path = Path(file_path_str)
                
                # Check if file exists
                if not file_path.exists():
                    logger.warning(f"File does not exist, skipping: {file_path}")
                    continue
                
                # Parse filename: {cam.id}_{YYYY}_{MM}_{DD}_{HH}_{MM}_{SS}.mp4
                # Extract camera ID and date components
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
                
                # Create target directory structure: {output_dir}/{year}_{month:02d}_{day:02d}/{cam_id}/
                target_dir = output_dir / f"{year}_{month:02d}_{day:02d}" / cam_id
                target_dir.mkdir(parents=True, exist_ok=True)
                
                # Move file to target directory
                target_path = target_dir / filename
                shutil.move(str(file_path), str(target_path))
                logger.info(f"Moved file: {file_path} -> {target_path}")
                
            except Exception as e:
                logger.error(f"Error moving file {file_path_str}: {e}", exc_info=True)
                continue
