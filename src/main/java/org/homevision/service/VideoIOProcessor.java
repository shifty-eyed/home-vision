package org.homevision.service;

import lombok.Getter;
import org.opencv.core.Mat;
import org.opencv.core.MatOfInt;
import org.opencv.core.Size;
import org.opencv.highgui.HighGui;
import org.opencv.videoio.VideoCapture;
import org.opencv.videoio.VideoWriter;
import org.opencv.videoio.Videoio;
import org.springframework.stereotype.Component;
import org.springframework.util.StopWatch;

import javax.annotation.PostConstruct;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

public class VideoIOProcessor {

    private static final Logger log = Logger.getLogger(VideoIOProcessor.class.getSimpleName());

    private VideoCapture capture;
    private VideoWriter videoOut;
    private Config.VideoSettings config;
    private StopWatch timer;
    private static final DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
    private static final DateFormat timeFormat = new SimpleDateFormat("HH_mm_ss");

    public VideoIOProcessor(Config.VideoSettings config) {
        this.config = config;
    }

    @Getter
    private Mat frame;

    public void start() {
        capture = new VideoCapture(config.getDeviceId(), Videoio.CAP_V4L2, new MatOfInt(
                Videoio.CAP_PROP_FOURCC, VideoWriter.fourcc('M', 'J', 'P', 'G'),
                Videoio.CAP_PROP_FRAME_WIDTH, config.getFrameWidth(), Videoio.CAP_PROP_FRAME_HEIGHT, config.getFrameHeight(),
                Videoio.CAP_PROP_FPS, config.getFps()
        ));
        videoOut = new VideoWriter(config.getVideoOutPath(), Videoio.CAP_FFMPEG, VideoWriter.fourcc('X', 'V', 'I', 'D'), config.getFps(),
                new Size(config.getFrameWidth(), config.getFrameHeight()));
        videoOut.set(Videoio.VIDEOWRITER_PROP_QUALITY, config.getVideoQuality());

        for (int i = 0; i < 500; i++) {
            frame = new Mat();
            if (capture.read(frame)) {
                videoOut.write(frame);
            }
        }

        stop();

    }

    private String buildCurrentFileName() {
        var currentDate = new Date();
        return config.getVideoOutPath() + "/" + config.getName() + "/" + dateFormat.format(currentDate) + "/" + timeFormat.format(currentDate);
    }

    private void createVideoWriter() {
        var fileName = buildCurrentFileName();
        log.info("Starting new file: "+fileName);
        if (videoOut != null && videoOut.isOpened()) {
            videoOut.release();
        }
        videoOut = new VideoWriter(fileName, Videoio.CAP_FFMPEG, VideoWriter.fourcc('X', 'V', 'I', 'D'), config.getFps(),
                new Size(config.getFrameWidth(), config.getFrameHeight()));
        videoOut.set(Videoio.VIDEOWRITER_PROP_QUALITY, config.getVideoQuality());
    }

    public void stop() {
        if (capture.isOpened()) {
            capture.release();
            System.out.println("Camera closed");
        }
        videoOut.release();
    }


}
