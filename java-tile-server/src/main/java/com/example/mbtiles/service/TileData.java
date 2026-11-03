package com.example.mbtiles.service;

/**
 * 一条瓦片记录：原始字节 + 是否本身已是 gzip。
 *
 * @param bytes   从 tiles.tile_data 读出的原始字节
 * @param gzipped 字节是否以 gzip 魔数开头
 */
public record TileData(byte[] bytes, boolean gzipped) {
}
