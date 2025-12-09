"""Main entry point for home-video service."""

import argparse
import logging
import signal
import sys
import time
from pathlib import Path

from app.config import Config
from app.stream_processor import StreamProcessor

processor: StreamProcessor | None = None

def setup_logging(log_file: Path | None = None) -> None:
    """Setup logging to both file and console."""
    handlers: list[logging.Handler] = [logging.StreamHandler(sys.stdout)]

    if log_file:
        log_file.parent.mkdir(parents=True, exist_ok=True)
        handlers.append(logging.FileHandler(log_file))

    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s - %(name)s - %(levelname)s - %(message)s",
        handlers=handlers,
    )


def signal_handler(signum: int, frame) -> None:
    """Handle shutdown signals gracefully."""
    sig_name = signal.Signals(signum).name
    logging.info(f"Received {sig_name}, shutting down...")
    if processor:
        processor.stop_all()
    sys.exit(0)


def main() -> None:
    global processor

    parser = argparse.ArgumentParser(description="Home video capture service")
    parser.add_argument(
        "-c", "--config", type=Path, default=Path("config/config.json"), help="Path to config file"
    )
    parser.add_argument(
        "-l", "--log-file", type=Path, default=None, help="Path to log file"
    )
    args = parser.parse_args()

    setup_logging(args.log_file)
    logger = logging.getLogger(__name__)

    try:
        logger.info(f"Loading config from {args.config}")
        config = Config(args.config)

        logger.info(f"Found {len(config.cameras)} enabled camera(s)")

        if not config.cameras:
            logger.warning("No cameras enabled, exiting")
            return

        processor = StreamProcessor(config)

        signal.signal(signal.SIGINT, signal_handler)
        signal.signal(signal.SIGTERM, signal_handler)

        logger.info("Starting camera processor...")
        processor.start_all()

        logger.info("Entering main loop (Ctrl+C to stop)")
        while True:
            time.sleep(60)
            status = processor.get_status()
            logger.info(
                f"Status: {status['active_cameras']} cameras, "
                f"queue size: {status['queue_size']}"
            )
            for cam_id, cam_status in status["cameras"].items():
                logger.info(
                    f"  {cam_id}: frames={cam_status['frames_processed']}, "
                    f"process={cam_status['process_alive']}, "
                    f"reader={cam_status['reader_alive']}"
                )

    except FileNotFoundError as e:
        logger.error(f"Configuration error: {e}")
        sys.exit(1)
    except KeyboardInterrupt:
        logger.info("Interrupted")
    except Exception as e:
        logger.exception(f"Unexpected error: {e}")
        sys.exit(1)
    finally:
        if processor:
            processor.stop_all()


if __name__ == "__main__":
    main()
