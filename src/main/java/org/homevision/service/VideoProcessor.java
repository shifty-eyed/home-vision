package org.homevision.service;

import lombok.Getter;
import lombok.Setter;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.videoio.VideoCapture;
import org.opencv.videoio.VideoWriter;
import org.opencv.videoio.Videoio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class VideoProcessor implements Runnable {

    private final Logger log;
    private final VideoCapture capture;

    private FFmpegFrameRecorder recorder;
    private final Config.VideoSettings config;
    private static final DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
    private static final DateFormat timeFormat = new SimpleDateFormat("HH_mm_ss");

    private AtomicLong frameProcessingTime = new AtomicLong();

    @Getter
    @Setter
    private volatile boolean running = true;

    @Getter
    private volatile boolean recording = false;

    private Mat frame;

    private Mat frameAnnotated;

    private MatOfByte frameBuffer = new MatOfByte();

    private OpenCVFrameConverter.ToMat converterToMat = new OpenCVFrameConverter.ToMat();

    public VideoProcessor(Config.VideoSettings config) {
        this.config = config;
        frame = new Mat();
        frameAnnotated = new Mat();

        log = LoggerFactory.getLogger(VideoProcessor.class.getSimpleName() + "-" + config.getName());

        capture = new VideoCapture(config.getDeviceId(), Videoio.CAP_V4L2, new MatOfInt(
                Videoio.CAP_PROP_FOURCC, VideoWriter.fourcc('M', 'J', 'P', 'G'),
                Videoio.CAP_PROP_FRAME_WIDTH, config.getFrameWidth(), Videoio.CAP_PROP_FRAME_HEIGHT, config.getFrameHeight()
                //Videoio.CAP_PROP_FPS, config.getFps()
        ));
        applyCaptureProperties();
    }

    private void applyCaptureProperties() {
        if (config.getCaptureProperties() == null) {
            return;
        }
        for (var entry : config.getCaptureProperties().entrySet()) {
            var key = entry.getKey();
            var value = entry.getValue();
            var paramId = VideoService.OPENCV_CAPTURE_PROPERTY_MAP.get(key);
            if (paramId == null) {
                log.error("Unsupported capture property: " + key);
                continue;
            }
            if (!capture.set(paramId, value)) {
                log.error("Failed to set capture property: " + key + " = " + value);
            } else {
                log.info("Capture property set: " + key + " = " + value);
            }
        }
    }

    public boolean canRecordNow() {
        var mode = config.getRecording().getMode();
        if ("always".equals(mode)) {
            return true;
        } else if ("auto".equals(mode)) {//TODO: implement on motion detection
            return true;
        } else if (mode != null && mode.startsWith("period:")) {
            var period = mode.substring(7);
            var parts = period.split("-");
            if (parts.length != 2) {
                log.error("Invalid period format: " + period);
                return false;
            }
            var start = Integer.parseInt(parts[0]);
            var end = Integer.parseInt(parts[1]);
            var currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            return currentHour >= start && currentHour <= end;
        }
        return false;
    }


    public void run() {
        long expectedProcessingTime = 1000 / config.getFps();
        long videoFileStartTime = System.currentTimeMillis();
        while (isRunning()) {
            final long frameStartTime = System.currentTimeMillis();

            if (!processFrame()) {
                break;
            }

            frameProcessingTime.set(System.currentTimeMillis() - frameStartTime);
            if (frameProcessingTime.get() < expectedProcessingTime) {
                try {
                    Thread.sleep(expectedProcessingTime - frameProcessingTime.get());
                } catch (InterruptedException e) {
                   break;
                }
            }

            if (System.currentTimeMillis() - videoFileStartTime > config.getFileIntervalSeconds() * 1000) {
                createVideoWriter();
                videoFileStartTime = System.currentTimeMillis();
            }
        }
        shutdown();
    }

    private boolean processFrame() {
        synchronized (frame) {
            if (!capture.read(frame)) {
                log.error("Failed to capture the frame");
                return false;
            }
        }

        if (recording) {
            try {
                recorder.record(converterToMat.convert(frame));
            } catch (FFmpegFrameRecorder.Exception e) {
                throw new RuntimeException(e);
            }
        }
        return true;
    }

    private void safeStopRecording() {
        if (recorder != null) {
            try {
                recorder.stop();
                recorder.release();
            } catch (Exception e) {
                log.error("Failed to stop recorder", e);
            }
        }
    }

    private void createVideoWriter() {
        var currentDate = new Date();
        var dirName = config.getRecording().getVideoOutPath() + "/" + config.getName() + "/" + dateFormat.format(currentDate);
        var fileName = dirName + "/" + timeFormat.format(currentDate) + "." + config.getRecording().getVideoFileExtension();

        new File(dirName).mkdirs();

        log.info("Starting new file: " + fileName);
        safeStopRecording();

        var videoCodec = VideoService.FFMPEG_CODEC_MAP.get(config.getRecording().getVideoCodec());
        recorder = new FFmpegFrameRecorder(fileName, config.getFrameWidth(), config.getFrameHeight());
        recorder.setVideoCodec(videoCodec);
        recorder.setFormat(config.getRecording().getVideoFileExtension());
        recorder.setFrameRate(config.getFps());
        recorder.setVideoBitrate(12_000_000);
        try {
            recorder.start();
        } catch (org.bytedeco.javacv.FrameRecorder.Exception e) {
            log.error("Failed to start recorder", e);
        }
    }

    private void shutdown() {
        frame.release();
        frameBuffer.release();
        frameAnnotated.release();
        if (capture.isOpened()) {
            capture.release();
            log.info("Camera closed");
        }
        safeStopRecording();

    }

    public long getFrameProcessingTime() {
        return frameProcessingTime.get();
    }

    public byte[] getCurrentFrame(int w, int h, int quality) {
        synchronized (frame) {
            if (!isRunning() || frame.empty()) {
                return null;
            }

            Imgproc.resize(frame, frameAnnotated, new Size(w, h));
            var s = String.format("frametime: %d", getFrameProcessingTime());
            Imgproc.putText(frameAnnotated, s, new Point(20, 50), 1, 1.0, new Scalar(155, 155, 0));

            Imgcodecs.imencode(".jpg", frameAnnotated, frameBuffer, new MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, quality));
            return frameBuffer.toArray();
        }
    }

    public void setRecording(boolean recording) {
        if (this.recording == recording) {
            return;
        }
        if (recording) {
            createVideoWriter();
            this.recording = true;
        } else {
            this.recording = false;
            safeStopRecording();
        }
    }

    public String getCameraParameter(int parameter) {
        return String.valueOf(capture.get(parameter));
    }

    public boolean setCameraParameter(int parameter, double value) {
        return capture.set(parameter, value);
    }


}
