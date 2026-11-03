"""诊断3：解析矢量瓦片，检查 china 低 zoom 瓦片里是否包含台湾岛几何。"""
import gzip
import math
import sqlite3

BASE = "D:/AI/map_mbtiles"
china = sqlite3.connect("file:%s/china-261001.mbtiles?mode=ro" % BASE, uri=True)
taiwan = sqlite3.connect("file:%s/taiwan-261001.mbtiles?mode=ro" % BASE, uri=True)
TW_BOX = (120.0, 21.8, 122.1, 25.5)


def read_varint(buf, i):
    shift = 0
    val = 0
    while True:
        b = buf[i]
        i += 1
        val |= (b & 0x7F) << shift
        if not b & 0x80:
            return val, i
        shift += 7


def fields(buf):
    """简单 protobuf 遍历：yield (field_no, wire_type, payload)"""
    i = 0
    while i < len(buf):
        key, i = read_varint(buf, i)
        fno, wt = key >> 3, key & 7
        if wt == 0:
            v, i = read_varint(buf, i)
            yield fno, wt, v
        elif wt == 2:
            ln, i = read_varint(buf, i)
            yield fno, wt, buf[i:i + ln]
            i += ln
        elif wt == 5:
            yield fno, wt, buf[i:i + 4]
            i += 4
        elif wt == 1:
            yield fno, wt, buf[i:i + 8]
            i += 8
        else:
            break


def zigzag(n):
    return (n >> 1) ^ -(n & 1)


def decode_layers(data):
    """返回 [(layer_name, extent, [(x, y), ...]), ...]"""
    out = []
    for fno, wt, payload in fields(data):
        if fno != 3 or wt != 2:
            continue
        name, extent, feats = None, 4096, []
        for lf, lwt, lp in fields(payload):
            if lf == 15 and lwt == 2:
                name = lp.decode("utf-8", "ignore")
            elif lf == 5 and lwt == 0:
                extent = lp or 4096
            elif lf == 2 and lwt == 2:
                for ff, fwt, fp in fields(lp):
                    if ff == 4 and fwt == 2:
                        feats.append(decode_geom(fp))
        out.append((name, extent, feats))
    return out


def decode_geom(buf):
    i = 0
    x = y = 0
    pts = []
    while i < len(buf):
        cmd, i = read_varint(buf, i)
        cid, count = cmd & 7, cmd >> 3
        if cid == 1 or cid == 2:
            for _ in range(count):
                dx, i = read_varint(buf, i)
                dy, i = read_varint(buf, i)
                x += zigzag(dx)
                y += zigzag(dy)
                pts.append((x, y))
        elif cid == 7:
            pass
        else:
            break
    return pts


def tile_latlon(z, tx, ty, extent, px, py):
    n = 2 ** z
    lon = (tx + px / extent) / n * 360.0 - 180.0
    lat = math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * (ty + py / extent) / n))))
    return lon, lat


def tile_data(db, z, x, y):
    tms = (2 ** z - 1) - y
    row = db.execute("select tile_data from tiles where zoom_level=? and tile_column=? and tile_row=?",
                     (z, x, tms)).fetchone()
    if not row:
        return None
    try:
        return gzip.decompress(row[0])
    except Exception:
        return row[0]


def scan(db, z, x, y, label):
    data = tile_data(db, z, x, y)
    if not data:
        print("  %-7s 缺失" % label)
        return
    if len(data) == 0:
        print("  %-7s 空瓦片" % label)
        return
    total_in = total = 0
    detail = []
    for name, extent, feats in decode_layers(data):
        pts = [p for f in feats for p in f]
        total += len(pts)
        inn = 0
        for px, py in pts:
            lon, lat = tile_latlon(z, x, y, extent, px, py)
            if TW_BOX[0] <= lon <= TW_BOX[2] and TW_BOX[1] <= lat <= TW_BOX[3]:
                inn += 1
        total_in += inn
        if inn:
            detail.append("%s=%d" % (name, inn))
    print("  %-7s %7dB 顶点 %6d，落在台湾岛范围内的顶点 %5d  %s"
          % (label, len(data), total, total_in, ",".join(detail)))


print("台湾岛判别框:", TW_BOX)
for z, x, y in [(3, 6, 3), (4, 13, 6), (5, 26, 13), (6, 53, 27), (7, 107, 55), (8, 214, 110)]:
    print("z=%d (%d/%d/%d)" % (z, z, x, y))
    scan(china, z, x, y, "china")
    scan(taiwan, z, x, y, "taiwan")
