package org.homevision.controller;

import com.google.gson.Gson;
import org.springframework.boot.autoconfigure.http.HttpMessageConverters;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.GsonHttpMessageConverter;

@Configuration
public class WebConfig {

	@Bean
	public HttpMessageConverters customConverters(Gson gson) {
		return new HttpMessageConverters(new GsonHttpMessageConverter(gson));
	}
}