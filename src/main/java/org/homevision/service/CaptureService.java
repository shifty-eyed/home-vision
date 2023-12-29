package org.homevision.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Component
public class CaptureService {

    private ExecutorService pool;

    @Autowired
    private Config config;

    private List<VideoIOProcessor> videoProcessors;

    @PostConstruct
    private void init() {
        var numberOfDevices = config.getAll().size();
        pool = Executors.newFixedThreadPool(numberOfDevices);
        videoProcessors = new ArrayList<>(numberOfDevices);
        for (var deviceConfig : config.getAll()) {
            var proc = new VideoIOProcessor(deviceConfig);
            videoProcessors.add(proc);
            pool.execute(proc);
        }
    }

    @PreDestroy
    private void shutdown() throws InterruptedException {
        videoProcessors.stream().forEach(proc -> proc.setRunning(false));
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);
    }

}
