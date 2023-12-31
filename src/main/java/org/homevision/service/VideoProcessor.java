package org.homevision.service;

import lombok.Getter;
import lombok.Setter;
import org.opencv.core.*;
import org.opencv.highgui.HighGui;
import org.opencv.imgproc.Imgproc;
import org.opencv.videoio.VideoCapture;
import org.opencv.videoio.VideoWriter;
import org.opencv.videoio.Videoio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
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

    @Getter
    @Setter
    private boolean running = true;

    @Getter
    private Mat frame;

    private Mat frameGrayscale;
    private Mat frameAnnotated;


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
        createVideoWriter();
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
        if (!capture.read(frame)) {
            log.error("Failed to capture the frame");
            return false;
        }

        var exp = capture.get(Videoio.CAP_PROP_EXPOSURE);
        var ap = capture.get(Videoio.CAP_PROP_AUTO_EXPOSURE);
        double averageIntensity = correctExposure(exp);
        frame.copyTo(frameAnnotated);

        var s = String.format("Exp: %.2f, Ap: %.2f, avgIntensity: %.2f, fps: %.2f", exp, ap, averageIntensity, getActualFPS());
        Imgproc.putText(frameAnnotated, s, new Point(20, 50), 1, 2.0, new Scalar(255, 255, 0));
        HighGui.imshow(config.getName(), frameAnnotated);
        HighGui.waitKey(1);
        videoOut.write(frame);
        return true;
    }

    private double correctExposure(double currentExposure) {
        final var exp = config.getExposure();
        Imgproc.cvtColor(frame, frameGrayscale, Imgproc.COLOR_BGR2GRAY);
        double averageIntensity = Core.mean(frameGrayscale).val[0];

        if (exp.isAutoCorrect()) {
            if (averageIntensity > exp.getUpperThreshold() && currentExposure > exp.getMinExposure()) {
                capture.set(Videoio.CAP_PROP_EXPOSURE, currentExposure - exp.getCorrectionStep());
            }
            if (averageIntensity < exp.getLowerThreshold() && currentExposure < exp.getMaxExposure()) {
                capture.set(Videoio.CAP_PROP_EXPOSURE, currentExposure + exp.getCorrectionStep());
            }
        }
        return averageIntensity;
    }

    private void createVideoWriter() {
        var currentDate = new Date();
        var dirName = config.getVideoOutPath() + "/" + config.getName() + "/" + dateFormat.format(currentDate);
        var fileName = dirName + "/" + timeFormat.format(currentDate) + "." + config.getVideoFileExtension();

        new File(dirName).mkdirs();

        log.info("Starting new file: " + fileName);
        if (videoOut != null && videoOut.isOpened()) {
            videoOut.release();
        }
        var format = config.getVideoFormat();
        var codec = VideoWriter.fourcc(format.charAt(0), format.charAt(1), format.charAt(2), format.charAt(3));
        videoOut = new VideoWriter(fileName, Videoio.CAP_FFMPEG, codec, config.getFps(),
                new Size(config.getFrameWidth(), config.getFrameHeight()));
        videoOut.set(Videoio.VIDEOWRITER_PROP_QUALITY, config.getVideoQuality());
        videoOut.set(Videoio.VIDEOWRITER_PROP_HW_ACCELERATION, Videoio.VIDEO_ACCELERATION_VAAPI);
        videoOut.set(Videoio.VIDEOWRITER_PROP_HW_ACCELERATION_USE_OPENCL, 1);
    }

    private void shutdown() {
        frame.release();
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

    public double getActualFPS() {
        return 1000.0 / getFrameProcessingTime();
    }


}
