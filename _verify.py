"""验证修复后的选片策略：空瓦片/稀疏瓦片不再被误判为命中。"""
import gzip
import math
import sqlite3

BASE = "D:/AI/map_mbtiles"
DBS = {"china": sqlite3.connect("file:%s/china-261001.mbtiles?mode=ro" % BASE, uri=True),
       "taiwan": sqlite3.connect("file:%s/taiwan-261001.mbtiles?mode=ro" % BASE, uri=True)}
BOUNDS = {"china": (73.417880, 14.274370, 134.803619, 53.655590),
          "taiwan": (118.103600, 20.727990, 122.931200, 26.603050)}
PRIO = {"china": 10, "taiwan": 5}
MIN_TILE_BYTES = 1024


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


def route(z, x, y):
    box = tile_bounds(z, x, y)
    c = [("taiwan", round(overlap(box, BOUNDS["taiwan"]), 3)),
         ("china", round(overlap(box, BOUNDS["china"]), 3))]
    c = [i for i in c if i[1] > 0]
    c.sort(key=lambda i: (-i[1], PRIO[i[0]]))
    return [i[0] for i in c]


def find(name, z, x, y):
    """修复后的 find()：gzip 空瓦片视为未命中"""
    tms = (2 ** z - 1) - y
    row = DBS[name].execute(
        "select tile_data from tiles where zoom_level=? and tile_column=? and tile_row=?",
        (z, x, tms)).fetchone()
    if not row:
        return None
    raw = row[0]
    if raw[:2] == b"\x1f\x8b" and len(raw) <= 128 and len(gzip.decompress(raw)) == 0:
        return None
    return raw


def find_auto(z, x, y):
    best, best_name, fallback = None, None, False
    for name in route(z, x, y):
        raw = find(name, z, x, y)
        if raw is not None:
            if best is None or len(raw) > len(best):
                if best is not None:
                    fallback = True
                best, best_name = raw, name
            if len(best) >= MIN_TILE_BYTES:
                break
    return best_name, (len(best) if best else 0), fallback


def lonlat_to_tile(lon, lat, z):
    n = 2 ** z
    x = int((lon + 180.0) / 360.0 * n)
    r = math.radians(lat)
    y = int((1.0 - math.log(math.tan(r) + 1 / math.cos(r)) / math.pi) / 2.0 * n)
    return x, y


print("%-8s %-14s %-28s %s" % ("地点", "瓦片", "候选顺序", "最终命中"))
cases = []
for label, lon, lat in [("台北", 121.50, 25.05), ("高雄", 120.30, 22.62), ("台中", 120.65, 24.15),
                        ("北京", 116.40, 39.90), ("上海", 121.47, 31.23), ("厦门", 118.10, 24.48)]:
    for z in (3, 4, 5, 6, 7, 8, 10, 12, 14):
        x, y = lonlat_to_tile(lon, lat, z)
        cases.append((label, z, x, y))
for label, z, x, y in cases:
    name, size, fb = find_auto(z, x, y)
    order = " > ".join(route(z, x, y)) or "无"
    hit = "%s(%dB)%s" % (name, size, " 回退" if fb else "") if name else "无数据(204)"
    print("%-8s %-14s %-28s %s" % (label, "%d/%d/%d" % (z, x, y), order, hit))
