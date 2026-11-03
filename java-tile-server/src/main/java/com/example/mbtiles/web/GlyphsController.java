package com.example.mbtiles.web;

import com.example.mbtiles.config.MbtilesProperties;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Set;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 字形(glyphs)：MapLibre 渲染文字标注时会请求 /fonts/{fontstack}/{range}.pbf。
 *
 * <p>查找顺序：
 * <ol>
 *   <li>本地字体目录 classpath:/style/fonts/{fontstack}/{range}.pbf（离线推荐，如 NotoSansRegular/NotoSansBold），
 *       目录可通过 mbtiles.glyphs.local-dirs 配置；</li>
 *   <li>上游字体服务（默认 https://fonts.openmaptiles.org，字体栈为 "Noto Sans Regular" 等）；</li>
 * </ol>
 * 都没有则返回 404（此时文字不显示，图形正常）。
 */
@RestController
@CrossOrigin(origins = "*")
public class GlyphsController {

    private static final Logger log = LoggerFactory.getLogger(GlyphsController.class);

    private final MbtilesProperties properties;
    private final ResourceLoader resourceLoader;
    private final HttpClient httpClient;

    public GlyphsController(MbtilesProperties properties, ResourceLoader resourceLoader) {
        this.properties = properties;
        this.resourceLoader = resourceLoader;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    // 注意：fontstack 是逗号连接的字体栈（如 "NotoSansRegular,NotoSansRegular"），
    // 必须允许逗号，否则 Spring 路由匹配不上直接 404。
    @GetMapping("/fonts/{fontstack:[A-Za-z0-9_,\\- ]+}/{range:[0-9\\-]+}.pbf")
    public ResponseEntity<byte[]> glyphs(@PathVariable String fontstack, @PathVariable String range) {
        // 1) 本地字体（离线部署时把字体 PBF 放到 src/main/resources/style/fonts/<fontstack>/）
        byte[] local = loadLocal(fontstack, range);
        if (local != null) {
            return ResponseEntity.ok()
                    .contentType(MediaType.valueOf("application/x-protobuf"))
                    .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic())
                    .body(local);
        }

        // 2) 代理上游字体服务
        if (!properties.getGlyphs().isProxyEnabled()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        URI uri = URI.create(properties.getGlyphs().getUpstream() + "/" + fontstack + "/" + range + ".pbf");
        try {
            HttpResponse<byte[]> response = httpClient.send(
                    HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() != 200) {
                log.debug("glyphs 未命中: {} -> {}", uri, response.statusCode());
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }

            String upstreamEncoding = response.headers()
                    .firstValue(HttpHeaders.CONTENT_ENCODING)
                    .orElse("")
                    .toLowerCase();
            ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                    .contentType(MediaType.valueOf("application/x-protobuf"))
                    .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic());
            if (upstreamEncoding.contains("gzip")) {
                builder.header(HttpHeaders.CONTENT_ENCODING, "gzip");
            }
            return builder.body(response.body());
        } catch (IOException e) {
            log.warn("请求上游字体失败: {} ({})", uri, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).build();
        }
    }

    /** 在本地字体目录里查找，命中返回字节，未命中返回 null */
    private byte[] loadLocal(String fontstack, String range) {
        Set<String> names = localCandidates(fontstack);
        for (String dir : properties.getGlyphs().getLocalDirs()) {
            if (dir == null || dir.isBlank()) {
                continue;
            }
            String prefix = dir.endsWith("/") ? dir : dir + "/";
            for (String name : names) {
                Resource resource = resourceLoader.getResource(prefix + name + "/" + range + ".pbf");
                if (!resource.exists()) {
                    continue;
                }
                try (InputStream in = resource.getInputStream()) {
                    return in.readAllBytes();
                } catch (IOException e) {
                    log.warn("读取本地字体失败: {} ({})", name, e.getMessage());
                }
            }
        }
        return null;
    }

    /**
     * 字体栈候选名：MapLibre 请求的是逗号连接的字体栈，
     * 因此除整体外还要逐个字体名尝试；再补一个去掉空格的写法
     * （如 "Noto Sans Bold" -> "NotoSansBold"）。
     */
    private static Set<String> localCandidates(String fontstack) {
        Set<String> names = new LinkedHashSet<>();
        String stack = fontstack == null ? "" : fontstack.trim();
        if (!stack.isEmpty()) {
            names.add(stack);
            names.add(stack.replaceAll("\\s+", ""));
        }
        for (String part : stack.split(",")) {
            String name = part.trim();
            if (!name.isEmpty()) {
                names.add(name);
                names.add(name.replaceAll("\\s+", ""));
            }
        }
        return names;
    }
}
