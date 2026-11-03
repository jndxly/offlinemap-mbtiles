package com.example.mbtiles.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 把外部官方边界目录挂到 /boundary/**（classpath:static/boundary 由 Boot 默认静态资源提供）。
 */
@Configuration
public class BoundaryResourceConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(BoundaryResourceConfig.class);

    private final MbtilesProperties properties;

    public BoundaryResourceConfig(MbtilesProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String dir = properties.getBoundaryDir();
        if (dir == null || dir.isBlank()) {
            return;
        }
        Path path = Paths.get(dir).toAbsolutePath().normalize();
        if (!Files.isDirectory(path)) {
            log.warn("mbtiles.boundary-dir 不是有效目录: {}", path);
            return;
        }
        String location = "file:" + path.toString().replace('\\', '/') + "/";
        registry.addResourceHandler("/boundary/**")
                .addResourceLocations(location)
                .setCachePeriod((int) Duration.ofDays(7).toSeconds());
        log.info("边界数据目录: {} -> /boundary/**", path);
    }
}
