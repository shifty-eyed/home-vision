package org.homevision.controller;

import org.apache.commons.io.IOUtils;
import org.homevision.service.Config;
import org.homevision.service.VideoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/homevision")
public class MainController {

	@Autowired
	private Config config;

	@Autowired
	private VideoService videoService;

	private static final DateFormat dateTimeFormat = new SimpleDateFormat("yyyy-MM-dd__HH_mm_ss");

	@GetMapping(value = "/config", produces = MediaType.APPLICATION_JSON_VALUE)
	public Config.VideoSettings[] getConfig() {
		return config.getAll().toArray(new Config.VideoSettings[0]);
	}

	@GetMapping(value = "/config/edit")
	public String getConfigText() throws IOException {
		return config.loadText();
	}

	@PostMapping(value = "/config/edit")
	public String updateConfig(@RequestBody String configJson) throws IOException, InterruptedException {
		config.update(configJson);
		videoService.restart();
		return "OK";
	}

	@GetMapping(value = "/cam/{cameraIndex}/frame", produces = MediaType.IMAGE_JPEG_VALUE)
	public ResponseEntity<byte[]> getFrame(@PathVariable int cameraIndex, @RequestParam(defaultValue = "1280") int w,
										   @RequestParam(defaultValue = "720") int h,
										   @RequestParam(defaultValue = "40") int q) throws IOException {

		if (!videoService.isRunning()) {
			return ResponseEntity.notFound().build();
		}
		if (videoService.getVideoProcessors().size() <= cameraIndex) {
			return ResponseEntity.badRequest().build();
		}


		var video = videoService.getVideoProcessors().get(cameraIndex);
		var fileName = config.getAll().get(cameraIndex).getName() + "@" + dateTimeFormat.format(new Date()) + ".jpg";

		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=" + fileName)
				.contentType(MediaType.IMAGE_JPEG)
				.body(video.getCurrentFrame(w, h, q));
	}

	@GetMapping(value = "/cam/{cameraIndex}/parameter")
	public String getCameraParameterValue(@PathVariable int cameraIndex, @RequestParam int id) {
		if (!checkCameraIndexCorrect(cameraIndex))
			throw new IllegalArgumentException("Not Available");

		var video = videoService.getVideoProcessors().get(cameraIndex);
		return video.getCameraParameter(id);
	}

	@PostMapping(value = "/cam/{cameraIndex}/parameter")
	public String setCameraParameterValue(@PathVariable int cameraIndex, @RequestParam int id, @RequestParam double value) {
		if (!checkCameraIndexCorrect(cameraIndex))
			throw new IllegalArgumentException("Not Available");

		var video = videoService.getVideoProcessors().get(cameraIndex);
		if (video.setCameraParameter(id, value)) {
			return "OK";
		} else {
			return "Failed";
		}
	}

	private boolean checkCameraIndexCorrect(int cameraIndex) {
		return videoService.isRunning() && videoService.getVideoProcessors().size() > cameraIndex;
	}

	@PostMapping("/restart")
	public String restart() throws IOException, InterruptedException {
		config.loadFromFile();
		videoService.restart();
		return "OK";
	}


	@GetMapping("/list-devices")
	public String shell() throws IOException, InterruptedException {
		String command = "v4l2-ctl --list-devices";
		Process process = Runtime.getRuntime().exec(command);
		var timeout = process.waitFor(5, TimeUnit.SECONDS);
		if (!timeout) {
			process.destroy();
			return "Timeout";
		}
		return IOUtils.toString(process.getInputStream(), "UTF-8").replaceAll("\n", "<br/>");
	}

	//add get mapping for individual camera accept parameter for camera index and return empty string if camera is not running




}
