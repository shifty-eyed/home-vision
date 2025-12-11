"""Main entry point for home-video service."""

import argparse
import logging
import sys
from pathlib import Path

import uvicorn
from fastapi import FastAPI, HTTPException
from fastapi.responses import PlainTextResponse

from app.config import Config
from app.stream_processor import StreamProcessor

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


def main() -> None:
    parser = argparse.ArgumentParser(description="Home video capture service")
    parser.add_argument(
        "-c", "--config", type=Path, default=Path("config/config.json"), help="Path to config file"
    )
    parser.add_argument(
        "-l", "--log-file", type=Path, default=None, help="Path to log file"
    )
    parser.add_argument("--host", type=str, default="0.0.0.0", help="API host")
    parser.add_argument("--port", type=int, default=8000, help="API port")
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

        app = create_app(config)
        uvicorn.run(app, host=args.host, port=args.port, log_level="info")

    except FileNotFoundError as e:
        logger.error(f"Configuration error: {e}")
        sys.exit(1)
    except Exception as e:
        logger.exception(f"Unexpected error: {e}")
        sys.exit(1)


def create_app(config: Config) -> FastAPI:
    app = FastAPI(title="home-video")
    processor = StreamProcessor(config)
    app.state.processor = processor

    @app.on_event("startup")
    async def startup_event() -> None:
        logging.getLogger(__name__).info("Starting camera processor...")
        processor.start_all()

    @app.on_event("shutdown")
    async def shutdown_event() -> None:
        processor.shutdown()

    @app.get("/logs/{camera_id}", response_class=PlainTextResponse)
    async def get_logs(camera_id: str) -> PlainTextResponse:
        try:
            logs = processor.get_logs(camera_id)
        except KeyError:
            raise HTTPException(status_code=404, detail=f"Camera {camera_id} not found")
        return PlainTextResponse(content=logs or "", media_type="text/plain")

    return app


if __name__ == "__main__":
    main()
