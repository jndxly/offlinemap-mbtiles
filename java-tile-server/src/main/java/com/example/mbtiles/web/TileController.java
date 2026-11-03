package com.example.mbtiles.web;

import com.example.mbtiles.config.MbtilesProperties;
import com.example.mbtiles.service.BoundaryData;
import com.example.mbtiles.service.MbtilesDataset;
import com.example.mbtiles.service.MbtilesMetadata;
import com.example.mbtiles.service.MbtilesRegistry;
import com.example.mbtiles.service.MbtilesService;
import com.example.mbtiles.service.TileData;
import com.example.mbtiles.service.TileRouter;
import com.example.mbtiles.util.Gzip;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * 解析前端(MapLibre)请求，按 z/x/y 自适应选择 .mbtiles 并返回地图数据。
 *
 * <pre>
 * GET /tiles/{z}/{x}/{y}.pbf                  自动路由（按瓦片范围自动选库，必要时回退）
 * GET /datasets/{name}/tiles/{z}/{x}/{y}.pbf  显式指定数据集
 * GET /tile?z=&x=&y=[&dataset=]               兼容查询参数形式
 * GET /tiles.json                             自适应 TileJSON
 * GET /datasets/{name}/tiles.json             指定数据集的 TileJSON
 * GET /datasets.json                          数据集清单（名称/范围/层级/优先级）
 * GET /route?z=&x=&y=                         路由调试：候选数据集与实际命中
 * GET /metadata  GET /health
 * </pre>
 */
@RestController
@CrossOrigin(origins = "*", exposedHeaders = {"X-Mbtiles-Dataset", "X-Mbtiles-Fallback"})
public class TileController {

    private static final String VECTOR_TILE_MIME = "application/vnd.mapbox-vector-tile";
    private static final String HEADER_DATASET = "X-Mbtiles-Dataset";
    private static final String HEADER_FALLBACK = "X-Mbtiles-Fallback";

    private final MbtilesService service;
    private final MbtilesProperties properties;
    private final BoundaryData boundaryData;

    public TileController(MbtilesService service, MbtilesProperties properties, BoundaryData boundaryData) {
        this.service = service;
        this.properties = properties;
        this.boundaryData = boundaryData;
    }

    /** 自动路由瓦片：/tiles/{z}/{x}/{y}.pbf */
    @GetMapping({
            "/tiles/{z:[0-9]+}/{x:[0-9]+}/{y:[0-9]+}.pbf",
            "/tiles/{z:[0-9]+}/{x:[0-9]+}/{y:[0-9]+}"
    })
    public ResponseEntity<byte[]> autoTile(
            @PathVariable int z,
            @PathVariable long x,
            @PathVariable long y,
            @RequestHeader(value = HttpHeaders.ACCEPT_ENCODING, required = false) String acceptEncoding,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) throws IOException {
        return writeTile(null, z, x, y, acceptEncoding, ifNoneMatch);
    }

    /** 显式指定数据集：/datasets/{dataset}/tiles/{z}/{x}/{y}.pbf */
    @GetMapping({
            "/datasets/{dataset}/tiles/{z:[0-9]+}/{x:[0-9]+}/{y:[0-9]+}.pbf",
            "/datasets/{dataset}/tiles/{z:[0-9]+}/{x:[0-9]+}/{y:[0-9]+}"
    })
    public ResponseEntity<byte[]> datasetTile(
            @PathVariable String dataset,
            @PathVariable int z,
            @PathVariable long x,
            @PathVariable long y,
            @RequestHeader(value = HttpHeaders.ACCEPT_ENCODING, required = false) String acceptEncoding,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) throws IOException {
        return writeTile(dataset, z, x, y, acceptEncoding, ifNoneMatch);
    }

    /** 兼容查询参数：/tile?z=&x=&y=&dataset=（dataset 可省略） */
    @GetMapping("/tile")
    public ResponseEntity<byte[]> tileByParams(
            @RequestParam int z,
            @RequestParam long x,
            @RequestParam long y,
            @RequestParam(required = false) String dataset,
            @RequestHeader(value = HttpHeaders.ACCEPT_ENCODING, required = false) String acceptEncoding,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) throws IOException {
        return writeTile(dataset, z, x, y, acceptEncoding, ifNoneMatch);
    }

    /** 自适应 TileJSON */
    @GetMapping("/tiles.json")
    public Map<String, Object> tileJson(HttpServletRequest request) {
        return buildTileJson(null, baseUrl(request) + "/tiles/{z}/{x}/{y}.pbf", request);
    }

    /** 指定数据集的 TileJSON */
    @GetMapping("/datasets/{dataset}/tiles.json")
    public Map<String, Object> datasetTileJson(@PathVariable String dataset, HttpServletRequest request) {
        return buildTileJson(dataset, baseUrl(request) + "/datasets/" + dataset + "/tiles/{z}/{x}/{y}.pbf", request);
    }

    /** 数据集清单，供前端生成数据源下拉 */
    @GetMapping("/datasets.json")
    public Map<String, Object> datasets(HttpServletRequest request) {
        String base = baseUrl(request);
        List<Map<String, Object>> items = new ArrayList<>();
        for (MbtilesDataset dataset : service.registry().all()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", dataset.name());
            item.put("label", dataset.label());
            item.put("file", dataset.path().getFileName().toString());
            item.put("priority", dataset.priority());
            item.put("bounds", dataset.metadata().bounds());
            item.put("center", dataset.metadata().center());
            item.put("minzoom", dataset.metadata().minzoom());
            item.put("maxzoom", dataset.metadata().maxzoom());
            item.put("tilejson", base + "/datasets/" + dataset.name() + "/tiles.json");
            item.put("tiles", base + "/datasets/" + dataset.name() + "/tiles/{z}/{x}/{y}.pbf");
            items.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("datasets", items);
        result.put("autoTiles", base + "/tiles/{z}/{x}/{y}.pbf");
        result.put("autoTileJson", base + "/tiles.json");
        result.put("overlayDataset", properties.getOverlayDataset());
        result.put("boundaryAvailable", boundaryData.available());
        return result;
    }

    /** 路由调试：查看某个瓦片会命中哪些数据集、最终用了哪个 */
    @GetMapping("/route")
    public Map<String, Object> route(@RequestParam int z, @RequestParam long x, @RequestParam long y) {
        List<TileRouter.Candidate> candidates = service.router().explain(z, x, y);
        String hit = service.findAuto(z, x, y).map(MbtilesService.TileResult::dataset).orElse(null);

        List<Map<String, Object>> candidateList = new ArrayList<>();
        for (TileRouter.Candidate candidate : candidates) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", candidate.name());
            item.put("label", candidate.label());
            item.put("coverage", Math.round(candidate.coverage() * 10000.0) / 10000.0);
            item.put("priority", candidate.priority());
            candidateList.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("z", z);
        result.put("x", x);
        result.put("y", y);
        result.put("tileBounds", TileRouter.tileBounds(z, x, y));
        result.put("candidates", candidateList);
        result.put("dataset", hit);
        return result;
    }

    @GetMapping("/metadata")
    public Map<String, Map<String, String>> metadata() {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        for (MbtilesDataset dataset : service.registry().all()) {
            result.put(dataset.name(), dataset.metadata().values());
        }
        return result;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (MbtilesDataset dataset : service.registry().all()) {
            MbtilesMetadata md = dataset.metadata();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", dataset.name());
            item.put("file", dataset.path().toString());
            item.put("format", md.format());
            item.put("minzoom", md.minzoom());
            item.put("maxzoom", md.maxzoom());
            items.add(item);
        }
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("status", "UP");
        health.put("datasets", items);
        return health;
    }

    private ResponseEntity<byte[]> writeTile(String dataset, int z, long x, long y,
                                             String acceptEncoding, String ifNoneMatch) throws IOException {
        String scope = dataset == null ? "auto" : dataset;
        String etag = "\"" + scope + "/" + z + "/" + x + "/" + y + "\"";
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }

        String source;
        boolean fallback = false;
        TileData tile;
        if (dataset != null) {
            Optional<TileData> found = service.find(dataset, z, x, y);
            if (found.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
            }
            source = dataset;
            tile = found.get();
        } else {
            Optional<MbtilesService.TileResult> found = service.findAuto(z, x, y);
            if (found.isEmpty()) {
                // 没有任何数据集覆盖该瓦片：204，MapLibre 当作空瓦片
                return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
            }
            source = found.get().dataset();
            fallback = found.get().fallback();
            tile = found.get().tile();
        }

        byte[] body = tile.bytes();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf(VECTOR_TILE_MIME));
        headers.setCacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic());
        headers.setETag(etag);
        headers.set(HEADER_DATASET, source);
        if (fallback) {
            headers.set(HEADER_FALLBACK, "true");
        }

        if (tile.gzipped()) {
            if (acceptsGzip(acceptEncoding)) {
                // 已是 gzip：直接透传，浏览器负责解压
                headers.set(HttpHeaders.CONTENT_ENCODING, "gzip");
            } else {
                body = Gzip.gunzip(body);
            }
        }
        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }

    private Map<String, Object> buildTileJson(String dataset, String tilesUrl, HttpServletRequest request) {
        MbtilesRegistry registry = service.registry();
        List<MbtilesDataset> datasets = new ArrayList<>();
        if (dataset != null) {
            MbtilesDataset found = registry.get(dataset);
            if (found == null) {
                throw new DatasetNotFoundException(dataset);
            }
            datasets.add(found);
        } else {
            datasets.addAll(registry.all());
        }

        double[] bounds = null;
        int minzoom = Integer.MAX_VALUE;
        int maxzoom = 0;
        String name = null;
        String format = "pbf";
        for (MbtilesDataset ds : datasets) {
            MbtilesMetadata md = ds.metadata();
            bounds = bounds == null ? md.bounds() : unionBounds(bounds, md.bounds());
            minzoom = Math.min(minzoom, md.minzoom());
            maxzoom = Math.max(maxzoom, md.maxzoom());
            name = name == null ? md.name() : name;
            format = md.format();
        }
        int centerZoom = minzoom;
        for (MbtilesDataset ds : datasets) {
            double[] c = ds.metadata().center();
            if (c.length == 3) {
                centerZoom = Math.max(centerZoom, (int) c[2]);
            }
        }
        double[] center = new double[]{
                (bounds[0] + bounds[2]) / 2, (bounds[1] + bounds[3]) / 2, centerZoom
        };

        Map<String, Object> tileJson = new LinkedHashMap<>();
        tileJson.put("tilejson", "3.0.0");
        tileJson.put("name", name);
        tileJson.put("format", format);
        tileJson.put("bounds", bounds);
        tileJson.put("center", center);
        tileJson.put("minzoom", minzoom);
        tileJson.put("maxzoom", maxzoom);
        tileJson.put("tiles", List.of(tilesUrl));

        JsonNode layers = mergeVectorLayers(datasets);
        if (layers != null && !layers.isEmpty()) {
            tileJson.put("vector_layers", layers);
        }
        return tileJson;
    }

    private JsonNode mergeVectorLayers(List<MbtilesDataset> datasets) {
        ArrayNode merged = null;
        List<String> seen = new ArrayList<>();
        for (MbtilesDataset dataset : datasets) {
            JsonNode layers = dataset.vectorLayers();
            if (layers == null || !layers.isArray()) {
                continue;
            }
            if (merged == null) {
                merged = (ArrayNode) layers.deepCopy();
                for (JsonNode layer : merged) {
                    seen.add(layer.path("id").asText());
                }
                continue;
            }
            for (JsonNode layer : layers) {
                String id = layer.path("id").asText();
                if (!seen.contains(id)) {
                    merged.add(layer.deepCopy());
                    seen.add(id);
                }
            }
        }
        if (merged != null) {
            return merged;
        }
        return null;
    }

    private static double[] unionBounds(double[] a, double[] b) {
        return new double[]{
                Math.min(a[0], b[0]), Math.min(a[1], b[1]),
                Math.max(a[2], b[2]), Math.max(a[3], b[3])
        };
    }

    private boolean acceptsGzip(String acceptEncoding) {
        return acceptEncoding != null && acceptEncoding.toLowerCase().contains("gzip");
    }

    private String baseUrl(HttpServletRequest request) {
        return ServletUriComponentsBuilder.fromContextPath(request).build().toUriString();
    }
}
