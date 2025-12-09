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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Component
public class Config {

    //v4l2-ctl --list-devices

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
        private RecordingControl recording;
        private Map<String, Integer> captureProperties;
    }

    @Data
    public static class RecordingControl {
        private String mode; //never, always, period:10-20, auto
        private String videoCodec;
        private Integer videoBitrateKbps;
        private String videoOutPath;
        private String videoFileExtension;
        private boolean limitOccupiedSpace;
        private Integer fileIntervalSeconds;
        private Long maxOccupiedSpaceGB;
        private Long keepFreeDiskSpaceGB;
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
            copyPropertiesUndefinedInTarget(config.global, deviceConfig);
        }
        return config;
    }

    private void copyPropertiesUndefinedInTarget(Object src, Object dst) {
        if (!src.getClass().equals(dst.getClass())) {
            throw new IllegalArgumentException("Arguments must be of the same class");
        }
        var srcWrapper = new BeanWrapperImpl(src);
        var dstWrapper = new BeanWrapperImpl(dst);
        for (var pdDst : dstWrapper.getPropertyDescriptors()) {
            var name = pdDst.getName();
            var type = pdDst.getPropertyType();
            if (dstWrapper.getPropertyValue(name) == null) {
                var value = srcWrapper.getPropertyValue(name);
                dstWrapper.setPropertyValue(name, value);
            } else if (type.isAssignableFrom(RecordingControl.class)) {
                copyPropertiesUndefinedInTarget(srcWrapper.getPropertyValue(name), dstWrapper.getPropertyValue(name));
            } else if (type.isAssignableFrom(Map.class)) {
                var srcMap = (Map) srcWrapper.getPropertyValue(name);
                var dstMap = (Map) dstWrapper.getPropertyValue(name);
                for (var key : srcMap.keySet()) {
                    if (!dstMap.containsKey(key)) {
                        dstMap.put(key, srcMap.get(key));
                    }
                }
            }
        }
    }

    public String loadText() throws IOException {
        return IOUtils.toString(new FileReader(CONFIG_FILE));
    }

    public void update(String json) throws IOException {
        data = init(json);
        FileUtils.write(new File(CONFIG_FILE), json, "UTF-8");
    }



}
