package org.homevision;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfInt;
import org.opencv.highgui.HighGui;
import org.opencv.videoio.VideoCapture;
import org.opencv.videoio.VideoWriter;
import org.opencv.videoio.Videoio;

public class CameraTest {
    static {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
    }

    public static void main(String[] args) {
        boolean mjpg = false;
        if (args.length > 0) {
            mjpg = args[0].equals("mjpg");
        }

        VideoCapture capture = new VideoCapture(0, Videoio.CAP_ANY,
                mjpg ? new MatOfInt(Videoio.CAP_PROP_FOURCC, VideoWriter.fourcc('M', 'J', 'P', 'G'))
                    : new MatOfInt());
        //capture.set(Videoio.CAP_PROP_FPS, 60);
        capture.set(Videoio.CAP_PROP_FRAME_WIDTH, 1920);
        capture.set(Videoio.CAP_PROP_FRAME_HEIGHT, 1080);

        if (!capture.isOpened()) {
            System.out.println("Error: Can't open video stream");
            return;
        }

        Mat frame = new Mat();
        while (true) {
            if (capture.read(frame)) {
                HighGui.imshow("Camera Test", frame);
                var key = HighGui.waitKey(10);
                if (key != -1) {
                    break;
                }
            } else {
                System.out.println("Error: Can't capture frame");
                break;
            }
        }

        capture.release();
    }
}
