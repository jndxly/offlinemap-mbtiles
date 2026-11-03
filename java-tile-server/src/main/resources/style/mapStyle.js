// 离线中国地图样式（OpenMapTiles schema / tilemaker 生成）
// 数据源: {assetsPath}/china-20260930.pmtiles (大陆, zoom 0-14)
//         {assetsPath}/taiwan-261001.pmtiles (台湾, zoom 0-14, 与大陆同一 schema)
// 字体: {assetsPath}/fonts/NotoSansRegular, NotoSansBold (glyph PBF)
// 行政边界: {assetsPath}/standard-2020/*.json (GS(2020)4619 官方标准地图 SHP 转换而来,
//           WGS84, 藏南按传统习惯线、含九段线与台湾; 脚本见
//           scripts/convert-standard-boundary.mjs —— 官方正式数据, 商用合规最稳妥)
//           OSM 的国界在藏南等段与官方画法偏差最大约 150km, 故不使用其 boundary 层)
//           回退: standard-2024/ (DataV GeoJSON, 区划较新但源自高德且为
//           GCJ-02 反算, 授权条款不明, 仅内部使用, 商用前须替换为官方授权数据)

export const TILESET = 'china-20260930.pmtiles';
export const TAIWAN_TILESET = 'taiwan-261001.pmtiles';
export const BOUNDARY_DIR = 'standard-2020';

// 拼接资源路径，去掉多余斜杠，保证 assetsPath 传 '/' 或 '/sub/' 都能正常工作
// 返回值始终以单个 '/' 开头，便于拼接 pmtiles:// 等协议前缀
function joinPath(base, path) {
  const trimmedBase = String(base ?? '/').replace(/\/+$/, '');
  const trimmedPath = String(path).replace(/^\/+/, '');
  return `${trimmedBase}/${trimmedPath}`;
}

export function createSources({ assetsPath = '/', tileset = TILESET, taiwanTileset = TAIWAN_TILESET, boundaryDir = BOUNDARY_DIR } = {}) {
  const attribution = '© OpenStreetMap contributors';
  return {
    mainland: {
      type: 'vector',
      url: `pmtiles://${joinPath(assetsPath, tileset)}`,
      attribution,
    },
    taiwan: {
      type: 'vector',
      url: `pmtiles://${joinPath(assetsPath, taiwanTileset)}`,
      attribution,
    },
    // 官方标准地图 (GS(2020)4619) 边界与行政区划 —— 当前生效
    'std-national': { type: 'geojson', data: joinPath(assetsPath, `${boundaryDir}/national-boundary.json`) },
    'std-province': { type: 'geojson', data: joinPath(assetsPath, `${boundaryDir}/province-boundary.json`) },
    'std-county': { type: 'geojson', data: joinPath(assetsPath, `${boundaryDir}/county-boundary.json`) },
    'std-areas': { type: 'geojson', data: joinPath(assetsPath, `${boundaryDir}/province-areas.json`) },
  };
}

// 基础图层模板（source 为 mainland；无 source 的图层如 background 不参与克隆）
const BASE_LAYERS = [
    {
      id: 'background',
      type: 'background',
      paint: { 'background-color': '#f5f2ec' },
    },

    /* ---------- 官方国土范围填充 (GS(2020)4619 省级行政区) ---------- */
    {
      id: 'official-land',
      type: 'fill',
      source: 'std-areas',
      paint: { 'fill-color': '#f6efdb' }, // 淡暖色区分国土范围 (藏南等区域 OSM 无数据, 也显示为中国版图)
    },

    /* ---------- 土地覆盖 ---------- */
    {
      id: 'landcover-ice',
      type: 'fill',
      source: 'mainland',
      'source-layer': 'landcover',
      filter: ['==', ['get', 'class'], 'ice'],
      paint: { 'fill-color': '#e8f4f8' },
    },
    {
      id: 'landcover-sand',
      type: 'fill',
      source: 'mainland',
      'source-layer': 'landcover',
      filter: ['==', ['get', 'class'], 'sand'],
      paint: { 'fill-color': '#f0e6c8' },
    },
    {
      id: 'landcover-farmland',
      type: 'fill',
      source: 'mainland',
      'source-layer': 'landcover',
      filter: ['==', ['get', 'class'], 'farmland'],
      paint: { 'fill-color': '#edead8' },
    },
    {
      id: 'landcover-wood',
      type: 'fill',
      source: 'mainland',
      'source-layer': 'landcover',
      filter: ['==', ['get', 'class'], 'wood'],
      paint: { 'fill-color': '#c8e6c1' },
    },
    {
      id: 'landcover-grass',
      type: 'fill',
      source: 'mainland',
      'source-layer': 'landcover',
      filter: ['==', ['get', 'class'], 'grass'],
      paint: { 'fill-color': '#d3ebcd' },
    },

    /* ---------- 土地利用 / 公园 ---------- */
    {
      id: 'landuse',
      type: 'fill',
      source: 'mainland',
      'source-layer': 'landuse',
      minzoom: 4,
      filter: ['!=', ['get', 'class'], 'residential'],
      paint: {
        'fill-color': [
          'match',
          ['get', 'class'],
          ['cemetery', 'education', 'healthcare'],
          '#d3ebcd',
          ['industrial', 'quarry', 'brownfield'],
          '#dedbd4',
          '#e5e1d8',
        ],
        'fill-opacity': 0.7,
      },
    },
    {
      id: 'landuse-residential',
      type: 'fill',
      source: 'mainland',
      'source-layer': 'landuse',
      minzoom: 8,
      filter: ['==', ['get', 'class'], 'residential'],
      paint: { 'fill-color': '#e2ded5', 'fill-opacity': 0.6 },
    },
    {
      id: 'park',
      type: 'fill',
      source: 'mainland',
      'source-layer': 'park',
      minzoom: 5,
      paint: {
        'fill-color': [
          'match',
          ['get', 'class'],
          ['national_park', 'nature_reserve', 'protected_area'],
          '#bfe0b8',
          '#cfe8c6',
        ],
        'fill-opacity': 0.8,
      },
    },

    /* ---------- 水系 ---------- */
    {
      id: 'water',
      type: 'fill',
      source: 'mainland',
      'source-layer': 'water',
      minzoom: 6,
      filter: ['!=', ['get', 'intermittent'], 'yes'],
      paint: { 'fill-color': '#a4cced' },
    },
    {
      id: 'waterway',
      type: 'line',
      source: 'mainland',
      'source-layer': 'waterway',
      minzoom: 8,
      paint: {
        'line-color': '#a4cced',
        'line-width': [
          'interpolate',
          ['exponential', 1.6],
          ['zoom'],
          8,
          ['match', ['get', 'class'], 'river', 1.2, 0.6],
          14,
          ['match', ['get', 'class'], 'river', 4, 2],
        ],
      },
    },

    /* ---------- 机场 ---------- */
    {
      id: 'aeroway-apron',
      type: 'fill',
      source: 'mainland',
      'source-layer': 'aeroway',
      minzoom: 11,
      filter: ['==', ['get', 'class'], 'apron'],
      paint: { 'fill-color': '#d7d2cc' },
    },

    /* ---------- 建筑（3D 挤出） ---------- */
    {
      id: 'building-3d',
      type: 'fill-extrusion',
      source: 'mainland',
      'source-layer': 'building',
      minzoom: 13,
      paint: {
        'fill-extrusion-color': '#e0d9cf',
        'fill-extrusion-height': ['coalesce', ['get', 'render_height'], 6],
        'fill-extrusion-base': ['coalesce', ['get', 'render_min_height'], 0],
        'fill-extrusion-opacity': 0.9,
      },
    },

    /* ---------- 行政边界 (GS(2020)4619 官方标准地图, 替代 OSM boundary 层) ---------- */
    {
      id: 'boundary-county',
      type: 'line',
      source: 'std-county',
      minzoom: 9,
      paint: {
        'line-color': '#cfcac1',
        'line-width': ['interpolate', ['linear'], ['zoom'], 9, 0.4, 14, 1],
      },
    },
    {
      id: 'boundary-province',
      type: 'line',
      source: 'std-province',
      paint: {
        'line-color': '#b8b2a8',
        'line-width': ['interpolate', ['linear'], ['zoom'], 5, 0.5, 12, 1.2],
        'line-dasharray': [3, 2],
      },
    },
    {
      id: 'boundary-national',
      type: 'line',
      source: 'std-national',
      paint: {
        'line-color': '#8a8278',
        'line-width': ['interpolate', ['linear'], ['zoom'], 3, 0.9, 8, 1.5, 14, 2.2],
      },
    },

    /* ---------- 铁路 ---------- */
    {
      id: 'rail',
      type: 'line',
      source: 'mainland',
      'source-layer': 'transportation',
      minzoom: 10,
      filter: ['in', ['get', 'class'], ['literal', ['rail', 'transit']]],
      paint: {
        'line-color': '#b3afa9',
        'line-width': ['interpolate', ['linear'], ['zoom'], 10, 0.6, 14, 2, 16, 3],
      },
    },

    /* ---------- 道路：小路（casing + fill） ---------- */
    {
      id: 'road-minor-casing',
      type: 'line',
      source: 'mainland',
      'source-layer': 'transportation',
      minzoom: 13,
      filter: ['in', ['get', 'class'], ['literal', ['minor', 'service']]],
      paint: {
        'line-color': '#c8c3ba',
        'line-width': ['interpolate', ['linear'], ['zoom'], 13, 1, 16, 5, 18, 12],
      },
    },
    {
      id: 'road-minor',
      type: 'line',
      source: 'mainland',
      'source-layer': 'transportation',
      minzoom: 13,
      filter: ['in', ['get', 'class'], ['literal', ['minor', 'service']]],
      paint: {
        'line-color': '#ffffff',
        'line-width': ['interpolate', ['linear'], ['zoom'], 13, 0.5, 16, 4, 18, 11],
      },
    },

    /* ---------- 道路：主干道（casing + fill，按等级配色） ---------- */
    {
      id: 'road-major-casing',
      type: 'line',
      source: 'mainland',
      'source-layer': 'transportation',
      minzoom: 5,
      filter: [
        'in',
        ['get', 'class'],
        ['literal', ['motorway', 'trunk', 'primary', 'secondary', 'tertiary']],
      ],
      paint: {
        'line-color': [
          'match',
          ['get', 'class'],
          ['motorway', 'trunk'],
          '#e2a684',
          ['primary', 'secondary'],
          '#d8b98d',
          '#c8c3ba',
        ],
        'line-width': [
          'interpolate',
          ['linear'],
          ['zoom'],
          5,
          ['match', ['get', 'class'], 'motorway', 1.2, 0],
          8,
          ['match', ['get', 'class'], 'motorway', 2, 'primary', 1.2, 0],
          10,
          ['match', ['get', 'class'], 'motorway', 3.5, 'trunk', 2.5, 'primary', 2.2, 'secondary', 1.8, 0],
          12,
          ['match', ['get', 'class'], 'motorway', 5, 'trunk', 4, 'primary', 3.5, 'secondary', 3, 'tertiary', 2.5, 2.5],
          14,
          ['match', ['get', 'class'], 'motorway', 8, 'trunk', 7, 'primary', 6, 'secondary', 5, 'tertiary', 4, 4],
          16,
          12,
          18,
          22,
        ],
      },
    },
    {
      id: 'road-major',
      type: 'line',
      source: 'mainland',
      'source-layer': 'transportation',
      minzoom: 5,
      filter: [
        'in',
        ['get', 'class'],
        ['literal', ['motorway', 'trunk', 'primary', 'secondary', 'tertiary']],
      ],
      paint: {
        'line-color': [
          'match',
          ['get', 'class'],
          'motorway',
          '#f6b28c',
          'trunk',
          '#fbd9a2',
          'primary',
          '#fde8b8',
          ['secondary', 'tertiary'],
          '#ffffff',
          '#ffffff',
        ],
        'line-width': [
          'interpolate',
          ['linear'],
          ['zoom'],
          5,
          ['match', ['get', 'class'], 'motorway', 0.6, 0],
          8,
          ['match', ['get', 'class'], 'motorway', 1.4, 'primary', 0.8, 0],
          10,
          ['match', ['get', 'class'], 'motorway', 2.5, 'trunk', 1.8, 'primary', 1.6, 'secondary', 1.2, 0],
          12,
          ['match', ['get', 'class'], 'motorway', 4, 'trunk', 3.2, 'primary', 2.8, 'secondary', 2.4, 'tertiary', 2, 2],
          14,
          ['match', ['get', 'class'], 'motorway', 7, 'trunk', 6, 'primary', 5, 'secondary', 4, 'tertiary', 3.2, 3.2],
          16,
          10,
          18,
          20,
        ],
      },
    },

    /* ---------- 机场跑道 ---------- */
    {
      id: 'aeroway-runway',
      type: 'line',
      source: 'mainland',
      'source-layer': 'aeroway',
      minzoom: 11,
      filter: ['in', ['get', 'class'], ['literal', ['runway', 'taxiway']]],
      paint: {
        'line-color': '#a8a29a',
        'line-width': ['interpolate', ['linear'], ['zoom'], 11, 1, 14, 4, 16, 8],
      },
    },

    /* ---------- 水域名 ---------- */
    {
      id: 'water-name',
      type: 'symbol',
      source: 'mainland',
      'source-layer': 'water_name',
      minzoom: 13,
      layout: {
        'text-field': ['get', 'name:latin'],
        'text-font': ['NotoSansRegular'],
        'text-size': 11,
        'text-letter-spacing': 0.15,
      },
      paint: {
        'text-color': '#3a6ea5',
        'text-halo-color': '#ffffff',
        'text-halo-width': 1.2,
      },
    },

    /* ---------- 地名（place） ---------- */
    {
      id: 'place-state',
      type: 'symbol',
      source: 'mainland',
      'source-layer': 'place',
      minzoom: 4,
      filter: ['==', ['get', 'class'], 'state'],
      layout: {
        'text-field': ['get', 'name:latin'],
        'text-font': ['NotoSansRegular'],
        'text-size': ['interpolate', ['linear'], ['zoom'], 4, 11, 10, 14],
        'text-letter-spacing': 0.15,
      },
      paint: {
        'text-color': '#7d7568',
        'text-halo-color': '#f5f2ec',
        'text-halo-width': 1.5,
      },
    },
    {
      id: 'place-city',
      type: 'symbol',
      source: 'mainland',
      'source-layer': 'place',
      minzoom: 5,
      filter: ['==', ['get', 'class'], 'city'],
      layout: {
        'text-field': ['get', 'name:latin'],
        'text-font': ['NotoSansBold'],
        'text-size': ['interpolate', ['linear'], ['zoom'], 5, 11, 10, 15, 14, 19],
        'text-anchor': 'bottom',
        'text-offset': [0, 0.15],
      },
      paint: {
        'text-color': '#2b2620',
        'text-halo-color': '#f5f2ec',
        'text-halo-width': 1.6,
      },
    },
    {
      id: 'place-town',
      type: 'symbol',
      source: 'mainland',
      'source-layer': 'place',
      minzoom: 9,
      filter: ['==', ['get', 'class'], 'town'],
      layout: {
        'text-field': ['get', 'name:latin'],
        'text-font': ['NotoSansRegular'],
        'text-size': ['interpolate', ['linear'], ['zoom'], 9, 11, 14, 15],
        'text-anchor': 'bottom',
        'text-offset': [0, 0.1],
      },
      paint: {
        'text-color': '#3c362e',
        'text-halo-color': '#f5f2ec',
        'text-halo-width': 1.5,
      },
    },
    {
      id: 'place-village',
      type: 'symbol',
      source: 'mainland',
      'source-layer': 'place',
      minzoom: 12,
      filter: ['in', ['get', 'class'], ['literal', ['village', 'suburb']]],
      layout: {
        'text-field': ['get', 'name:latin'],
        'text-font': ['NotoSansRegular'],
        'text-size': ['interpolate', ['linear'], ['zoom'], 12, 10.5, 15, 13],
      },
      paint: {
        'text-color': '#4a443a',
        'text-halo-color': '#f5f2ec',
        'text-halo-width': 1.4,
      },
    },
    {
      id: 'place-neighbourhood',
      type: 'symbol',
      source: 'mainland',
      'source-layer': 'place',
      minzoom: 13,
      filter: ['in', ['get', 'class'], ['literal', ['neighbourhood', 'hamlet', 'isolated_dwelling']]],
      layout: {
        'text-field': ['get', 'name:latin'],
        'text-font': ['NotoSansRegular'],
        'text-size': ['interpolate', ['linear'], ['zoom'], 13, 10, 16, 12],
      },
      paint: {
        'text-color': '#5a5348',
        'text-halo-color': '#f5f2ec',
        'text-halo-width': 1.2,
      },
    },

    /* ---------- 道路名称 ---------- */
    {
      id: 'road-name',
      type: 'symbol',
      source: 'mainland',
      'source-layer': 'transportation_name',
      minzoom: 13,
      layout: {
        'symbol-placement': 'line',
        'text-field': ['coalesce', ['get', 'name:latin'], ['get', 'ref']],
        'text-font': ['NotoSansRegular'],
        'text-size': ['interpolate', ['linear'], ['zoom'], 13, 9.5, 17, 13],
      },
      paint: {
        'text-color': '#453f37',
        'text-halo-color': '#ffffff',
        'text-halo-width': 1.8,
      },
    },

    /* ---------- POI ---------- */
    {
      id: 'poi-dot',
      type: 'circle',
      source: 'mainland',
      'source-layer': 'poi',
      minzoom: 14,
      paint: {
        'circle-radius': 1.8,
        'circle-color': '#6f675c',
      },
    },
    {
      id: 'poi-label',
      type: 'symbol',
      source: 'mainland',
      'source-layer': 'poi',
      minzoom: 14.5,
      layout: {
        'text-field': ['get', 'name:latin'],
        'text-font': ['NotoSansRegular'],
        'text-size': 10.5,
        'text-offset': [0, 0.9],
        'text-anchor': 'top',
        'text-padding': 4,
      },
      paint: {
        'text-color': '#5a5348',
        'text-halo-color': '#ffffff',
        'text-halo-width': 1.3,
      },
    },

    /* ---------- 机场名 / 门牌号 / 山峰 ---------- */
    {
      id: 'aerodrome-label',
      type: 'symbol',
      source: 'mainland',
      'source-layer': 'aerodrome_label',
      minzoom: 11,
      layout: {
        'text-field': ['coalesce', ['get', 'name:latin'], ['get', 'iata']],
        'text-font': ['NotoSansRegular'],
        'text-size': 11,
      },
      paint: {
        'text-color': '#5f6b78',
        'text-halo-color': '#ffffff',
        'text-halo-width': 1.3,
      },
    },
    {
      id: 'housenumber',
      type: 'symbol',
      source: 'mainland',
      'source-layer': 'housenumber',
      minzoom: 14.5,
      layout: {
        'text-field': ['get', 'housenumber'],
        'text-font': ['NotoSansRegular'],
        'text-size': 9,
      },
      paint: { 'text-color': '#8a8378' },
    },
    {
      id: 'mountain-peak',
      type: 'symbol',
      source: 'mainland',
      'source-layer': 'mountain_peak',
      minzoom: 12,
      layout: {
        'text-field': ['concat', ['get', 'name:latin'], '\n', ['get', 'ele'], ' m'],
        'text-font': ['NotoSansRegular'],
        'text-size': 10,
      },
      paint: {
        'text-color': '#6b6459',
        'text-halo-color': '#f5f2ec',
        'text-halo-width': 1.2,
      },
    },
];

// 为台湾数据源克隆一份图层（background 等无 source 的图层自动跳过，避免重复绘制）
function createLayers() {
  const tw = BASE_LAYERS.filter((l) => l.source === 'mainland').map((l) => ({
    ...l,
    id: `${l.id}-tw`,
    source: 'taiwan',
  }));
  return [...BASE_LAYERS, ...tw];
}

/**
 * 构建离线地图样式
 * @param {object} [options]
 * @param {string} [options.assetsPath='/'] 瓦片 / 字体 / 边界数据的根路径
 * @param {string} [options.tileset] 大陆瓦片文件名
 * @param {string} [options.taiwanTileset] 台湾瓦片文件名
 * @param {string} [options.boundaryDir] 官方边界数据目录名
 */
export function createOfflineStyle(options = {}) {
  return {
    version: 8,
    name: 'China Offline (OpenMapTiles)',
    glyphs: `${joinPath(options.assetsPath ?? '/', 'fonts/{fontstack}/{range}.pbf')}`,
    sources: createSources(options),
    layers: createLayers(),
  };
}

export const offlineStyle = createOfflineStyle();
