package org.homevision.controller;

import com.google.gson.Gson;
import org.springframework.boot.autoconfigure.http.HttpMessageConverters;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.converter.json.GsonHttpMessageConverter;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

	@Bean
	public HttpMessageConverters customConverters(Gson gson) {
		return new HttpMessageConverters(new GsonHttpMessageConverter(gson));
	}

	@Override
	public void addResourceHandlers(ResourceHandlerRegistry registry) {
		registry.addResourceHandler("/v/**")
			.addResourceLocations("file:./web/")
			.setCachePeriod(1);
	}
}