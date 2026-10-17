/* 顺风账本 · 手动补充起终点后的全量重算
 * 输入: shunfeng-ledger-2026-09-04-new.json（用户手动补齐起终点）
 * 输出: shunfeng-ledger-2026-09-04-new-recalc.json + 里程重算报告-新增起终点.md
 * 规则:
 *   1) ocrPrompt 换成最新默认（该文件源自旧导出，提示词仍为旧版）
 *   2) 所有有起点+终点的订单（原有 66 + 手动补充 50）→ 高德 API 重算真实里程/耗时
 *   3) 无起终点的订单（奖励伪订单等）保持原样
 * 复用 APP 内已实测的高德解析/规划逻辑；限速 ≈2.9 次/秒 < 3 次/秒；同名地点/相同起终点对去重缓存 */
const fs = require('fs');
const https = require('https');
const ROOT = '/root/shunfeng-ledger';
const SRC = ROOT + '/shunfeng-ledger-2026-09-04-new.json';
const ORIG = ROOT + '/shunfeng-ledger-2026-09-04.json';
const OUT = ROOT + '/shunfeng-ledger-2026-09-04-new-recalc.json';
const REPORT = ROOT + '/里程重算报告-新增起终点.md';

const html = fs.readFileSync(ROOT + '/android-app/app/src/main/assets/index.html', 'utf8');
(0, eval)(html.slice(html.indexOf('/* 运营区域'), html.indexOf('/* 轻量级驾车路线规划')));
const grabPrompt = name => eval('`' + html.match(new RegExp('const ' + name + ' = `([\\s\\S]*?)`;'))[1] + '`');
const PROMPT_LATEST = grabPrompt('LLM_DEFAULT_PROMPT');

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

(async () => {
  const data = JSON.parse(fs.readFileSync(SRC, 'utf8'));
  const origOrders = JSON.parse(fs.readFileSync(ORIG, 'utf8')).orders;
  const hadEndpoint = new Set(origOrders.filter(o => (o.pickup || '').trim() && (o.dropoff || '').trim()).map(o => o.id));
  const orders = data.orders;
  const KEY = data.ocrCfg && data.ocrCfg.amapKey;
  const REGION = (data.ocrCfg && data.ocrCfg.routeRegion) || '河南省郑州市';

  /* 提示词换成最新 */
  const savedP = data.ocrCfg && data.ocrCfg.ocrPrompt;
  let promptAction = '未存储';
  if(savedP === undefined) promptAction = '未存储（APP 使用内置最新默认）';
  else if((savedP || '').replace(/\s+/g, '') === PROMPT_LATEST.replace(/\s+/g, '')) promptAction = '已是最新';
  else { data.ocrCfg.ocrPrompt = PROMPT_LATEST; promptAction = '已替换为最新默认（原为旧版默认）'; }

  /* 全量重算有端点的订单 */
  const resolveCache = new Map(), routeCache = new Map();
  const resolveCached = async name => {
    if(resolveCache.has(name)) return resolveCache.get(name);
    const r = await amapResolvePlace(name, REGION, KEY).catch(e => ({ __err: e.message }));
    resolveCache.set(name, r);
    return r;
  };
  const routeCached = async (o, d) => {
    const k = o.lng + ',' + o.lat + '|' + d.lng + ',' + d.lat;
    if(routeCache.has(k)) return routeCache.get(k);
    const r = await amapDrivingRoute(o, d, KEY).catch(e => ({ __err: e.message }));
    routeCache.set(k, r);
    return r;
  };

  const targets = orders.filter(o => (o.pickup || '').trim() && (o.dropoff || '').trim());
  console.log('待重算订单: ' + targets.length + ' / ' + orders.length + '（其中手动新增端点 ' + (targets.length - [...hadEndpoint].filter(id => targets.some(o => o.id === id)).length) + ' 单）');
  const changed = [], failed = [];
  let i = 0;
  for(const o of targets){
    i++;
    try{
      const [pList, dList] = await Promise.all([resolveCached(o.pickup.trim()), resolveCached(o.dropoff.trim())]);
      if(pList.__err) throw new Error('起点解析失败: ' + pList.__err);
      if(dList.__err) throw new Error('终点解析失败: ' + dList.__err);
      const r = await routeCached(pList[0], dList[0]);
      if(r.__err) throw new Error('路线规划失败: ' + r.__err);
      changed.push({ id: o.id, date: o.date, platform: o.platform, amount: o.amount,
        pickup: o.pickup, dropoff: o.dropoff,
        source: hadEndpoint.has(o.id) ? '原有' : '手动补充',
        oldDistance: o.distance, oldDuration: o.duration,
        newDistance: r.distance_km, newDuration: r.duration_min });
      o.distance = r.distance_km;
      o.duration = r.duration_min;
    }catch(e){
      failed.push({ id: o.id, date: o.date, pickup: o.pickup, dropoff: o.dropoff, reason: e.message });
    }
    if(i % 10 === 0) console.log('  进度 ' + i + '/' + targets.length + '（累计 API 请求 ' + reqCount + '）');
  }

  data.exportedAt = new Date().toISOString();
  fs.writeFileSync(OUT, JSON.stringify(data, null, 2));
  console.log('已写出: ' + OUT);

  const newOnes = changed.filter(c => c.source === '手动补充');
  const fmt = v => (v == null ? '—' : v);
  let md = `# 顺风账本 · 手动补充起终点后的重算报告

生成时间：${new Date().toLocaleString('zh-CN')}
源文件：shunfeng-ledger-2026-09-04-new.json（${orders.length} 单，其中 ${newOnes.length} 单为手动补充的起终点）
输出文件：shunfeng-ledger-2026-09-04-new-recalc.json（格式一致，可直接导入）

## 一、处理总览

| 项目 | 数量 | 说明 |
|---|---|---|
| 订单总数 | ${orders.length} | — |
| 有起点+终点（本次全部重算） | ${targets.length} | 高德 API 真实路网，成功 ${changed.length} / 失败 ${failed.length} |
| ├ 原有端点订单 | ${changed.filter(c => c.source === '原有').length} | 以本次高德结果为准 |
| ├ 手动补充端点订单 | ${newOnes.length} | 新增解析并计算 |
| 无起终点订单 | ${orders.length - targets.length} | 保持原样（奖励伪订单） |
| API 请求总数 | ${reqCount} | 限速 340ms/次 ≈ 2.9 次/秒 |

提示词：${promptAction}。

## 二、手动补充起终点的 ${newOnes.length} 单计算明细

| 日期 | 平台 | 金额 | 起点 → 终点 | 里程 | 耗时 |
|---|---|---|---|---|---|
${newOnes.map(c => `| ${c.date} | ${c.platform} | ¥${c.amount} | ${c.pickup} → ${c.dropoff} | **${c.newDistance} km** | **${c.newDuration} min** |`).join('\n')}

## 三、原有端点订单重算明细（${changed.filter(c => c.source === '原有').length} 单）

| 日期 | 平台 | 起点 → 终点 | 原里程 | 新里程 | 原耗时 | 新耗时 |
|---|---|---|---|---|---|---|
${changed.filter(c => c.source === '原有').map(c => `| ${c.date} | ${c.platform} | ${c.pickup} → ${c.dropoff} | ${fmt(c.oldDistance)} | **${c.newDistance}** | ${fmt(c.oldDuration)} | **${c.newDuration}** |`).join('\n')}

${failed.length ? `## 四、失败（保留原值）\n\n| 日期 | 起点 → 终点 | 原因 |\n|---|---|---|\n${failed.map(f => `| ${f.date} | ${f.pickup} → ${f.dropoff} | ${f.reason} |`).join('\n')}\n` : ''}

## 五、导入方式

APP → 设置 → 「从文件导入数据」→ 选择 shunfeng-ledger-2026-09-04-new-recalc.json，按订单 id 合并更新。
`;
  fs.writeFileSync(REPORT, md);
  console.log('已写出: ' + REPORT);
  console.log('\n=== 完成: 重算 ' + changed.length + ' 单（新增端点 ' + newOnes.length + '），失败 ' + failed.length + '，API 请求 ' + reqCount + ' ===');
  process.exit(0);
})().catch(e => { console.error('FAIL:', e); process.exit(1); });
