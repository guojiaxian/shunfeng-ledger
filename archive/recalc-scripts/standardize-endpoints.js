/* 顺风账本 · 起终点写法标准化
 * 输入/输出: shunfeng-ledger-2026-09-04-new-recalc.json（就地更新）
 * 规则: 按人工审核的映射表统一非标准写法；文字变化导致解析路线变化的订单重走高德 API */
const fs = require('fs');
const https = require('https');
const ROOT = '/root/shunfeng-ledger';
const FILE = ROOT + '/shunfeng-ledger-2026-09-04-new-recalc.json';
const REPORT = ROOT + '/里程重算报告-写法标准化.md';

const html = fs.readFileSync(ROOT + '/android-app/app/src/main/assets/index.html', 'utf8');
(0, eval)(html.slice(html.indexOf('/* 运营区域'), html.indexOf('/* 轻量级驾车路线规划')));

let lastStart = 0, reqCount = 0;
global.XMLHttpRequest = class {
  open(m, u){ this.__url = u; }
  send(){
    const wait = Math.max(0, lastStart + 340 - Date.now());
    lastStart = Date.now() + wait;
    reqCount++;
    setTimeout(() => {
      https.get(this.__url, res => {
        let t = '';
        res.on('data', c => t += c);
        res.on('end', () => { this.status = res.statusCode; this.responseText = t; this.onload && this.onload(); });
      }).on('error', e => { this.onerror && this.onerror(e); });
    }, wait);
  }
};

/* 人工审核的写法映射表（只合并错别字/标点/数字写法差异；不同门、不同院区不合并） */
const PICKUP_MAP = {
  '碧桂园西湖名城三号院东门': '碧桂园西流湖名城三号院(东门)',      // 漏「流」字
  '碧桂园西流湖名称三号院东门': '碧桂园西流湖名城三号院(东门)',    // 名称→名城
  '碧桂園西流湖名城三号院(东门)': '碧桂园西流湖名城三号院(东门)',  // 繁体園
  '碧桂园西流湖名城3号院': '碧桂园西流湖名城三号院',              // 数字写法
  '碧桂园西流湖名城3号院-...': '碧桂园西流湖名城三号院',          // 数字写法+OCR截断
  '郑州东站-西广场P10网约车上客区': '郑州东站西广场P10网约车上客区' // 仅标点
};
const DROPOFF_MAP = {
  '河南省肿瘤医院(西门)': '河南省肿瘤医院',                       // 多数派写法（解析结果相同）
  '河南省肿瘤医院西门': '河南省肿瘤医院',
  '郑州东站(北进站口)': '郑州东站北进站口',                       // 仅标点
  '郑州东站-北进站口': '郑州东站北进站口',
  '郑州东站(南进站口)': '郑州东站南进站口'
};

(async () => {
  const data = JSON.parse(fs.readFileSync(FILE, 'utf8'));
  const KEY = data.ocrCfg && data.ocrCfg.amapKey;
  const REGION = (data.ocrCfg && data.ocrCfg.routeRegion) || '河南省郑州市';

  /* 应用映射，收集文字变化的订单 */
  const textChanges = [];
  data.orders.forEach(o => {
    const p = PICKUP_MAP[(o.pickup || '').trim()];
    const d = DROPOFF_MAP[(o.dropoff || '').trim()];
    if(p || d){
      const rec = { id: o.id, date: o.date, platform: o.platform,
        oldPickup: o.pickup, oldDropoff: o.dropoff,
        oldDistance: o.distance, oldDuration: o.duration };
      if(p) o.pickup = p;
      if(d) o.dropoff = d;
      rec.newPickup = o.pickup; rec.newDropoff = o.dropoff;
      textChanges.push(rec);
    }
  });
  console.log('文字标准化订单: ' + textChanges.length);

  /* 重算文字变化的订单 */
  const valueChanged = [], failed = [];
  for(const rec of textChanges){
    const o = data.orders.find(x => x.id === rec.id);
    try{
      const [pList, dList] = await Promise.all([
        amapResolvePlace(o.pickup.trim(), REGION, KEY),
        amapResolvePlace(o.dropoff.trim(), REGION, KEY)
      ]);
      if(pList.__err) throw new Error('起点解析失败: ' + pList.__err);
      if(dList.__err) throw new Error('终点解析失败: ' + dList.__err);
      const r = await amapDrivingRoute(pList[0], dList[0], KEY);
      if(r.__err) throw new Error('路线规划失败: ' + r.__err);
      rec.newDistance = r.distance_km; rec.newDuration = r.duration_min;
      rec.resolved = pList[0].desc + ' → ' + dList[0].desc;
      if(r.distance_km !== rec.oldDistance || r.duration_min !== rec.oldDuration){
        valueChanged.push(rec);
        o.distance = r.distance_km;
        o.duration = r.duration_min;
      }
    }catch(e){
      failed.push({ rec, reason: e.message });
      o.pickup = rec.oldPickup;      // 失败回滚文字，保持数据可解析状态
      o.dropoff = rec.oldDropoff;
      rec.newPickup = rec.oldPickup; rec.newDropoff = rec.oldDropoff;
    }
  }

  fs.writeFileSync(FILE, JSON.stringify(data, null, 2));
  console.log('已就地更新: ' + FILE);

  let md = `# 顺风账本 · 起终点写法标准化报告

生成时间：${new Date().toLocaleString('zh-CN')}
处理文件：shunfeng-ledger-2026-09-04-new-recalc.json（就地更新）

## 一、标准化映射（人工审核）

| 非标准写法 | 标准写法 | 类型 |
|---|---|---|
${Object.entries(PICKUP_MAP).map(([k, v]) => `| ${k}（起点） | ${v} | ${k.includes('園') ? '繁体字' : k.includes('名称') ? '错别字' : k.includes('西湖') ? '漏字' : k.includes('...') ? 'OCR截断' : '数字/标点写法'} |`).join('\n')}
${Object.entries(DROPOFF_MAP).map(([k, v]) => `| ${k}（终点） | ${v} | ${k.includes('西门') ? '多数派统一（解析结果相同）' : '标点写法'} |`).join('\n')}

**明确不合并**：郑州东站南/北进站口（不同入口）、郑州站东/西进站口、河南省人民医院南门/西门西南侧（不同门）、碧桂园西流湖名城九号院（≠三号院）、高途科教园两种写法。

## 二、文字修正订单（${textChanges.length} 单）

| 日期 | 平台 | 原起点 → 原终点 | 标准化后 | 原里程 | 新里程 | 原耗时 | 新耗时 |
|---|---|---|---|---|---|---|---|
${textChanges.map(r => `| ${r.date} | ${r.platform} | ${r.oldPickup} → ${r.oldDropoff} | ${r.newPickup} → ${r.newDropoff} | ${r.oldDistance} | **${r.newDistance}** | ${r.oldDuration} | **${r.newDuration}** |`).join('\n')}

## 三、数值实际发生变化的订单

${valueChanged.length ? valueChanged.map(r => `- **${r.date} ${r.platform}**：${r.oldDistance}km/${r.oldDuration}min → **${r.newDistance}km/${r.newDuration}min**（${r.resolved}）`).join('\n') : '（无——其余均为纯文字统一，解析与里程不变）'}

${failed.length ? `## 四、失败回滚（保留原文字与原值）\n\n${failed.map(f => `- ${f.rec.date}：${f.reason}`).join('\n')}` : ''}
`;
  fs.writeFileSync(REPORT, md);
  console.log('已写出: ' + REPORT);
  console.log('\n=== 完成: 标准化 ' + textChanges.length + ' 单, 数值变化 ' + valueChanged.length + ' 单, 失败 ' + failed.length + ' ===');
  process.exit(0);
})().catch(e => { console.error('FAIL:', e); process.exit(1); });
