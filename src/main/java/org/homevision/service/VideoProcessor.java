package org.homevision.service;

import lombok.Getter;
import lombok.Setter;
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
    private VideoWriter videoOut;
    private final Config.VideoSettings config;
    private static final DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
    private static final DateFormat timeFormat = new SimpleDateFormat("HH_mm_ss");

    private AtomicLong frameProcessingTime = new AtomicLong();

    private AtomicInteger actualExposure = new AtomicInteger();
    private AtomicInteger avgIntensity = new AtomicInteger();

    @Getter
    @Setter
    private volatile boolean running = true;

    @Getter
    private volatile boolean recording = false;

    private Mat frame;

    private Mat frameGrayscale;
    private Mat frameAnnotated;

    private MatOfByte frameBuffer = new MatOfByte();

    @Getter
    private boolean exposureCorrectWorks = true;


    public VideoProcessor(Config.VideoSettings config) {
        this.config = config;
        frame = new Mat();
        frameGrayscale = new Mat();
        frameAnnotated = new Mat();

        log = LoggerFactory.getLogger(VideoProcessor.class.getSimpleName() + "-" + config.getName());

        capture = new VideoCapture(config.getDeviceId(), Videoio.CAP_V4L2, new MatOfInt(
                Videoio.CAP_PROP_FOURCC, VideoWriter.fourcc('M', 'J', 'P', 'G'),
                Videoio.CAP_PROP_FRAME_WIDTH, config.getFrameWidth(), Videoio.CAP_PROP_FRAME_HEIGHT, config.getFrameHeight(),
                Videoio.CAP_PROP_FPS, config.getFps()
        ));
        //capture.set(Videoio.CAP_PROP_AUTO_EXPOSURE, 1);
    }

    public boolean canRecordNow() {
        var mode = config.getRecording().getMode();
        if ("always".equals(mode)) {
            return true;
        } else if ("auto".equals(mode)) {
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

        actualExposure.set((int)capture.get(Videoio.CAP_PROP_EXPOSURE));
        avgIntensity.set(correctExposure(actualExposure.get()));

        if (recording) {
            videoOut.write(frame);
        }
        return true;
    }

    private int correctExposure(int currentExposure) {
        if (!isRunning()) {
            return 0;
        }
        final var exp = config.getExposure();
        Imgproc.cvtColor(frame, frameGrayscale, Imgproc.COLOR_BGR2GRAY);
        double averageIntensity = Core.mean(frameGrayscale).val[0];

        if (exp.isAutoCorrect()) {
            var exposureAdjustment = currentExposure;
            if (averageIntensity > exp.getUpperThreshold() && currentExposure > exp.getMinExposure()) {
                exposureAdjustment -= exp.getCorrectionStep();
            }
            if (averageIntensity < exp.getLowerThreshold() && currentExposure < exp.getMaxExposure()) {
                exposureAdjustment += exp.getCorrectionStep();
            }
            exposureCorrectWorks = capture.set(Videoio.CAP_PROP_EXPOSURE, exposureAdjustment);
        }
        return (int)averageIntensity;
    }

    private void createVideoWriter() {
        var currentDate = new Date();
        var dirName = config.getRecording().getVideoOutPath() + "/" + config.getName() + "/" + dateFormat.format(currentDate);
        var fileName = dirName + "/" + timeFormat.format(currentDate) + "." + config.getRecording().getVideoFileExtension();

        new File(dirName).mkdirs();

        log.info("Starting new file: " + fileName);
        if (videoOut != null && videoOut.isOpened()) {
            videoOut.release();
        }
        var format = config.getRecording().getVideoFormat();
        var codec = VideoWriter.fourcc(format.charAt(0), format.charAt(1), format.charAt(2), format.charAt(3));
        var args = new MatOfInt(
            //Videoio.VIDEOWRITER_PROP_QUALITY, config.getVideoQuality()
            //Videoio.VIDEOWRITER_PROP_FRAMEBYTES, 500
            //Videoio.VIDEOWRITER_PROP_HW_ACCELERATION, Videoio.VIDEO_ACCELERATION_VAAPI
            //Videoio.VIDEOWRITER_PROP_HW_ACCELERATION_USE_OPENCL, 1

        );
        videoOut = new VideoWriter(fileName, Videoio.CAP_FFMPEG, codec, config.getFps(),
                new Size(config.getFrameWidth(), config.getFrameHeight()), args);

        try {
            log.info("Video backend: " + videoOut.getBackendName());
        } catch (Exception e) {}
    }

    private void shutdown() {
        frame.release();
        frameBuffer.release();
        frameAnnotated.release();
        frameGrayscale.release();
        if (capture.isOpened()) {
            capture.release();
            log.info("Camera closed");
        }
        if (videoOut != null && videoOut.isOpened()) {
            videoOut.release();
        }
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
            var s = String.format("exp: %d%s  avgi: %d  frametime: %d", actualExposure.get(), exposureCorrectWorks ? "" : "!",
                    avgIntensity.get(), getFrameProcessingTime());
            Imgproc.putText(frameAnnotated, s, new Point(20, 50), 1, 1.0, new Scalar(255, 255, 0));

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
            if (videoOut != null && videoOut.isOpened()) {
                videoOut.release();
            }
        }
    }

    public String getCameraParameter(int parameter) {
        return String.valueOf(capture.get(parameter));
    }

    public boolean setCameraParameter(int parameter, double value) {
        return capture.set(parameter, value);
    }


}
