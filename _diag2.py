"""诊断2：低 zoom 台湾瓦片归属、水体图层 class、是否存在海岸线数据。"""
import gzip
import math
import sqlite3

BASE = "D:/AI/map_mbtiles"
china = sqlite3.connect("file:%s/china-261001.mbtiles?mode=ro" % BASE, uri=True)
taiwan = sqlite3.connect("file:%s/taiwan-261001.mbtiles?mode=ro" % BASE, uri=True)
WORDS = [b"ocean", b"lake", b"river", b"sea", b"coastline", b"reservoir", b"pond", b"water"]
CLASSES = [b"wood", b"grass", b"farmland", b"sand", b"ice", b"residential", b"national_park"]
TAIWAN_BOUNDS = (118.103600, 20.727990, 122.931200, 26.603050)
CHINA_BOUNDS = (73.417880, 14.274370, 134.803619, 53.655590)


def tile_bounds(z, x, y):
    n = 2 ** z
    lat = lambda yy: math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * yy / n))))
    return (x / n * 360 - 180, lat(y + 1), (x + 1) / n * 360 - 180, lat(y))


def overlap(t, b):
    x1, x2 = max(t[0], b[0]), min(t[2], b[2])
    y1, y2 = max(t[1], b[1]), min(t[3], b[3])
    if x2 <= x1 or y2 <= y1:
        return 0.0
    return (x2 - x1) * (y2 - y1) / max(1e-12, (t[2] - t[0]) * (t[3] - t[1]))


def tile(db, z, x, y):
    tms = (2 ** z - 1) - y
    row = db.execute("select tile_data from tiles where zoom_level=? and tile_column=? and tile_row=?",
                     (z, x, tms)).fetchone()
    if not row:
        return None, None
    raw = row[0]
    try:
        data = gzip.decompress(raw)
    except Exception:
        data = raw
    return raw, data


def lonlat_to_tile(lon, lat, z):
    n = 2 ** z
    x = int((lon + 180.0) / 360.0 * n)
    r = math.radians(lat)
    y = int((1.0 - math.log(math.tan(r) + 1 / math.cos(r)) / math.pi) / 2.0 * n)
    return x, y


print("=" * 78)
print("1) 台湾区域低 zoom：路由会选谁？两库实际内容")
for z in range(2, 9):
    x, y = lonlat_to_tile(121.5, 23.8, z)
    box = tile_bounds(z, x, y)
    ct, tt = overlap(box, CHINA_BOUNDS), overlap(box, TAIWAN_BOUNDS)
    order = sorted([(round(tt, 4), 5, "taiwan"), (round(ct, 4), 10, "china")],
                   key=lambda c: (-c[0], c[1]))
    rc, dc = tile(china, z, x, y)
    rt, dt = tile(taiwan, z, x, y)
    fmt = lambda raw, data: ("缺失" if raw is None else ("空瓦片(%dB)" % len(raw) if len(data or b"") == 0
                                                         else "%dB->%dB" % (len(raw), len(data))))
    print("z=%d %d/%d/%d cov china=%.3f taiwan=%.3f | 顺序 %s | china:%-16s taiwan:%s"
          % (z, z, x, y, ct, tt, " > ".join(o[2] for o in order), fmt(rc, dc), fmt(rt, dt)))
    if dt:
        hit = [w.decode() for w in WORDS + CLASSES if w in dt]
        print("      taiwan 含: %s" % ",".join(sorted(set(hit))))

print("=" * 78)
print("2) 沿海瓦片中 water 图层出现的 class 值")
for label, lon, lat, z in [("上海沿海", 121.9, 31.3, 8), ("福建沿海", 119.6, 25.4, 10),
                           ("南海", 114.0, 15.0, 6), ("渤海", 119.5, 38.5, 8),
                           ("台湾海峡", 119.8, 24.0, 8), ("高雄外海", 120.0, 22.3, 8)]:
    x, y = lonlat_to_tile(lon, lat, z)
    for dbname, db in (("china", china), ("taiwan", taiwan)):
        raw, data = tile(db, z, x, y)
        if data is None:
            print("%-8s z%d %-7s 缺失" % (label, z, dbname))
            continue
        if len(data) == 0:
            print("%-8s z%d %-7s 空瓦片" % (label, z, dbname))
            continue
        hit = sorted({w.decode() for w in WORDS if w in data})
        print("%-8s z%d %-7s %7dB->%7dB  water相关: %s" % (label, z, dbname, len(raw), len(data),
                                                           ",".join(hit) or "无"))
