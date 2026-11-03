package com.example.mbtiles.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * mbtiles 服务配置（application.yml 中的 mbtiles.*）。
 *
 * <p>支持同时挂载多个 .mbtiles：
 * <pre>
 * mbtiles:
 *   datasets:
 *     - name: china
 *       path: D:/map/china.mbtiles
 *       priority: 10   # 数值越小优先级越高
 *     - name: taiwan
 *       path: D:/map/taiwan.mbtiles
 *       priority: 5
 * </pre>
 */
@ConfigurationProperties(prefix = "mbtiles")
public class MbtilesProperties {

    /** 兼容旧的单文件配置（datasets 为空时生效） */
    private String file;

    /** 每个数据集的连接池大小 */
    private int poolSize = 4;

    /** 首选数据集无该瓦片时，是否继续尝试其它候选数据集 */
    private boolean fallbackEnabled = true;

    /** 路由结果(z/x/y -> 候选数据集)缓存条数 */
    private int routeCacheSize = 50000;

    /**
     * 瓦片小于该字节数时视为"数据过于稀疏"（例如只有路网、或解压后为空的空瓦片），
     * 继续尝试其它候选数据集并取更完整的那份。
     */
    private int minTileBytes = 1024;

    /** 官方行政边界 GeoJSON 目录；为空则使用 classpath:static/boundary */
    private String boundaryDir = "";

    /**
     * 叠加显示的数据集：会把主源图层克隆一份（绘制在上层）。
     * 用于全国库在某个区域（如台湾）没有数据时，由区域库补齐。
     */
    private String overlayDataset = "taiwan";

    private List<Dataset> datasets = new ArrayList<>();

    private final Glyphs glyphs = new Glyphs();

    /** 归一化：配置了 datasets 就用它，否则退化成单个数据集 */
    public List<Dataset> resolveDatasets() {
        if (datasets != null && !datasets.isEmpty()) {
            return datasets;
        }
        if (file != null && !file.isBlank()) {
            Dataset single = new Dataset();
            single.setName("default");
            single.setLabel("默认");
            single.setPath(file);
            single.setPriority(100);
            return List.of(single);
        }
        throw new IllegalStateException("未配置任何数据集：请设置 mbtiles.datasets 或 mbtiles.file");
    }

    public String getFile() {
        return file;
    }

    public void setFile(String file) {
        this.file = file;
    }

    public int getPoolSize() {
        return poolSize;
    }

    public void setPoolSize(int poolSize) {
        this.poolSize = poolSize;
    }

    public boolean isFallbackEnabled() {
        return fallbackEnabled;
    }

    public void setFallbackEnabled(boolean fallbackEnabled) {
        this.fallbackEnabled = fallbackEnabled;
    }

    public int getRouteCacheSize() {
        return routeCacheSize;
    }

    public void setRouteCacheSize(int routeCacheSize) {
        this.routeCacheSize = routeCacheSize;
    }

    public List<Dataset> getDatasets() {
        return datasets;
    }

    public void setDatasets(List<Dataset> datasets) {
        this.datasets = datasets == null ? new ArrayList<>() : new ArrayList<>(datasets);
    }

    public int getMinTileBytes() {
        return minTileBytes;
    }

    public void setMinTileBytes(int minTileBytes) {
        this.minTileBytes = minTileBytes;
    }

    public String getBoundaryDir() {
        return boundaryDir;
    }

    public void setBoundaryDir(String boundaryDir) {
        this.boundaryDir = boundaryDir == null ? "" : boundaryDir;
    }

    public String getOverlayDataset() {
        return overlayDataset;
    }

    public void setOverlayDataset(String overlayDataset) {
        this.overlayDataset = overlayDataset == null ? "" : overlayDataset;
    }

    public Glyphs getGlyphs() {
        return glyphs;
    }

    public static class Dataset {

        /** 数据集标识，用于 /datasets/{name}/... */
        private String name;

        /** 前端下拉显示名 */
        private String label;

        /** .mbtiles 文件路径 */
        private String path;

        /** 优先级，数值越小越优先（覆盖范围相同时用它打破平局） */
        private int priority = 100;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public int getPriority() {
            return priority;
        }

        public void setPriority(int priority) {
            this.priority = priority;
        }
    }

    public static class Glyphs {

        /** 是否把 /fonts/{fontstack}/{range}.pbf 代理到上游字体服务 */
        private boolean proxyEnabled = true;

        /** 上游字体服务地址 */
        private String upstream = "https://fonts.openmaptiles.org";

        /** 本地字形 PBF 根目录（按序查找，支持 classpath: / file: 前缀） */
        private List<String> localDirs = new ArrayList<>(List.of(
                "classpath:/style/fonts/", "classpath:/static/fonts/"));

        public boolean isProxyEnabled() {
            return proxyEnabled;
        }

        public void setProxyEnabled(boolean proxyEnabled) {
            this.proxyEnabled = proxyEnabled;
        }

        public String getUpstream() {
            return upstream;
        }

        public void setUpstream(String upstream) {
            this.upstream = upstream;
        }

        public List<String> getLocalDirs() {
            return localDirs;
        }

        public void setLocalDirs(List<String> localDirs) {
            this.localDirs = localDirs == null ? new ArrayList<>() : new ArrayList<>(localDirs);
        }
    }
}
