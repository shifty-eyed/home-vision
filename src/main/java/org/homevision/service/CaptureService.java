package org.homevision.service;

import org.opencv.core.Mat;
import org.opencv.core.MatOfInt;
import org.opencv.core.Size;
import org.opencv.dnn.Dnn;
import org.opencv.highgui.HighGui;
import org.opencv.videoio.VideoCapture;
import org.opencv.videoio.VideoWriter;
import org.opencv.videoio.Videoio;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class CaptureService {

    private ExecutorService pool;

    @Autowired
    private Config config;

    private List<VideoIOProcessor> videoProcessors;

    @PostConstruct
    private void init() {
        pool = Executors.newFixedThreadPool(config.getAll().size());
        videoProcessors = new ArrayList<>(config.getAll().size());
        for (var deviceConfig : config.getAll()) {
            var proc = new VideoIOProcessor(deviceConfig);
            videoProcessors.add(proc);
            pool.execute(proc);
        }

    }




}
