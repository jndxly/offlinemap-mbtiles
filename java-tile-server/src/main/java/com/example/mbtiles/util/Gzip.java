package com.example.mbtiles.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * gzip 探测与解压/压缩工具。
 * MBTiles 中的矢量瓦片通常已经是 gzip 压缩的（部分工具还会用 deflate 分块），
 * 此时可以直接把原始字节交给浏览器并声明 Content-Encoding: gzip。
 */
public final class Gzip {

    private Gzip() {
    }

    public static boolean isGzip(byte[] data) {
        return data != null && data.length > 2
                && (data[0] & 0xff) == 0x1f
                && (data[1] & 0xff) == 0x8b;
    }

    public static byte[] gunzip(byte[] data) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return in.readAllBytes();
        }
    }

    /**
     * 判断是否为"解压后为空"的 gzip 数据。
     * 字节不是 gzip 或解压失败（数据损坏）时返回 false，由调用方按普通数据继续处理。
     */
    public static boolean isEmptyGzip(byte[] data) {
        if (!isGzip(data)) {
            return false;
        }
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return in.read() < 0;                   // 首字节即 EOF => 解压后 0 字节
        } catch (IOException e) {
            return false;
        }
    }

    public static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length / 2 + 32);
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(data);
        }
        return out.toByteArray();
    }
}
