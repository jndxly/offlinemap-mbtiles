package com.example.mbtiles.service;

import com.example.mbtiles.config.MbtilesProperties;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 瓦片路由：根据请求的 z/x/y 反算瓦片地理范围，与各数据集 metadata.bounds 求交，
 * 从而"自适应"选择应该查询哪个 .mbtiles。
 *
 * <p>排序规则：
 * <ol>
 *   <li>瓦片与数据集 bounds 的重叠比例越大越优先（说明该瓦片主要落在这个数据集里）；</li>
 *   <li>重叠比例相同时，priority 数值小的优先（可把区域更精细的数据集设为高优先级）。</li>
 * </ol>
 */
@Component
public class TileRouter {

    private final MbtilesRegistry registry;
    private final int cacheSize;

    /** 简单 LRU：key = "z/x/y"，value = 候选数据集名（按顺序） */
    private final Map<String, List<String>> routeCache;

    public TileRouter(MbtilesRegistry registry, MbtilesProperties properties) {
        this.registry = registry;
        this.cacheSize = Math.max(0, properties.getRouteCacheSize());
        this.routeCache = new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, List<String>> eldest) {
                return size() > cacheSize;
            }
        };
    }

    /** 返回按优先级排好的候选数据集名（可能为空） */
    public synchronized List<String> route(int z, long x, long y) {
        if (cacheSize == 0) {
            return compute(z, x, y);
        }
        String key = z + "/" + x + "/" + y;
        List<String> cached = routeCache.get(key);
        if (cached != null) {
            return cached;
        }
        List<String> computed = compute(z, x, y);
        routeCache.put(key, computed);
        return computed;
    }

    /** 供调试/前端展示：候选数据集 + 重叠比例 */
    public List<Candidate> explain(int z, long x, long y) {
        double[] tileBox = tileBounds(z, x, y);
        List<Candidate> result = new ArrayList<>();
        for (MbtilesDataset dataset : registry.all()) {
            if (!dataset.coversZoom(z)) {
                continue;
            }
            double ratio = overlapRatio(tileBox, dataset.metadata().bounds());
            if (ratio > 0) {
                result.add(new Candidate(dataset.name(), dataset.label(), ratio, dataset.priority()));
            }
        }
        result.sort(Comparator.comparingDouble(Candidate::coverage).reversed()
                .thenComparingInt(Candidate::priority));
        return result;
    }

    public void clearCache() {
        synchronized (this) {
            routeCache.clear();
        }
    }

    private List<String> compute(int z, long x, long y) {
        double[] tileBox = tileBounds(z, x, y);
        List<MbtilesDataset> candidates = new ArrayList<>();
        for (MbtilesDataset dataset : registry.all()) {
            if (!dataset.coversZoom(z)) {
                continue;
            }
            if (overlapRatio(tileBox, dataset.metadata().bounds()) > 0) {
                candidates.add(dataset);
            }
        }
        // coverage 保留 3 位小数后比较：消除浮点噪音，让"完全包含"的情况走 priority 打破平局
        candidates.sort(Comparator
                .comparingDouble((MbtilesDataset d) -> -round3(overlapRatio(tileBox, d.metadata().bounds())))
                .thenComparingInt(MbtilesDataset::priority));
        List<String> names = new ArrayList<>(candidates.size());
        for (MbtilesDataset dataset : candidates) {
            names.add(dataset.name());
        }
        return names;
    }

    /** 瓦片(z/x/y, XYZ) 的经纬度范围 [minLon, minLat, maxLon, maxLat] */
    public static double[] tileBounds(int z, long x, long y) {
        double n = Math.pow(2, z);
        double minLon = x / n * 360.0 - 180.0;
        double maxLon = (x + 1) / n * 360.0 - 180.0;
        double minLat = latitudeOf(y + 1, n);
        double maxLat = latitudeOf(y, n);
        return new double[]{minLon, minLat, maxLon, maxLat};
    }

    private static double latitudeOf(double y, double n) {
        return Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * y / n))));
    }

    /** 瓦片范围被数据集 bounds 覆盖的比例（0 表示不相交） */
    public static double overlapRatio(double[] tile, double[] bounds) {
        if (bounds == null || bounds.length < 4) {
            return 0;
        }
        double minLon = Math.max(tile[0], bounds[0]);
        double maxLon = Math.min(tile[2], bounds[2]);
        double minLat = Math.max(tile[1], bounds[1]);
        double maxLat = Math.min(tile[3], bounds[3]);
        if (maxLon <= minLon || maxLat <= minLat) {
            return 0;
        }
        double intersection = (maxLon - minLon) * (maxLat - minLat);
        double tileArea = Math.max(1e-12, (tile[2] - tile[0]) * (tile[3] - tile[1]));
        return intersection / tileArea;
    }

    private static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    /** 路由候选（调试用） */
    public record Candidate(String name, String label, double coverage, int priority) {
    }
}
