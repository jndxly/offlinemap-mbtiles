package com.example.mbtiles.service;

import com.example.mbtiles.config.MbtilesProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.sqlite.SQLiteDataSource;

/**
 * 数据集注册表：把配置的每个 .mbtiles 都当作一个独立的只读 SQLite 数据集打开。
 */
@Component
public class MbtilesRegistry {

    private static final Logger log = LoggerFactory.getLogger(MbtilesRegistry.class);

    private static final String SELECT_METADATA = "SELECT name, value FROM metadata";

    private final List<MbtilesDataset> datasets;
    private final Map<String, MbtilesDataset> byName;

    public MbtilesRegistry(MbtilesProperties properties, ObjectMapper objectMapper) {
        List<MbtilesDataset> loaded = new ArrayList<>();
        for (MbtilesProperties.Dataset cfg : properties.resolveDatasets()) {
            MbtilesDataset dataset = open(cfg, properties.getPoolSize(), objectMapper);
            loaded.add(dataset);
            log.info("数据集 {} -> {} | format={} zoom={}~{} bounds={}",
                    dataset.name(), dataset.path(), dataset.metadata().format(),
                    dataset.metadata().minzoom(), dataset.metadata().maxzoom(),
                    Arrays.toString(dataset.metadata().bounds()));
        }
        this.datasets = List.copyOf(loaded);
        Map<String, MbtilesDataset> index = new LinkedHashMap<>();
        for (MbtilesDataset dataset : datasets) {
            index.put(dataset.name(), dataset);
        }
        this.byName = Map.copyOf(index);
    }

    public List<MbtilesDataset> all() {
        return datasets;
    }

    public MbtilesDataset get(String name) {
        return byName.get(name);
    }

    public MbtilesDataset first() {
        return datasets.isEmpty() ? null : datasets.get(0);
    }

    @PreDestroy
    public void close() {
        for (MbtilesDataset dataset : datasets) {
            dataset.close();
        }
    }

    private MbtilesDataset open(MbtilesProperties.Dataset cfg, int poolSize, ObjectMapper objectMapper) {
        if (cfg.getName() == null || cfg.getName().isBlank()) {
            throw new IllegalStateException("数据集缺少 name: " + cfg.getPath());
        }
        Path path = Paths.get(cfg.getPath()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("mbtiles 文件不存在: " + path);
        }
        // SQLite URI 只读打开：不生成 -wal/-shm，不对源文件加锁
        String encodedPath = "/" + path.toString().replace('\\', '/').replace(" ", "%20");
        String url = "jdbc:sqlite:file:" + encodedPath + "?mode=ro";

        SQLiteDataSource sqlite = new SQLiteDataSource();
        sqlite.setUrl(url);

        HikariConfig hikari = new HikariConfig();
        hikari.setDataSource(sqlite);
        hikari.setPoolName("mbtiles-" + cfg.getName());
        hikari.setMaximumPoolSize(Math.max(1, poolSize));
        hikari.setMinimumIdle(1);
        hikari.setIdleTimeout(600_000);
        hikari.setConnectionTimeout(10_000);
        hikari.setConnectionTestQuery("select 1");

        HikariDataSource pool = new HikariDataSource(hikari);
        JdbcTemplate jdbc = new JdbcTemplate(pool);

        MbtilesMetadata metadata = loadMetadata(jdbc);
        JsonNode vectorLayers = parseVectorLayers(metadata, objectMapper);
        return new MbtilesDataset(cfg.getName(), cfg.getLabel(), path, cfg.getPriority(),
                metadata, vectorLayers, jdbc, pool);
    }

    private MbtilesMetadata loadMetadata(JdbcTemplate jdbc) {
        Map<String, String> values = new LinkedHashMap<>();
        jdbc.query(SELECT_METADATA, rs -> {
            while (rs.next()) {
                values.put(rs.getString(1), rs.getString(2));
            }
        });
        return new MbtilesMetadata(values);
    }

    private JsonNode parseVectorLayers(MbtilesMetadata metadata, ObjectMapper objectMapper) {
        String json = metadata.json();
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            return node.get("vector_layers");
        } catch (Exception e) {
            log.warn("解析 metadata.json 失败(数据集 {}): {}", metadata.name(), e.getMessage());
            return null;
        }
    }
}
