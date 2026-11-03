package com.example.mbtiles.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 一个 .mbtiles 数据集：独立只读连接 + 元数据。
 */
public class MbtilesDataset implements AutoCloseable {

    private final String name;
    private final String label;
    private final Path path;
    private final int priority;
    private final MbtilesMetadata metadata;
    private final JsonNode vectorLayers;
    private final JdbcTemplate jdbc;
    private final AutoCloseable pool;

    public MbtilesDataset(String name, String label, Path path, int priority, MbtilesMetadata metadata,
                          JsonNode vectorLayers, JdbcTemplate jdbc, AutoCloseable pool) {
        this.name = Objects.requireNonNull(name);
        this.label = label == null || label.isBlank() ? name : label;
        this.path = path;
        this.priority = priority;
        this.metadata = metadata;
        this.vectorLayers = vectorLayers;
        this.jdbc = jdbc;
        this.pool = pool;
    }

    public String name() {
        return name;
    }

    public String label() {
        return label;
    }

    public Path path() {
        return path;
    }

    public int priority() {
        return priority;
    }

    public MbtilesMetadata metadata() {
        return metadata;
    }

    public JsonNode vectorLayers() {
        return vectorLayers;
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    /** 该数据集是否覆盖指定层级 */
    public boolean coversZoom(int z) {
        return z >= metadata.minzoom() && z <= metadata.maxzoom();
    }

    @Override
    public void close() {
        try {
            pool.close();
        } catch (Exception e) {
            // ignore
        }
    }

    @Override
    public String toString() {
        return name + "(" + path.getFileName() + ")";
    }
}
