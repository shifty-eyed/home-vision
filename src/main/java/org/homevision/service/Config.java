package org.homevision.service;

import com.google.gson.Gson;
import lombok.Data;
import org.apache.commons.io.IOUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;

@Component
public class Config {

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
    }

    @Data
    public static class ConfigDto {
        private VideoSettings global;
        private List<VideoSettings> captureDevices;
    }

    private ConfigDto data;

    public List<VideoSettings> getAll() {
        return data.captureDevices;
    }


    @PostConstruct
    private void load() throws IOException, IllegalAccessException {
        String json = IOUtils.resourceToString("/conf/config.json", Charset.defaultCharset());
        data = (new Gson()).fromJson(json, ConfigDto.class);


        for (var deviceConfig : data.captureDevices) {
            var dst = new BeanWrapperImpl(deviceConfig);
            var definedProps = Arrays.stream(dst.getPropertyDescriptors())
                    .map(pd -> pd.getName())
                    .filter(name -> dst.getPropertyValue(name) != null).toArray(String[]::new);
            BeanUtils.copyProperties(data.global, deviceConfig, definedProps);
        }
    }

}
