package org.homevision.controller;

import org.homevision.service.Config;
import org.homevision.service.VideoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

@RestController
@RequestMapping("/homevision")
public class MainController {

	@Autowired
	private Config config;

	@Autowired
	private VideoService videoService;

	private static final DateFormat dateTimeFormat = new SimpleDateFormat("yyyy-MM-dd__HH_mm_ss");

	@GetMapping(value = "/cam/{cameraIndex}/frame", produces = MediaType.IMAGE_JPEG_VALUE)
	public ResponseEntity<byte[]> getFrame(@PathVariable int cameraIndex, @RequestParam(defaultValue = "1280") int w,
										   @RequestParam(defaultValue = "720") int h,
										   @RequestParam(defaultValue = "40") int q) throws IOException {

		var video = videoService.getVideoProcessors().get(cameraIndex);
		var fileName = config.getAll().get(cameraIndex).getName() + "@" + dateTimeFormat.format(new Date()) + ".jpg";

		return ResponseEntity.ok()
			.header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=" + fileName)
			.contentType(MediaType.IMAGE_JPEG)
			.body(video.getCurrentFrame(w, h, q));

	}

	@PostMapping("/restart")
	public String restart() throws IOException {
		config.load();
		return "OK";
	}
}
