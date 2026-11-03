"""诊断：两库 schema 是否一致、台湾区域瓦片实际内容、是否存在海岸线/海洋图层。"""
import gzip
import math
import re
import sqlite3

BASE = "D:/AI/map_mbtiles"
china = sqlite3.connect("file:%s/china-261001.mbtiles?mode=ro" % BASE, uri=True)
taiwan = sqlite3.connect("file:%s/taiwan-261001.mbtiles?mode=ro" % BASE, uri=True)
KNOWN = ["place", "boundary", "poi", "housenumber", "waterway", "transportation", "transportation_name",
         "building", "water", "water_name", "aeroway", "aerodrome_label", "park", "landuse", "landcover",
         "mountain_peak", "coastline"]

print("=" * 70)
print("1) metadata 对比")
for name, db in (("china", china), ("taiwan", taiwan)):
    md = dict(db.execute("select name, value from metadata").fetchall())
    print("\n[%s] name=%s type=%s format=%s compression=%s zoom=%s~%s"
          % (name, md.get("name"), md.get("type"), md.get("format"), md.get("compression"),
             md.get("minzoom"), md.get("maxzoom")))
    print("   bounds=%s center=%s" % (md.get("bounds"), md.get("center")))
    j = md.get("json", "{}")
    ids = re.findall(r'"id"\s*:\s*"([^"]+)"', j)
    print("   vector_layers(%d): %s" % (len(ids), ids))

print("=" * 70)
print("2) 瓦片抽样（z/x/y | 库: 原始大小 -> 解压大小 | 瓦片内含图层名）")


def lonlat_to_tile(lon, lat, z):
    n = 2 ** z
    x = int((lon + 180.0) / 360.0 * n)
    r = math.radians(lat)
    y = int((1.0 - math.log(math.tan(r) + 1 / math.cos(r)) / math.pi) / 2.0 * n)
    return x, y


def tile_info(db, z, x, y):
    tms = (2 ** z - 1) - y
    row = db.execute("select tile_data from tiles where zoom_level=? and tile_column=? and tile_row=?",
                     (z, x, tms)).fetchone()
    if not row:
        return None
    raw = row[0]
    try:
        data = gzip.decompress(raw)
    except Exception:
        data = raw
    layers = sorted({k for k in KNOWN if k.encode() in data})
    return len(raw), len(data), layers


cases = [
    ("台北", 121.50, 25.05, [8, 10, 12, 14]),
    ("高雄", 120.30, 22.62, [10, 12]),
    ("台中", 120.65, 24.15, [12]),
    ("上海沿海", 121.90, 31.30, [8, 10]),
    ("福建沿海", 119.60, 25.40, [10]),
    ("南海", 114.00, 15.00, [6, 8]),
    ("北京", 116.40, 39.90, [10]),
]
for label, lon, lat, zooms in cases:
    for z in zooms:
        x, y = lonlat_to_tile(lon, lat, z)
        for dbname, db in (("china", china), ("taiwan", taiwan)):
            info = tile_info(db, z, x, y)
            if info is None:
                print("%-8s %-12s %-7s 缺失" % (label, "%d/%d/%d" % (z, x, y), dbname))
            else:
                raw, dec, layers = info
                print("%-8s %-12s %-7s %8dB -> %8dB  %s"
                      % (label, "%d/%d/%d" % (z, x, y), dbname, raw, dec, ",".join(layers) or "空瓦片"))
