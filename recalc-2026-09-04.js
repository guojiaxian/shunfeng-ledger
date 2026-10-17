/* 顺风账本 · 历史订单里程/耗时重算脚本 v2
 * 输入: shunfeng-ledger-2026-09-04.json（新版完整备份格式）
 * 输出: shunfeng-ledger-2026-09-04-recalc.json + 里程重算报告-2026-09-04.md
 * 方法（v2，按平台分别拟合）:
 *   1) ocrCfg.ocrPrompt 换成 APP 内置最新默认提示词
 *   2) 有起点+终点的订单 → 高德地图 API 重算真实里程/耗时（限速 ≈2.9 次/秒 < 3 次/秒，地点与路线均缓存）
 *   3) 用重算后的 66 单作为训练集，高德/滴滴分别拟合「金额→里程」「里程→时间」线性函数
 *   4) 无起终点的订单按所属平台函数直接估算并覆盖旧值；
 *      金额超出训练区间 ±20% 或原始记录无里程/耗时（奖励伪订单）的除外，保留原值 */
const fs = require('fs');
const https = require('https');
const ROOT = '/root/shunfeng-ledger';
const SRC = ROOT + '/shunfeng-ledger-2026-09-04.json';
const OUT = ROOT + '/shunfeng-ledger-2026-09-04-recalc.json';
const REPORT = ROOT + '/里程重算报告-2026-09-04.md';

const html = fs.readFileSync(ROOT + '/android-app/app/src/main/assets/index.html', 'utf8');

// —— 提取 APP 真实的高德识路函数（与线上行为完全一致）——
(0, eval)(html.slice(html.indexOf('/* 运营区域'), html.indexOf('/* 轻量级驾车路线规划')));

// —— 提取三代默认提示词的运行时字符串 ——
const grabPrompt = name => eval('`' + html.match(new RegExp('const ' + name + ' = `([\\s\\S]*?)`;'))[1] + '`');
const PROMPT_V1 = grabPrompt('LLM_DEFAULT_PROMPT_V1');
const PROMPT_V2 = grabPrompt('LLM_DEFAULT_PROMPT_V2');
const PROMPT_LATEST = grabPrompt('LLM_DEFAULT_PROMPT');

// —— 限速 XHR：全局间隔 ≥340ms（≈2.9 次/秒 < 高德 3 次/秒限制）——
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
  const orders = data.orders;
  const KEY = data.ocrCfg && data.ocrCfg.amapKey;
  if(!KEY) throw new Error('备份里没有高德 Key');
  const REGION = (data.ocrCfg && data.ocrCfg.routeRegion) || '河南省郑州市';

  /* ---- 1) 提示词换成最新默认 ---- */
  /* —— 有效车费修正：金额含大额奖励的订单，按「有效车费」参与估算（不改订单金额/奖励字段）—— */
  const EFFECTIVE_AMOUNT = { mseg1ekxqbd6u: 19.62 };   // 2026-07-25 高德 ¥49.62 = 19.62 车费 + 30 奖励

  const savedP = data.ocrCfg && data.ocrCfg.ocrPrompt;
  const norm = s => (s || '').replace(/\s+/g, '');
  let promptAction = '未存储（APP 将使用内置最新默认）';
  if(savedP === undefined) promptAction = '未存储（APP 将使用内置最新默认）';
  else if(norm(savedP) === norm(PROMPT_LATEST)) promptAction = '已是最新，无需改动';
  else {
    const wasOld = norm(savedP) === norm(PROMPT_V1) ? '（原为 V1 旧默认）' : norm(savedP) === norm(PROMPT_V2) ? '（原为 V2 旧默认）' : '（原为自定义/旧变体）';
    data.ocrCfg.ocrPrompt = PROMPT_LATEST;
    promptAction = '已替换为最新默认 ' + wasOld;
  }

  /* ---- 2) 有起终点的订单：高德 API 重算（缓存 + 限速）---- */
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

  const hasRoute = o => (o.pickup || '').trim() && (o.dropoff || '').trim();
  const targets = orders.filter(hasRoute);
  console.log('待重算订单: ' + targets.length + ' / ' + orders.length + '（限速 ≈2.9 次/秒）');
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
      changed.push({ id: o.id, date: o.date, platform: o.platform, pickup: o.pickup, dropoff: o.dropoff,
        oldDistance: o.distance, oldDuration: o.duration, newDistance: r.distance_km, newDuration: r.duration_min });
      o.distance = r.distance_km;
      o.duration = r.duration_min;
    }catch(e){
      failed.push({ id: o.id, date: o.date, pickup: o.pickup, dropoff: o.dropoff, reason: e.message });
    }
    if(i % 10 === 0) console.log('  进度 ' + i + '/' + targets.length + '（累计 API 请求 ' + reqCount + '）');
  }

  /* ---- 3) 按平台拟合「金额→里程」「里程→时间」（训练集 = 重算后的 66 单）---- */
  const num = v => { const x = parseFloat(v); return isFinite(x) ? x : null; };
  const fit = (pts, getX, getY) => {
    const n = pts.length;
    let sx = 0, sy = 0, sxy = 0, sxx = 0;
    pts.forEach(p => { const x = getX(p), y = getY(p); sx += x; sy += y; sxy += x * y; sxx += x * x; });
    const a = (n * sxy - sx * sy) / (n * sxx - sx * sx);
    const b = (sy - a * sx) / n;
    const ym = sy / n;
    let ssr = 0, sst = 0;
    pts.forEach(p => { const f = a * getX(p) + b, y = getY(p); ssr += (y - f) ** 2; sst += (y - ym) ** 2; });
    return { a, b, r2: sst ? 1 - ssr / sst : 0, n };
  };
  const minAmount = {}, maxAmount = {}, fits = {};
  ['gaode', 'didi'].forEach(pf => {
    const pts = targets.filter(o => o.platform === pf)
      .map(o => ({ amount: num(o.amount), distance: num(o.distance), duration: num(o.duration) }))
      .filter(p => p.amount > 0 && p.distance > 0 && p.duration > 0);
    minAmount[pf] = Math.min(...pts.map(p => p.amount));
    maxAmount[pf] = Math.max(...pts.map(p => p.amount));
    fits[pf] = {
      n: pts.length,
      dist: fit(pts, p => p.amount, p => p.distance),                    // 里程 = a×金额 + b
      durByDist: fit(pts, p => p.distance, p => p.duration),             // 时间 = c×里程 + d
      durByAmt: fit(pts, p => p.amount, p => p.duration)                 // 时间 = e×金额 + f
    };
  });

  /* ---- 4) 无起终点订单：按平台函数估算并覆盖（金额外推保护）---- */
  const noEndpoint = orders.filter(o => !hasRoute(o));
  const estimated = [], skipped = [];
  for(const o of noEndpoint){
    const pf = fits[o.platform] ? o.platform : null;
    const adjusted = EFFECTIVE_AMOUNT[o.id] != null;
    const amt = num(adjusted ? EFFECTIVE_AMOUNT[o.id] : o.amount);
    const od = num(o.distance), ou = num(o.duration);
    if(!pf || !amt || (od === 0 && ou === 0)){
      skipped.push({ id: o.id, date: o.date, platform: o.platform, amount: amt, reason: !pf ? '未知平台' : (od === 0 && ou === 0) ? '非行程记录（奖励/0里程）' : '无金额' });
      continue;
    }
    if(amt < minAmount[pf] * 0.8 || amt > maxAmount[pf] * 1.2){
      skipped.push({ id: o.id, date: o.date, platform: o.platform, amount: amt, reason: '金额 ' + amt + ' 元超出' + pf + '训练区间 [' + minAmount[pf] + '~' + maxAmount[pf] + ']±20%，避免外推' });
      continue;
    }
    const f = fits[pf];
    const fd = Math.max(0.5, f.dist.a * amt + f.dist.b);
    // 时间优先按里程链式计算（R² 更高时），否则按金额直算
    const durFit = f.durByDist.r2 >= 0.5 ? { fit: f.durByDist, x: fd, by: '里程' } : { fit: f.durByAmt, x: amt, by: '金额' };
    const fu = Math.max(3, durFit.fit.a * durFit.x + durFit.fit.b);
    estimated.push({ id: o.id, date: o.date, platform: o.platform, amount: num(o.amount),
      oldDistance: o.distance, oldDuration: o.duration,
      newDistance: Math.round(fd * 10) / 10, newDuration: Math.round(fu),
      note: adjusted ? '金额含 ' + (num(o.amount) - amt).toFixed(2) + ' 元奖励，按有效车费 ' + amt + ' 元估算' : '' });
    o.distance = Math.round(fd * 10) / 10;
    o.duration = Math.round(fu);
  }

  /* ---- 5) 写出结果 ---- */
  data.exportedAt = new Date().toISOString();
  fs.writeFileSync(OUT, JSON.stringify(data, null, 2));
  console.log('已写出: ' + OUT);

  /* ---- 6) 生成报告 ---- */
  const fmt = v => (v == null ? '—' : v);
  const fitLine = (f, unit) => `${f.a.toFixed(2)} × 自变量 + ${f.b.toFixed(2)}（${unit}，R² = ${f.r2.toFixed(3)}，n = ${f.n}）`;
  let md = `# 顺风账本 · 里程/耗时重算报告（v2 按平台拟合）

生成时间：${new Date().toLocaleString('zh-CN')}
源文件：shunfeng-ledger-2026-09-04.json（${orders.length} 单）
输出文件：shunfeng-ledger-2026-09-04-recalc.json（格式与备份完全一致，可直接「从文件导入数据」）

## 一、处理总览

| 项目 | 数量 | 处理方式 |
|---|---|---|
| 订单总数 | ${orders.length} | — |
| 有起点+终点 | ${targets.length} | 高德地图 API 按真实路网重算（成功 ${changed.length} / 失败 ${failed.length}） |
| 无起点+终点 | ${noEndpoint.length} | 按平台回归函数估算里程/耗时，**直接覆盖**旧值 |
| ├ 高德平台待估 | ${noEndpoint.filter(o => o.platform === 'gaode').length} | 用高德 ${fits.gaode.n} 单重算样本拟合 |
| ├ 滴滴平台待估 | ${noEndpoint.filter(o => o.platform === 'didi').length} | 用滴滴 ${fits.didi.n} 单重算样本拟合 |
| ├ 估算保护跳过 | ${skipped.length} | 奖励伪订单 / 金额超出训练区间（保留原值，见附表） |
| API 请求总数 | ${reqCount} | 全局限速 340ms/次 ≈ 2.9 次/秒（< 3 次/秒限制） |

## 二、提示词更新（第 1 项）

${promptAction}。新默认为二分规则：出现滴滴标语「顺风有你/温暖前行」→ 滴滴，否则 → 高德。

## 三、高德 API 重算明细（第 2、3 项）

起点/终点文字 → 高德 POI 检索（region=郑州）解析坐标（失败转地理编码兜底）→ 路径规划 2.0（/v5/direction/driving，show_fields=cost）取真实驾车距离（米→km）与实时路况耗时（秒→分钟）。同名地点与相同起终点对已去重缓存。

| 日期 | 平台 | 起点 → 终点 | 原里程 | 新里程 | 原耗时 | 新耗时 |
|---|---|---|---|---|---|---|
${changed.map(c => `| ${c.date} | ${c.platform} | ${c.pickup} → ${c.dropoff} | ${fmt(c.oldDistance)} | **${c.newDistance}** | ${fmt(c.oldDuration)} | **${c.newDuration}** |`).join('\n')}

${failed.length ? `### 重算失败（保留原值）\n\n| 日期 | 起点 → 终点 | 原因 |\n|---|---|---|\n${failed.map(f => `| ${f.date} | ${f.pickup} → ${f.dropoff} | ${f.reason} |`).join('\n')}\n` : ''}

## 四、按平台拟合的估算函数（第 4 项）

训练集为上表 ${changed.length} 单「真实路网里程 × 实际金额」，高德/滴滴**分开拟合**（两家定价结构不同）：

### 高德（训练样本 ${fits.gaode.n} 单，金额范围 ${minAmount.gaode}~${maxAmount.gaode} 元）
- **里程 ≈ ${fits.gaode.dist.a.toFixed(2)} × 金额 + ${fits.gaode.dist.b.toFixed(2)}**（km，R² = ${fits.gaode.dist.r2.toFixed(3)}）
- **时间 ≈ ${fits.gaode.durByDist.a.toFixed(2)} × 里程 + ${fits.gaode.durByDist.b.toFixed(2)}**（分钟，按里程链式，R² = ${fits.gaode.durByDist.r2.toFixed(3)}；按金额直算 R² = ${fits.gaode.durByAmt.r2.toFixed(3)}）

### 滴滴（训练样本 ${fits.didi.n} 单，金额范围 ${minAmount.didi}~${maxAmount.didi} 元）
- **里程 ≈ ${fits.didi.dist.a.toFixed(2)} × 金额 + ${fits.didi.dist.b.toFixed(2)}**（km，R² = ${fits.didi.dist.r2.toFixed(3)}）
- **时间 ≈ ${fits.didi.durByDist.a.toFixed(2)} × 里程 + ${fits.didi.durByDist.b.toFixed(2)}**（分钟，按里程链式，R² = ${fits.didi.durByDist.r2.toFixed(3)}；按金额直算 R² = ${fits.didi.durByAmt.r2.toFixed(3)}）

> 滴滴训练样本 ${fits.didi.n} 单相对较少，拟合置信度低于高德；后续订单积累后建议重跑本脚本更新系数。

### 无端点订单估算覆盖明细（旧值已被覆盖）

| 日期 | 平台 | 金额 | 原里程 | 新里程 | 原耗时 | 新耗时 | 备注 |
|---|---|---|---|---|---|---|---|
${estimated.map(x => `| ${x.date} | ${x.platform} | ¥${x.amount} | ${fmt(x.oldDistance)} | **${x.newDistance}** | ${fmt(x.oldDuration)} | **${x.newDuration}** | ${x.note || ''} |`).join('\n')}

### 估算保护跳过（保留原值）

| 日期 | 平台 | 金额 | 原因 |
|---|---|---|---|
${skipped.length ? skipped.map(x => `| ${x.date} | ${x.platform} | ¥${fmt(x.amount)} | ${x.reason} |`).join('\n') : '| — | — | — | — |'}

## 五、导入方式

APP → 设置 → 「从文件导入数据」→ 选择 shunfeng-ledger-2026-09-04-recalc.json。导入按订单 id 合并更新，里程/耗时自动刷新，设置（含最新提示词）一并恢复。
`;
  fs.writeFileSync(REPORT, md);
  console.log('已写出: ' + REPORT);
  console.log('\n=== 完成: API重算 ' + changed.length + ' 单(失败' + failed.length + '), 估算覆盖 ' + estimated.length + ' 单, 保护跳过 ' + skipped.length + ', API请求 ' + reqCount + ' 次 ===');
  process.exit(0);
})().catch(e => { console.error('FAIL:', e); process.exit(1); });
