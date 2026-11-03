package com.example.mbtiles.web;

import com.example.mbtiles.config.MbtilesProperties;
import com.example.mbtiles.service.BoundaryData;
import com.example.mbtiles.service.MbtilesDataset;
import com.example.mbtiles.service.MbtilesRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * 返回 MapLibre 需要的 style.json（模板 + 占位符替换 + 官方边界 + 区域库叠加）。
 *
 * <p>占位符：${TILES_URL}、${GLYPHS_URL}、${BOUNDARY_URL}、${DATASET_NAME}
 *
 * <p>两个关键处理：
 * <ol>
 *   <li><b>官方行政边界</b>：存在时使用 official-land（省级面）+ 国界/省界/县界，
 *       并把底色改为海色，海陆分明 —— 这是海岸线、台湾等岛屿能显示的关键；
 *       不存在则移除 std-* 图层，回退 mbtiles 自带的 boundary 图层（避免 404）。</li>
 *   <li><b>区域库叠加</b>：全国库在某些区域（实测台湾）完全没有几何数据，
 *       因此把主源图层克隆一份指向区域库（默认 taiwan）并绘制在上层，
 *       区域库 TileJSON 的 bounds 会让 MapLibre 只请求该范围内的瓦片。</li>
 * </ol>
 */
@RestController
@CrossOrigin(origins = "*")
public class StyleController {

    private static final String TEMPLATE_PATH = "style/openmaptiles-basic.json";
    private static final String MAIN_SOURCE = "mbtiles";
    private static final String OVERLAY_SOURCE = "mbtiles-overlay";

    private static final List<String> OFFICIAL_LAYERS =
            List.of("official-land", "boundary-county", "boundary-province", "boundary-national");
    private static final List<String> OFFICIAL_SOURCES =
            List.of("std-national", "std-province", "std-county", "std-areas");

    /** 有官方陆地面时，底色用海色，陆地由 official-land 填充 */
    private static final String OCEAN_BACKGROUND = "#a9d3ee";
    /** 无官方数据时沿用陆地底色（mbtiles 内没有海洋多边形） */
    private static final String LAND_BACKGROUND = "#f5f2ec";

    private final ObjectMapper objectMapper;
    private final MbtilesProperties properties;
    private final MbtilesRegistry registry;
    private final BoundaryData boundaryData;
    private final String template;

    public StyleController(ObjectMapper objectMapper, MbtilesProperties properties,
                           MbtilesRegistry registry, BoundaryData boundaryData) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.registry = registry;
        this.boundaryData = boundaryData;
        this.template = loadTemplate();
    }

    @GetMapping(value = "/style.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public String style(@RequestParam(value = "dataset", required = false) String dataset,
                        HttpServletRequest request) throws IOException {
        String base = ServletUriComponentsBuilder.fromContextPath(request).build().toUriString();
        String tilesJson = dataset == null || dataset.isBlank()
                ? base + "/tiles.json"
                : base + "/datasets/" + dataset + "/tiles.json";
        String title = dataset == null || dataset.isBlank()
                ? "自适应路由（auto）"
                : "数据集 " + dataset;

        String filled = template
                .replace("${BASE_URL}", base)
                .replace("${GLYPHS_URL}", base + "/fonts/{fontstack}/{range}.pbf")
                .replace("${TILES_URL}", tilesJson)
                .replace("${BOUNDARY_URL}", base + "/boundary")
                .replace("${DATASET_NAME}", "中国矢量地图 · " + title);

        JsonNode root = objectMapper.readTree(filled);
        boolean official = boundaryData.available();
        applyBoundary(root, official);
        applyOverlay(root, dataset, base);
        return objectMapper.writeValueAsString(root);
    }

    private void applyBoundary(JsonNode root, boolean official) {
        ArrayNode layers = (ArrayNode) root.get("layers");
        if (layers == null) {
            return;
        }
        List<String> drop = official ? List.of("boundary-osm") : OFFICIAL_LAYERS;
        ArrayNode kept = objectMapper.createArrayNode();
        for (JsonNode layer : layers) {
            if (!drop.contains(layer.path("id").asText(""))) {
                kept.add(layer);
            }
        }
        ObjectNode sources = (ObjectNode) root.get("sources");
        if (!official && sources != null) {
            for (String name : OFFICIAL_SOURCES) {
                sources.remove(name);
            }
        }
        // 有官方陆地面时把底色换成海色：海陆分明，海岸线自然呈现
        String background = official ? OCEAN_BACKGROUND : LAND_BACKGROUND;
        for (JsonNode layer : kept) {
            if ("background".equals(layer.path("id").asText()) && layer.path("paint").isObject()) {
                ((ObjectNode) layer.path("paint")).put("background-color", background);
            }
        }
        layers.removeAll();
        layers.addAll(kept);
    }

    /** 把主源图层克隆一份指向区域库（如 taiwan），补齐全国库缺失的区域 */
    private void applyOverlay(JsonNode root, String dataset, String base) {
        // 仅自适应模式（未显式指定数据集）才叠加；"仅用 某库"时不再叠加
        if (dataset != null && !dataset.isBlank()) {
            return;
        }
        String overlay = properties.getOverlayDataset();
        if (overlay == null || overlay.isBlank()) {
            return;
        }
        MbtilesDataset overlayDataset = registry.get(overlay);
        if (overlayDataset == null) {
            return;
        }
        ObjectNode sources = (ObjectNode) root.get("sources");
        ArrayNode layers = (ArrayNode) root.get("layers");
        if (sources == null || layers == null) {
            return;
        }
        ObjectNode source = objectMapper.createObjectNode();
        source.put("type", "vector");
        source.put("url", base + "/datasets/" + overlay + "/tiles.json");
        sources.set(OVERLAY_SOURCE, source);

        ArrayNode result = objectMapper.createArrayNode();
        for (JsonNode layer : layers) {
            result.add(layer);
            if (MAIN_SOURCE.equals(layer.path("source").asText(""))) {
                ObjectNode clone = (ObjectNode) layer.deepCopy();
                clone.put("id", layer.path("id").asText("") + "-ov");
                clone.put("source", OVERLAY_SOURCE);
                result.add(clone);      // 紧跟原图层之后 -> 绘制在上层，且不破坏图层顺序
            }
        }
        layers.removeAll();
        layers.addAll(result);
    }

    private static String loadTemplate() {
        try (InputStream in = new ClassPathResource(TEMPLATE_PATH).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("无法读取 style 模板: " + TEMPLATE_PATH, e);
        }
    }
}
