# MBTiles 瓦片服务（Java + MapLibre，多库自适应路由）

用 Java（Spring Boot + SQLite JDBC）读取本地多个 `.mbtiles`，把前端 MapLibre 的瓦片请求解析成 SQL 查询，
并按瓦片所在位置**自动选择应该查询哪个库**。

当前挂载的数据集：

| name | 文件 | bounds | zoom | priority |
| --- | --- | --- | --- | --- |
| `china` | `D:/AI/map_mbtiles/china-261001.mbtiles` | 73.42,14.27,134.80,53.66 | 0~14 | 10 |
| `taiwan` | `D:/AI/map_mbtiles/taiwan-261001.mbtiles` | 118.10,20.73,122.93,26.60 | 0~14 | 5 |

两者均为矢量瓦片（`format=pbf`，OpenMapTiles 图层，`tile_data` 本身为 gzip）。

## 1. 环境要求

- JDK 17+（本机未安装时任选其一）
  - `winget install -e --id Microsoft.OpenJDK.17`
  - 或 `winget install -e --id EclipseAdoptium.Temurin.17.JDK`
- Maven 3.8+：`winget install -e --id Apache.Maven`
- 安装后重开终端，确认 `java -version`、`mvn -version` 可用

## 2. 构建与运行

```powershell
cd D:\AI\map_mbtiles\java-tile-server

mvn spring-boot:run                 # 方式一：直接运行
mvn -DskipTests package             # 方式二：打包
java -jar target/mbtiles-tile-server-0.0.1-SNAPSHOT.jar
```

首次运行需联网下载依赖。启动后打开 <http://localhost:8080/>。

## 3. 自适应路由方案

```
MapLibre 请求 /tiles/{z}/{x}/{y}.pbf
        │
        ├─ 1. 由 z/x/y 反算瓦片经纬度范围 tileBounds
        ├─ 2. 与每个数据集 metadata.bounds 求交，算覆盖率 coverage
        ├─ 3. 排序：coverage 高者优先 → coverage 相同则 priority 小者优先
        ├─ 4. 依次查库：首选库无该瓦片时回退到下一个候选（fallback-enabled）
        └─ 5. 命中返回瓦片字节（响应头 X-Mbtiles-Dataset 标明实际来源）
```

关键类：

| 类 | 职责 |
| --- | --- |
| `config/MbtilesProperties` | 数据集列表（name/label/path/priority）、回退开关、路由缓存 |
| `service/MbtilesRegistry` | 每个 .mbtiles 独立只读 SQLite 连接 + 加载 metadata |
| `service/TileRouter` | 瓦片 bbox ↔ bounds 求交、候选排序、路由 LRU 缓存 |
| `service/MbtilesService` | `findAuto()` 自动路由 + 回退；`find(name,…)` 显式指定 |
| `web/TileController` | 解析请求、返回瓦片 / TileJSON / 数据集清单 / 路由调试 |

实测（用真实数据跑过同样的算法）：

| 位置 | 瓦片 | 候选顺序 | 命中 |
| --- | --- | --- | --- |
| 台北 | 14/13721/7013 | taiwan(1.0,p5) > china(1.0,p10) | **taiwan** |
| 高雄 | 12/3416/1783 | taiwan(1.0,p5) > china(1.0,p10) | **taiwan** |
| 北京 | 14/13489/6208 | china(1.0,p10) | **china** |
| 上海 | 12/3430/1673 | china(1.0,p10) | **china** |
| 厦门 | 12/3391/1760 | china(1.0) > taiwan(0.243) | **china** |
| 全国概览 | 4/13/6 | china(0.991) > taiwan(0.053) | **china** |
| 台湾以东外海 | 14/13758/7101 | taiwan > china | 204（两库均无数据） |

为什么需要回退：在台湾范围内抽样 300 个瓦片，`仅 taiwan 有` 占 27%~36%，`仅 china 有` 约占 13%。
即台湾区域优先用 taiwan 拿更完整的数据，taiwan 缺失时回退 china 兜底，不会漏底图。

## 4. 接口一览

| 接口 | 说明 |
| --- | --- |
| `GET /` | MapLibre 前端页面（含数据源切换） |
| `GET /tiles/{z}/{x}/{y}.pbf` | **自适应路由**瓦片，响应头带 `X-Mbtiles-Dataset` / `X-Mbtiles-Fallback` |
| `GET /datasets/{name}/tiles/{z}/{x}/{y}.pbf` | 显式指定数据集（调试/对比用） |
| `GET /tile?z=&x=&y=[&dataset=]` | 兼容查询参数形式 |
| `GET /tiles.json` | 自适应 TileJSON（bounds/zoom 为各库并集） |
| `GET /datasets/{name}/tiles.json` | 指定数据集的 TileJSON |
| `GET /datasets.json` | 数据集清单（名称、文件、bounds、zoom、priority） |
| `GET /route?z=&x=&y=` | 路由调试：候选、覆盖率、实际命中 |
| `GET /style.json[?dataset=name]` | 前端 style，`dataset` 缺省为自适应 |
| `GET /fonts/{fontstack}/{range}.pbf` | 字形代理（文字标注） |
| `GET /metadata` | 各数据集的 metadata |
| `GET /health` | 健康检查（列出数据集） |

自检命令：

```powershell
curl http://localhost:8080/health
curl http://localhost:8080/datasets.json
curl "http://localhost:8080/route?z=14&x=13721&y=7013"      # 应命中 taiwan
curl "http://localhost:8080/route?z=14&x=13489&y=6208"      # 应命中 china
curl -D - -o t.pbf http://localhost:8080/tiles/14/13721/7013.pbf   # 看响应头 X-Mbtiles-Dataset
```

## 5. 配置（application.yml）

```yaml
mbtiles:
  pool-size: 4                 # 每个数据集的连接池大小
  fallback-enabled: true       # 首选库无瓦片时是否回退其它库
  route-cache-size: 50000      # 路由结果缓存条数
  datasets:
    - name: china
      label: 中国
      path: ${CHINA_MBTILES:D:/AI/map_mbtiles/china-261001.mbtiles}
      priority: 10             # 数值越小越优先
    - name: taiwan
      label: 台湾
      path: ${TAIWAN_MBTILES:D:/AI/map_mbtiles/taiwan-261001.mbtiles}
      priority: 5
```

- 新增数据集：往 `datasets` 加一项即可，前端下拉会自动出现（来自 `/datasets.json`）。
- 只用一个文件：删掉 `datasets`，设置 `mbtiles.file` 即可（自动退化为单数据集）。
- 端口：`server.port`（默认 8080）。

## 6. 其它实现要点

- **只读打开**：`jdbc:sqlite:file:/D:/…/x.mbtiles?mode=ro`，不生成 `-wal/-shm`，不锁源文件；每个库独立 HikariCP 池。
- **坐标转换**：MBTiles 的 `tile_row` 是 TMS 行号，前端 y 需换算：`tmsRow = (2^z - 1) - y`。
- **gzip**：本数据 `tile_data` 已是 gzip；浏览器支持时原样透传并声明 `Content-Encoding: gzip`，否则解压后返回。
- **缓存**：ETag（`scope/z/x/y`）+ `Cache-Control: max-age=7d`，重复请求 304；路由结果走 LRU 缓存。
- **空白瓦片**：任何库都没有该瓦片时返回 204，MapLibre 按空瓦片处理。

## 7. 行政边界与字体（style 已同步 mapStyle.js）

`src/main/resources/style/openmaptiles-basic.json` 的图层与 `style/mapStyle.js` 一一对应（35 个图层，顺序、配色、宽度一致），
只有数据源不同：

| mapStyle.js | 本服务 |
| --- | --- |
| `mainland` + `taiwan` 两个 pmtiles 源并克隆图层 | 主源 `mbtiles`（服务端自动路由）+ 叠加源 `mbtiles-overlay`（默认 taiwan），克隆图层由 `StyleController` 动态生成 |
| `std-national/province/county/areas` → `{assetsPath}/standard-2020/*.json` | → `/boundary/*.json`（classpath `static/boundary/`） |
| `NotoSansRegular` / `NotoSansBold` 本地字体 | → `/fonts/{fontstack}/{range}.pbf`：先查 classpath `static/fonts/`，再代理上游 |

放置官方标准地图边界（GS(2020)4619 转换结果，四个文件同名）：

```
src/main/resources/static/boundary/
├── national-boundary.json
├── province-boundary.json
├── county-boundary.json
└── province-areas.json
```

- **存在**：启用 `official-land` 国土填充 + `boundary-national/province/county` 官方边界，不使用 OSM boundary 层；
  同时底色自动改为海色 `#a9d3ee`，陆地由官方面填充 —— **海陆分明，海岸线因此可见**（mbtiles 内没有海洋多边形）；
- **缺失**：`StyleController` 自动移除 std-* 图层与数据源，回退到 mbtiles 自带的 `boundary` 图层（id `boundary-osm`），不会 404。
  **代价**：没有海洋面，海岸线/岛屿无法显示（见第 8 节）。

不想复制大文件时，可直接指向已有目录（环境变量或 yml）：

```powershell
$env:BOUNDARY_DIR = "D:\AI\map_mbtiles\standard-2020"; mvn spring-boot:run
```

字体（可选，离线建议放置）：

```
src/main/resources/static/fonts/NotoSansRegular/0-255.pbf ...
src/main/resources/static/fonts/NotoSansBold/0-255.pbf ...
```

未放置时由 `/fonts/**` 代理 `https://fonts.openmaptiles.org`；style 中每个 `text-font` 都写了两个字体栈
（`["NotoSansRegular", "Noto Sans Regular"]`），命中其一即可显示中文标注。

## 8. 台湾与海岸线：根因与修复（实测结论）

**实测数据**（直接查两个 mbtiles，并解析矢量瓦片几何统计顶点）：

| 现象 | 实测结果 |
| --- | --- |
| china 库含台湾岛几何吗 | **不含**：z3~z6 的台湾瓦片有 25 万个顶点，但落在台湾岛范围(120.0~122.1E, 21.8~25.5N)内的顶点数 = **0** |
| china 库 z≥7 台湾瓦片 | 缺失，或仅 101B/229B 的路网，或 23B 的 gzip 空瓦片（解压后 0 字节） |
| taiwan 库 | 台湾区域完整（z8 台北 143KB，含 landcover/water/transportation/place 等） |
| 有海洋/海岸线数据吗 | **没有**：南海 z6 瓦片仅 51B，`water` 图层只有 lake/river/pond，没有 ocean |

**台湾不显示的两个原因**

1. 低 zoom（z2~z6）路由按覆盖率选中 china，而 china 瓦片里根本没有台湾岛；
2. 高 zoom 时 china 只有 23B 空瓦片或 101B 路网瓦片，旧逻辑把"查到记录"当命中，回退没触发，于是台湾区域看着是空白。

**修复**：

1. `MbtilesService`：gzip 空瓦片（解压 0 字节）视为未命中；命中的瓦片小于 `mbtiles.min-tile-bytes`（默认 1024B）时
   继续尝试下一个候选库，取更完整的那份。修复后实测：台北/高雄/台中 z≥7 全部命中 taiwan 库。
2. `StyleController` 区域库叠加：把主源图层克隆一份指向 `mbtiles-overlay`（默认 taiwan，配置 `mbtiles.overlay-dataset`），
   克隆层紧跟原图层之后 —— 绘制在上层且不破坏图层顺序，低 zoom 由 taiwan 补齐台湾岛。
   taiwan 的 TileJSON bounds 会让 MapLibre 只请求台湾范围内的瓦片，额外开销很小。

**海岸线**：mbtiles 里没有海洋面，必须靠官方标准地图数据。放入 `province-areas.json`（省级面）+ `national-boundary.json`（国界/海岸线）后，
`StyleController` 自动把底色改为海色 `#a9d3ee`、陆地由官方面填充 → 海陆分明，海岸线自然呈现；
数据缺失时前端面板会显示"官方边界：缺失"。

## 9. 常见问题

1. **`Failed to configure a DataSource: 'url' attribute is not specified`**
   数据源由 `MbtilesRegistry` 自己创建（`jdbc:sqlite:file:/…?mode=ro`），不是 Spring Bean，
   因此已在启动类排除 `DataSourceAutoConfiguration` / `DataSourceTransactionManagerAutoConfiguration`。
   若自行改造代码时引入 `DataSource` Bean，则不要再排除这两个自动配置。
2. **地图能出图形没文字**：文字依赖 glyphs。优先读 `src/main/resources/static/fonts/<fontstack>/`（如 `NotoSansRegular`），
   没有则代理 `https://fonts.openmaptiles.org`（字体栈 `Noto Sans Regular`）。离线部署请把字体 PBF 放到本地目录。
3. **想确认某个瓦片来自哪个库**：访问 `/route?z=&x=&y=`，或看瓦片响应头 `X-Mbtiles-Dataset`；`X-Mbtiles-Fallback: true` 表示首选库缺数据、已回退。
4. **台湾区域想强制用全国库**：前端下拉选"仅用 中国"，或直接访问 `/datasets/china/tiles.json`（此时不会叠加 taiwan 层）。
5. **改样式**：编辑 `src/main/resources/style/openmaptiles-basic.json`（`${TILES_URL}`、`${GLYPHS_URL}`、`${DATASET_NAME}` 运行时替换）。
