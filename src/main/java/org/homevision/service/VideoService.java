package org.homevision.service;

import lombok.Getter;
import org.apache.commons.io.FileUtils;
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
import java.util.Map;
import static java.util.Map.entry;

@Component
public class VideoService {

	public static final Map<String, Integer> OPENCV_CAPTURE_PROPERTY_MAP = Map.ofEntries(
			entry("CAP_PROP_FRAME_WIDTH", 3),
			entry("CAP_PROP_FRAME_HEIGHT", 4),
			entry("CAP_PROP_FPS", 5),
			entry("CAP_PROP_FOURCC", 6),
			entry("CAP_PROP_FRAME_COUNT", 7),
			entry("CAP_PROP_FORMAT", 8),
			entry("CAP_PROP_MODE", 9),
			entry("CAP_PROP_BRIGHTNESS", 10),
			entry("CAP_PROP_CONTRAST", 11),
			entry("CAP_PROP_SATURATION", 12),
			entry("CAP_PROP_HUE", 13),
			entry("CAP_PROP_GAIN", 14),
			entry("CAP_PROP_EXPOSURE", 15),
			entry("CAP_PROP_CONVERT_RGB", 16),
			entry("CAP_PROP_WHITE_BALANCE_BLUE_U", 17),
			entry("CAP_PROP_RECTIFICATION", 18),
			entry("CAP_PROP_MONOCHROME", 19),
			entry("CAP_PROP_SHARPNESS", 20),
			entry("CAP_PROP_AUTO_EXPOSURE", 21),
			entry("CAP_PROP_GAMMA", 22),
			entry("CAP_PROP_TEMPERATURE", 23),
			entry("CAP_PROP_TRIGGER", 24),
			entry("CAP_PROP_TRIGGER_DELAY", 25),
			entry("CAP_PROP_WHITE_BALANCE_RED_V", 26),
			entry("CAP_PROP_ZOOM", 27),
			entry("CAP_PROP_FOCUS", 28),
			entry("CAP_PROP_GUID", 29),
			entry("CAP_PROP_ISO_SPEED", 30),
			entry("CAP_PROP_BACKLIGHT", 32),
			entry("CAP_PROP_PAN", 33),
			entry("CAP_PROP_TILT", 34),
			entry("CAP_PROP_ROLL", 35),
			entry("CAP_PROP_IRIS", 36),
			entry("CAP_PROP_SETTINGS", 37),
			entry("CAP_PROP_BUFFERSIZE", 38),
			entry("CAP_PROP_AUTOFOCUS", 39),
			entry("CAP_PROP_SAR_NUM", 40),
			entry("CAP_PROP_SAR_DEN", 41),
			entry("CAP_PROP_BACKEND", 42),
			entry("CAP_PROP_CHANNEL", 43),
			entry("CAP_PROP_AUTO_WB", 44),
			entry("CAP_PROP_WB_TEMPERATURE", 45)
	);

	private static final Logger log = LoggerFactory.getLogger(VideoService.class.getSimpleName());

	private ExecutorService pool;

	@Autowired
	private Config config;

	@Getter
	private List<VideoProcessor> videoProcessors;

	@Getter
	private boolean running = false;

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
		running = true;
	}

	public void restart() throws InterruptedException {
		log.info("Restarting video threads.");
		shutdown();
		init();
	}

	@Scheduled(fixedRate = 60000)
	private void ensureDiskSpace() throws IOException {
		File storageDir = new File(config.getGlobal().getRecording().getVideoOutPath());

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

	@Scheduled(fixedDelay = 1000)
	private void ensureVideoRecording() {
		for (var proc : videoProcessors) {
			if (proc.isRunning()) {
				proc.setRecording(proc.canRecordNow());
			}
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
		running = false;
		videoProcessors.stream().forEach(proc -> proc.setRunning(false));
		pool.shutdown();
		pool.awaitTermination(5, TimeUnit.SECONDS);
	}

}
