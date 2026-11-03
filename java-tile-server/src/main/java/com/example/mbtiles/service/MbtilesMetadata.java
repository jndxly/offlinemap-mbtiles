package com.example.mbtiles.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MBTiles 的 metadata 表内容。
 */
public class MbtilesMetadata {

    private final Map<String, String> values;

    public MbtilesMetadata(Map<String, String> values) {
        this.values = new LinkedHashMap<>(values);
    }

    public Map<String, String> values() {
        return Collections.unmodifiableMap(values);
    }

    public String get(String key) {
        return values.get(key);
    }

    public String name() {
        return values.getOrDefault("name", "mbtiles");
    }

    public String format() {
        return values.getOrDefault("format", "pbf");
    }

    public int minzoom() {
        return intOf("minzoom", 0);
    }

    public int maxzoom() {
        return intOf("maxzoom", 14);
    }

    /** bounds: "minLon,minLat,maxLon,maxLat" */
    public double[] bounds() {
        return doublesOf("bounds", new double[]{-180.0, -85.0, 180.0, 85.0});
    }

    /** center: "lon,lat,zoom" */
    public double[] center() {
        return doublesOf("center", new double[]{0.0, 0.0, 0.0});
    }

    /** metadata.json 字段（OpenMapTiles 用它描述 vector_layers） */
    public String json() {
        return values.get("json");
    }

    private int intOf(String key, int defaultValue) {
        String value = values.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private double[] doublesOf(String key, double[] defaultValue) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        String[] parts = value.split(",");
        double[] result = new double[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                result[i] = Double.parseDouble(parts[i].trim());
            }
            return result;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
