package org.homevision.service;

import lombok.Getter;
import lombok.Setter;
import org.apache.commons.io.FileUtils;
import org.opencv.core.Mat;
import org.opencv.core.MatOfInt;
import org.opencv.core.Size;
import org.opencv.videoio.VideoCapture;
import org.opencv.videoio.VideoWriter;
import org.opencv.videoio.Videoio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StopWatch;

import java.io.File;
import java.nio.file.Files;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

public class VideoIOProcessor implements Runnable {

    private final Logger log;
    private final VideoCapture capture;
    private VideoWriter videoOut;
    private final Config.VideoSettings config;
    private static final DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
    private static final DateFormat timeFormat = new SimpleDateFormat("HH_mm_ss");

    @Getter
    @Setter
    private boolean running = true;

    public VideoIOProcessor(Config.VideoSettings config) {
        this.config = config;
        frame = new Mat();

        log = LoggerFactory.getLogger(VideoIOProcessor.class.getSimpleName() + "-" + config.getName());

        capture = new VideoCapture(config.getDeviceId(), Videoio.CAP_V4L2, new MatOfInt(
                Videoio.CAP_PROP_FOURCC, VideoWriter.fourcc('M', 'J', 'P', 'G'),
                Videoio.CAP_PROP_FRAME_WIDTH, config.getFrameWidth(), Videoio.CAP_PROP_FRAME_HEIGHT, config.getFrameHeight(),
                Videoio.CAP_PROP_FPS, config.getFps()
        ));
        createVideoWriter();
    }

    @Getter
    private Mat frame;

    public void run() {
        long expectedProcessingTime = 1000 / config.getFps();
        long videoFileStartTime = System.currentTimeMillis();
        while (isRunning()) {
            final long frameStartTime = System.currentTimeMillis();

            if (!processFrame()) {
                break;
            }

            final long processingTime = System.currentTimeMillis() - frameStartTime;
            if (processingTime < expectedProcessingTime) {
                try {
                    Thread.sleep(expectedProcessingTime - processingTime);
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
        videoOut.write(frame);
        return true;
    }

    private void createVideoWriter() {
        var currentDate = new Date();
        var dirName = config.getVideoOutPath() + "/" + config.getName() + "/" + dateFormat.format(currentDate);
        var fileName = dirName + "/" + timeFormat.format(currentDate) + ".avi";

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
    }

    private void shutdown() {
        if (capture.isOpened()) {
            capture.release();
            log.info("Camera closed");
        }
        if (videoOut != null && videoOut.isOpened()) {
            videoOut.release();
        }
    }


}
