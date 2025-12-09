Implementation Plan for Coding Agent
Overview
Build a service capturing video from multiple PoE cameras RTSP protocol and writing to the file and allowing the future code to analyse every n-th frame with coustom open cv algorithm.

Minimalistic implementation:
Python class that manages multiple cameras. 
. Each camera has ONE FFmpeg subprocess that simultaneously:
Copies H.265 to file with 30-min rotation
Decodes every Nth frame to stdout for detection
Frames are queued and processed by detection thread (empty for now) but queue only keeps one last frame per camera.

Step 1: Project Setup
config.json:
{
  "cameras": [
    {"name": "cam1-front", 
    "url": "rtsp://...", 
     "enabled": true,
    "detection_interval": 30,
    "segment_minutes": 30,
    }
  ],
  "output_dir": "/home/rrr/video-out",
  "limitOccupiedSpace": "true",
    "maxOccupiedSpaceGB": 1,
    "keepFreeDiskSpaceGB": 100
}

Step 2: Config Loader
File: config.py
Simple class that:

Loads JSON file
Provides dict access to settings
Creates output directory if missing
Method get_enabled_cameras() filters by enabled flag


Step 3: CameraProcessor Class - Init
File: stream_processor.py
Create dataclass CameraProcess with: camera_id, name, recording_process, extraction_process, frame_count
CameraProcessor.init:

Store config
Initialize: cameras dict, frame_queue (Queue), stop_event (Event), processing_thread
Setup logger


Step 4: Start Camera - Recording
Method: start_camera(camera_config)

Create output dir: {output_dir}/{year_dir}/{month_dir_number_and_name}/{day_of_month_dir}/{cameraName}/
Build FFmpeg command.
Start subprocess.Popen
Launch thread: _frame_reader(camera_id, process, width, height)
Store complete CameraProcess in dict


Step 6: Frame Reader Thread
Method: _frame_reader(camera_id, process, width, height)
Loop until stop_event:

Read width*height*3 bytes from process.stdout
If incomplete: log error, break
Put (camera_id, frame) in queue (timeout 0.5s)


Step 8: Shutdown
Method: stop_camera(camera_id)

Terminate both processes
Wait 5s, then kill if needed
Remove from dict

Method: stop_all()

Set stop_event
Stop all cameras
Join processing_thread (timeout 5s)


Step 9: Status
Method: get_status() returns dict

active_cameras count
queue_size
Per camera: name, frames_processed, both processes alive


Step 10: Main Entry
File: main.py

Load config from JSON
Setup logging (file + console)
Create CameraProcessor
Register SIGINT/SIGTERM handlers → stop_all()
Call start_all()
Loop: sleep 60s, log status


Deliverables
config.py - JSON loader
stream_processor.py - Single class (~200 lines)
main.py - Entry point
config.json - Camera settings
Working system: recording + frame extraction + empty detection

