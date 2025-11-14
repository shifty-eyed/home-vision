# Python RTSP Video Service

A Python-based backend service for recording RTSP streams from IP cameras. Supports H.265/HEVC encoding and manages multiple cameras simultaneously.

## Features

- **Multi-camera support**: Record from multiple RTSP cameras concurrently
- **H.265/HEVC support**: Efficient video compression for 5MP cameras
- **Automatic file rotation**: Creates new video files at configurable intervals
- **Disk space management**: Automatically deletes old files when disk space is low
- **Flexible scheduling**: Record always, never, or during specific time periods
- **JSON configuration**: Easy camera and recording configuration
- **Graceful shutdown**: Properly closes video files on exit

## Requirements

- Python 3.7 or higher
- ffmpeg (must be installed on the system)

### Installing ffmpeg

**Ubuntu/Debian:**
```bash
sudo apt-get update
sudo apt-get install ffmpeg
```

**macOS:**
```bash
brew install ffmpeg
```

**Windows:**
Download from [https://ffmpeg.org/download.html](https://ffmpeg.org/download.html)

## Configuration

Edit `config.json` to configure your cameras and recording settings.

### Configuration Structure

```json
{
  "global": {
    "frameWidth": 2592,
    "frameHeight": 1944,
    "fps": 15,
    "recording": {
      "mode": "always",
      "videoCodec": "hevc",
      "videoBitrateKbps": 8000,
      "videoOutPath": "/home/user/video-out",
      "videoFileExtension": "mp4",
      "fileIntervalSeconds": 300,
      "limitOccupiedSpace": true,
      "maxOccupiedSpaceGB": 50,
      "keepFreeDiskSpaceGB": 10
    }
  },
  "cameras": [
    {
      "name": "front-door",
      "rtspUrl": "rtsp://admin:password@192.168.1.100:554/stream1"
    }
  ]
}
```

### Configuration Options

#### Global Settings

- `frameWidth`: Video frame width in pixels (default: 2592 for 5MP)
- `frameHeight`: Video frame height in pixels (default: 1944 for 5MP)
- `fps`: Frames per second (default: 15)
- `recording`: Default recording settings for all cameras

#### Recording Settings

- `mode`: When to record
  - `"always"`: Record continuously
  - `"never"`: Don't record
  - `"period:HH-HH"`: Record during specific hours (e.g., `"period:08-20"` for 8 AM to 8 PM)
  - `"auto"`: Motion detection (TODO)

- `videoCodec`: Video codec to use
  - `"hevc"` or `"h265"`: H.265/HEVC encoding (recommended for 5MP cameras)
  - `"h264"`: H.264 encoding
  - `"mpeg4"`: MPEG-4 encoding

- `videoBitrateKbps`: Video bitrate in Kbps (default: 8000 for 5MP H.265)
- `videoOutPath`: Directory to save video files
- `videoFileExtension`: Video file format (e.g., "mp4", "mkv")
- `fileIntervalSeconds`: Duration of each video file in seconds (default: 300 = 5 minutes)
- `limitOccupiedSpace`: Whether to limit total storage used
- `maxOccupiedSpaceGB`: Maximum storage in GB
- `keepFreeDiskSpaceGB`: Minimum free disk space to maintain in GB

#### Camera Settings

Each camera must have:
- `name`: Unique camera identifier (used for directory organization)
- `rtspUrl`: RTSP stream URL (format: `rtsp://username:password@ip:port/path`)

Optional camera-specific overrides:
- `frameWidth`, `frameHeight`, `fps`: Override global video settings
- `recording`: Override global recording settings

### RTSP URL Format

For most IP cameras:
```
rtsp://username:password@camera-ip:554/stream1
```

Common stream paths:
- Hikvision: `/Streaming/Channels/101`
- Dahua: `/cam/realmonitor?channel=1&subtype=0`
- Generic: `/stream1`, `/live`, `/h264`, `/h265`

Check your camera's documentation for the correct RTSP URL.

## Usage

### Start the service

```bash
python3 video_service.py
```

### Run as a background service

```bash
nohup python3 video_service.py > video_service.log 2>&1 &
```

### Stop the service

Press `Ctrl+C` or send SIGTERM:
```bash
pkill -f video_service.py
```

## File Organization

Video files are organized as:
```
video-out/
├── front-door/
│   ├── 2024-01-15/
│   │   ├── 08_00_00.mp4
│   │   ├── 08_05_00.mp4
│   │   └── ...
│   └── 2024-01-16/
│       └── ...
└── backyard/
    └── ...
```

## Logging

Logs are written to:
- Console (stdout)
- `video_service.log` file

Log format:
```
2024-01-15 08:00:00 - CameraProcessor-front-door - INFO - Starting recording to: /home/user/video-out/front-door/2024-01-15/08_00_00.mp4
```

## Disk Space Management

The service automatically manages disk space:

1. Every 60 seconds, checks if:
   - Total storage exceeds `maxOccupiedSpaceGB`
   - Free disk space is below `keepFreeDiskSpaceGB`

2. If space is needed:
   - Deletes oldest files first
   - Removes empty directories
   - Logs all deletions

## Comparison with Java Version

This Python service is functionally equivalent to the Java `VideoService.java`:

| Feature | Java (V4L2/OpenCV) | Python (RTSP/ffmpeg) |
|---------|-------------------|---------------------|
| Camera Input | USB/V4L2 devices | RTSP network cameras |
| Encoding | JavaCV/ffmpeg | ffmpeg subprocess |
| Multi-camera | ✓ | ✓ |
| File rotation | ✓ | ✓ |
| Disk management | ✓ | ✓ |
| Time-based recording | ✓ | ✓ |
| JSON config | ✓ | ✓ |
| H.265 support | ✓ | ✓ |

## Troubleshooting

### ffmpeg not found
Ensure ffmpeg is installed and in your PATH:
```bash
ffmpeg -version
```

### Cannot connect to RTSP stream
- Verify camera IP address and credentials
- Check network connectivity: `ping camera-ip`
- Test RTSP URL with VLC or ffplay:
  ```bash
  ffplay rtsp://admin:password@192.168.1.100:554/stream1
  ```

### High CPU usage
- Reduce fps in configuration
- Use `"copy"` mode (direct stream recording without re-encoding)
- Reduce number of concurrent cameras

### Files not being created
- Check output directory permissions
- Verify `videoOutPath` exists or is writable
- Check logs for ffmpeg errors

## Systemd Service (Linux)

Create `/etc/systemd/system/rtsp-video.service`:

```ini
[Unit]
Description=RTSP Video Recording Service
After=network.target

[Service]
Type=simple
User=your-username
WorkingDirectory=/path/to/python-rtsp-service
ExecStart=/usr/bin/python3 /path/to/python-rtsp-service/video_service.py
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

Enable and start:
```bash
sudo systemctl enable rtsp-video
sudo systemctl start rtsp-video
sudo systemctl status rtsp-video
```

## License

Same as parent project.
