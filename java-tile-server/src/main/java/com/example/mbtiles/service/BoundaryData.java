package com.example.mbtiles.service;

import com.example.mbtiles.config.MbtilesProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 官方行政边界数据（GS(2020)4619 标准地图转换的 GeoJSON）可用性检测。
 *
 * <p>查找位置：
 * <ol>
 *   <li>mbtiles.boundary-dir 配置的外部目录（可为原项目里的 standard-2020 目录）；</li>
 *   <li>classpath:static/boundary/（默认）。</li>
 * </ol>
 *
 * <p>这些 GeoJSON 提供国界/海岸线、省界、县界与省级行政区面，
 * 是海岸线与台湾等岛屿能正确显示的关键（mbtiles 内没有海洋数据）。
 */
@Component
public class BoundaryData {

    private static final Logger log = LoggerFactory.getLogger(BoundaryData.class);

    public static final String PROBE = "national-boundary.json";

    private final MbtilesProperties properties;
    private volatile Boolean available;

    public BoundaryData(MbtilesProperties properties) {
        this.properties = properties;
    }

    public boolean available() {
        Boolean cached = this.available;
        if (cached != null) {
            return cached;
        }
        boolean probe = probe();
        this.available = probe;
        if (probe) {
            log.info("已启用官方行政边界数据（{}）", PROBE);
        } else {
            log.warn("未找到官方行政边界数据（{}）：海岸线/岛屿可能无法显示。"
                    + "请配置 mbtiles.boundary-dir，或把 GeoJSON 放到 src/main/resources/static/boundary/", PROBE);
        }
        return probe;
    }

    private boolean probe() {
        String dir = properties.getBoundaryDir();
        if (dir != null && !dir.isBlank()) {
            Path external = Paths.get(dir, PROBE);
            if (Files.isRegularFile(external)) {
                return true;
            }
        }
        return new ClassPathResource("static/boundary/" + PROBE).exists();
    }
}
