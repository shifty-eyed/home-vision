#!/bin/bash
# Start script for RTSP Video Service

# Get the directory where this script is located
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

# Change to the service directory
cd "$DIR"

# Check if ffmpeg is installed
if ! command -v ffmpeg &> /dev/null; then
    echo "ERROR: ffmpeg is not installed"
    echo "Please install ffmpeg:"
    echo "  Ubuntu/Debian: sudo apt-get install ffmpeg"
    echo "  macOS: brew install ffmpeg"
    exit 1
fi

# Check if config file exists
if [ ! -f "config.json" ]; then
    echo "ERROR: config.json not found"
    echo "Please create a config.json file. See config.json.example"
    exit 1
fi

# Check Python version
PYTHON_CMD=""
if command -v python3 &> /dev/null; then
    PYTHON_CMD="python3"
elif command -v python &> /dev/null; then
    PYTHON_VERSION=$(python --version 2>&1 | awk '{print $2}' | cut -d. -f1)
    if [ "$PYTHON_VERSION" -ge 3 ]; then
        PYTHON_CMD="python"
    fi
fi

if [ -z "$PYTHON_CMD" ]; then
    echo "ERROR: Python 3 is not installed"
    exit 1
fi

echo "Starting RTSP Video Service..."
echo "Python: $PYTHON_CMD"
echo "Directory: $DIR"
echo ""

# Start the service
exec $PYTHON_CMD video_service.py
