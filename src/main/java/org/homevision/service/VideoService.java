package org.homevision.service;

import lombok.Getter;
import org.apache.commons.io.FileUtils;
import org.opencv.highgui.HighGui;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Component
public class VideoService {

	private static final Logger log = LoggerFactory.getLogger(VideoService.class.getSimpleName());

	private ExecutorService pool;

	@Autowired
	private Config config;

	@Getter
	private List<VideoProcessor> videoProcessors;

	@PostConstruct
	private void init() {
		var numberOfDevices = config.getAll().size();
		pool = Executors.newFixedThreadPool(numberOfDevices);
		videoProcessors = new ArrayList<>(numberOfDevices);
		for (var deviceConfig : config.getAll()) {
			var proc = new VideoProcessor(deviceConfig);
			videoProcessors.add(proc);
			pool.execute(proc);
		}
	}

	@Scheduled(fixedRate = 60000)
	private void ensureDiskSpace() throws IOException {
		File storageDir = new File(config.getGlobal().getVideoOutPath());

		var oversize = getBytesToFreeUp(storageDir);
		if (oversize > 0) {
			log.info("Oversize = " + FileUtils.byteCountToDisplaySize(oversize) + ". Deleting older files.");
			var files = FileUtils.listFiles(storageDir, null, true)
				.stream()
				.sorted(Comparator.comparingLong(File::lastModified))
				.toList();
			for (var file : files) {
				if (file.isFile()) {
					log.info("Deleting: " + file.getAbsolutePath() + ", size: " + FileUtils.byteCountToDisplaySize(file.length()));
					oversize -= file.length();
					file.delete();
				}
				if (oversize < 0) {
					break;
				}

			}
			files.stream().filter(File::isDirectory).forEach(dir -> {
				var contents = dir.list();
				if (contents == null || contents.length == 0) {
					log.info("Deleting empty directory: " + dir.getAbsolutePath());
					dir.delete();
				}
			});
		}
	}

	private long getBytesToFreeUp(File storageDir) throws IOException {
		final var conf = config.getGlobal();
		var sizeActual = FileUtils.sizeOfDirectory(storageDir);
		var sizeMax = conf.getMaxOccupiedSpaceGB() * FileUtils.ONE_GB;
		var oversizeOccupied = conf.isLimitOccupiedSpace() ? Math.max(sizeActual - sizeMax, 0) : 0;

		var diskFreeTarget = conf.getKeepFreeDiskSpaceGB() * FileUtils.ONE_GB;
		var diskFreeActual = Files.getFileStore(storageDir.toPath()).getUsableSpace();
		var oversizeOccupiedFreeSpace = Math.max(diskFreeTarget - diskFreeActual, 0);

		return Math.max(oversizeOccupied, oversizeOccupiedFreeSpace);
	}

	@PreDestroy
	private void shutdown() throws InterruptedException {
		HighGui.destroyAllWindows();
		videoProcessors.stream().forEach(proc -> proc.setRunning(false));
		pool.shutdown();
		pool.awaitTermination(5, TimeUnit.SECONDS);
	}

}
