package com.example.mbtiles.service;

import com.example.mbtiles.config.MbtilesProperties;
import com.example.mbtiles.util.Gzip;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 通过 SQL 访问 .mbtiles：支持多数据集，既能"自动选库"也能"显式指定库"。
 *
 * <p>MBTiles 约定：tile_column 是 XYZ 列号，tile_row 是 TMS 行号（从南往北），
 * 所以前端给的 y 需要转换：tmsRow = (2^z - 1) - y。
 */
@Service
public class MbtilesService {

    private static final String SELECT_TILE =
            "SELECT tile_data FROM tiles WHERE zoom_level = ? AND tile_column = ? AND tile_row = ?";

    private final MbtilesRegistry registry;
    private final TileRouter router;
    private final boolean fallbackEnabled;
    private final int minTileBytes;

    public MbtilesService(MbtilesRegistry registry, TileRouter router, MbtilesProperties properties) {
        this.registry = registry;
        this.router = router;
        this.fallbackEnabled = properties.isFallbackEnabled();
        this.minTileBytes = Math.max(0, properties.getMinTileBytes());
    }

    /**
     * 自动路由：按 z/x/y 选出候选数据集，取"数据最完整"的那份。
     *
     * <p>注意：库里存在两种"看起来命中但其实没内容"的瓦片
     * <ul>
     *   <li>gzip 空瓦片（约 23 字节，解压后 0 字节）；</li>
     *   <li>只有路网等极稀疏瓦片（几百字节）。</li>
     * </ul>
     * 以前会直接返回它们，导致台湾等区域看起来"没有地图"；
     * 现在会继续尝试后续候选库并取字节数更大的那份。
     *
     * @return 命中的瓦片及其来源数据集
     */
    public Optional<TileResult> findAuto(int z, long x, long y) {
        List<String> names = router.route(z, x, y);
        TileData best = null;
        String bestName = null;
        boolean fallback = false;

        for (String name : names) {
            Optional<TileData> found = find(name, z, x, y);
            if (found.isPresent()) {
                TileData tile = found.get();
                if (best == null || tile.bytes().length > best.bytes().length) {
                    if (best != null) {
                        fallback = true;          // 换用了更完整的瓦片
                    }
                    best = tile;
                    bestName = name;
                }
                if (best.bytes().length >= minTileBytes) {
                    break;                        // 数据够完整，不再查其它库
                }
            }
            if (!fallbackEnabled) {
                break;                            // 关闭回退时只看首选库
            }
        }
        return best == null ? Optional.empty() : Optional.of(new TileResult(bestName, best, fallback));
    }

    /** 显式指定数据集查询 */
    public Optional<TileData> find(String datasetName, int z, long x, long y) {
        MbtilesDataset dataset = registry.get(datasetName);
        if (dataset == null) {
            return Optional.empty();
        }
        if (!dataset.coversZoom(z)) {
            return Optional.empty();
        }
        long maxIndex = (1L << z) - 1;
        if (x < 0 || y < 0 || x > maxIndex || y > maxIndex) {
            return Optional.empty();
        }
        long tmsRow = maxIndex - y;                     // XYZ -> TMS
        byte[] data = dataset.jdbc().query(SELECT_TILE, rs -> rs.next() ? rs.getBytes(1) : null, z, x, tmsRow);
        if (data == null || data.length == 0) {
            return Optional.empty();
        }
        // gzip 空瓦片（解压后 0 字节，压缩后约 23 字节）视为未命中，
        // 否则会被当成有效数据，导致该区域显示空白且不再回退。
        if (data.length <= 128 && Gzip.isEmptyGzip(data)) {
            return Optional.empty();
        }
        return Optional.of(new TileData(data, Gzip.isGzip(data)));
    }

    public MbtilesRegistry registry() {
        return registry;
    }

    public TileRouter router() {
        return router;
    }

    /** 查询结果 */
    public record TileResult(String dataset, TileData tile, boolean fallback) {
    }
}
