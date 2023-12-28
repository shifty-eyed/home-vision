package org.homevision.service;

import org.opencv.core.Mat;
import org.opencv.core.MatOfInt;
import org.opencv.core.Size;
import org.opencv.dnn.Dnn;
import org.opencv.highgui.HighGui;
import org.opencv.videoio.VideoCapture;
import org.opencv.videoio.VideoWriter;
import org.opencv.videoio.Videoio;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class CaptureService {

    private VideoCapture capture;
    private VideoWriter videoOut;
    private ExecutorService pool = Executors.newSingleThreadExecutor();

    private Config config;

    @PostConstruct
    private void init() {
        pool.execute(() -> startCapture(0));
    }

    public void startCapture(int cameraIndex) {
        capture = new VideoCapture(cameraIndex, Videoio.CAP_V4L2, new MatOfInt(
                Videoio.CAP_PROP_FOURCC,  VideoWriter.fourcc('M', 'J', 'P', 'G'),
                Videoio.CAP_PROP_FRAME_WIDTH, 1920, Videoio.CAP_PROP_FRAME_HEIGHT, 1080,
                //Videoio.CAP_PROP_FRAME_WIDTH, 1280, Videoio.CAP_PROP_FRAME_HEIGHT, 720,
                Videoio.CAP_PROP_FPS, 60
        ));
        videoOut = new VideoWriter("/home/rrr/video1.avi", Videoio.CAP_FFMPEG, VideoWriter.fourcc('X', 'V', 'I', 'D'), 60,
                new Size(1920, 1080));
        videoOut.set(Videoio.VIDEOWRITER_PROP_QUALITY, 30);

        for (int i=0; i<500; i++) {
            Mat frame = new Mat();
            if (capture.read(frame)) {
                videoOut.write(frame);
                HighGui.imshow("Camera Feed", frame);
                HighGui.waitKey(1);
            }
        }

        stopCapture();

    }

    public void stopCapture() {
        if (capture.isOpened()) {
            capture.release();
            System.out.println("Camera closed");
        }
        videoOut.release();
        HighGui.destroyAllWindows();
    }


}
