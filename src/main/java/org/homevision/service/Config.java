package org.homevision.service;

import com.google.gson.Gson;
import lombok.Data;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

@Component
public class Config {

    private static final String CONFIG_FILE = "conf/config.json";

    private Gson gson = new Gson();

    @Data
    public static class ConfigDto {
        private VideoSettings global;
        private List<VideoSettings> captureDevices;
    }

    @Data
    public static class VideoSettings {
        private String name;
        private Integer deviceId;
        private Integer frameWidth;
        private Integer frameHeight;
        private Integer fps;
        private Integer fileIntervalSeconds;
        private String videoFormat;
        private Integer videoQuality;
        private String videoOutPath;
        private String videoFileExtension;
        private boolean limitOccupiedSpace;
        private Long maxOccupiedSpaceGB;
        private Long keepFreeDiskSpaceGB;
        private ExposureControl exposure;
    }

    @Data
    public static class ExposureControl {
        private boolean autoCorrect;
        private Integer minExposure;
        private Integer maxExposure;
        private Integer lowerThreshold;
        private Integer upperThreshold;
        private Integer correctionStep;
    }


    private ConfigDto data;

    public List<VideoSettings> getAll() {
        return data.captureDevices;
    }

    public VideoSettings getGlobal() {
        return data.global;
    }

    @PostConstruct
    public void loadFromFile() throws IOException {
        String json = loadText();
        data = init(json);
    }

    private ConfigDto init(String json) {
        var config = gson.fromJson(json, ConfigDto.class);
        for (var deviceConfig : config.captureDevices) {
            var dst = new BeanWrapperImpl(deviceConfig);
            var definedProps = Arrays.stream(dst.getPropertyDescriptors())
                .map(pd -> pd.getName())
                .filter(name -> dst.getPropertyValue(name) != null).toArray(String[]::new);
            BeanUtils.copyProperties(config.global, deviceConfig, definedProps);
        }
        return config;
    }

    public String loadText() throws IOException {
        return IOUtils.toString(new FileReader(CONFIG_FILE));
    }

    public void update(String json) throws IOException {
        data = init(json);
        FileUtils.write(new File(CONFIG_FILE), json, "UTF-8");
    }



}
