package org.homevision.service;

import lombok.Getter;
import org.apache.commons.io.FileUtils;
import org.bytedeco.ffmpeg.global.avcodec;
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
			entry("BRIGHTNESS", 10),
			entry("CONTRAST", 11),
			entry("SATURATION", 12),
			entry("HUE", 13),
			entry("GAIN", 14),
			entry("EXPOSURE", 15),
			entry("CONVERT_RGB", 16),
			entry("WHITE_BALANCE_BLUE_U", 17),
			entry("RECTIFICATION", 18),
			entry("MONOCHROME", 19),
			entry("SHARPNESS", 20),
			entry("AUTO_EXPOSURE", 21),
			entry("GAMMA", 22),
			entry("TEMPERATURE", 23),
			entry("TRIGGER", 24),
			entry("TRIGGER_DELAY", 25),
			entry("WHITE_BALANCE_RED_V", 26),
			entry("ZOOM", 27),
			entry("FOCUS", 28),
			entry("GUID", 29),
			entry("ISO_SPEED", 30),
			entry("BACKLIGHT", 32),
			entry("PAN", 33),
			entry("TILT", 34),
			entry("ROLL", 35),
			entry("IRIS", 36),
			entry("SETTINGS", 37),
			entry("BUFFERSIZE", 38),
			entry("AUTOFOCUS", 39),
			entry("SAR_NUM", 40),
			entry("SAR_DEN", 41),
			entry("CHANNEL", 43),
			entry("AUTO_WB", 44),
			entry("WB_TEMPERATURE", 45),
			entry("FPS", 5)
	);

	public static final Map<String, Integer> FFMPEG_CODEC_MAP = Map.ofEntries(
			entry("MPEG1VIDEO", avcodec.AV_CODEC_ID_MPEG1VIDEO),
			entry("MPEG2VIDEO", avcodec.AV_CODEC_ID_MPEG2VIDEO),
			entry("MPEG4", avcodec.AV_CODEC_ID_MPEG4),
			entry("H261", avcodec.AV_CODEC_ID_H261),
			entry("H263", avcodec.AV_CODEC_ID_H263),
			entry("H264", avcodec.AV_CODEC_ID_H264),
			entry("H265", avcodec.AV_CODEC_ID_H265),
			entry("VP8", avcodec.AV_CODEC_ID_VP8),
			entry("VP9", avcodec.AV_CODEC_ID_VP9),
			entry("HEVC", avcodec.AV_CODEC_ID_HEVC)

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
		var sizeMax = conf.getRecording().getMaxOccupiedSpaceGB() * FileUtils.ONE_GB;
		var oversizeOccupied = conf.getRecording().isLimitOccupiedSpace() ? Math.max(sizeActual - sizeMax, 0) : 0;

		var diskFreeTarget = conf.getRecording().getKeepFreeDiskSpaceGB() * FileUtils.ONE_GB;
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
