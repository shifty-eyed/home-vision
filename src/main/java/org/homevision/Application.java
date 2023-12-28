package org.homevision;

import org.opencv.core.Core;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
public class Application {

	public static void main(String[] args) throws Exception {
		System.loadLibrary("opencv_java481");
		SpringApplicationBuilder builder = new SpringApplicationBuilder(Application.class);
		builder.headless(false);
		builder.run(args);
	}

}

