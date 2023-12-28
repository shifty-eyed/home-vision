package org.homevision.service;

import com.google.gson.Gson;
import lombok.Data;
import org.apache.commons.io.IOUtils;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class Config {

    @Data
    public static class VideoSettings {
        private String name;
        private int frameWidth;
        private int frameHeight;
        private int fps;
        private int fileIntervalSeconds;
        private String videoFormat;
        private int videoQuality;
        private String videoOutPath;
    }

    @Data
    public static class ConfigDto {
        private VideoSettings global;
        private List<VideoSettings> captureDevices;
    }

    private ConfigDto rawData;

    private Map<String, VideoSettings> configHolder;

    public VideoSettings get(String name) {
        return configHolder.get(name);
    }

    public List<VideoSettings> getAll() {
        return rawData.captureDevices;
    }


    @PostConstruct
    void load() throws IOException, IllegalAccessException {
        Gson gson = new Gson();
        String json = IOUtils.resourceToString("/conf/config.json", Charset.defaultCharset());
        rawData = gson.fromJson(json, ConfigDto.class);

        configHolder = new HashMap<>(rawData.captureDevices.size());

        for (var deviceConfig : rawData.captureDevices) {
            for (Field f : deviceConfig.getClass().getFields()) {
                if (f.get(deviceConfig) == null) {
                    f.set(deviceConfig, f.get(rawData.global));
                }
            }
            configHolder.put(deviceConfig.getName(), deviceConfig);
        }
    }

}
