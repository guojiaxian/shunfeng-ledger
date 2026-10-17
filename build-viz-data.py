#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
构建预览数据 v2：
- 名称归一化分组 + (前缀相似 & <200m) 合并
- 排除 OCR 误识别的奖励记录与市外离群点
- 聚合 OD 线路 / 跨区矩阵 / 时刻热力 / 散点
- 下载高德静态地图底图（zoom 11, scale 2）
"""
import json, math, urllib.request, urllib.parse, difflib
from collections import Counter
import datetime

KEY = '22c4f1ac678523a67063342559dbe287'
GEO = 'geocode-cache-2026-09-04.json'

BLACKLIST = {'单笔奖励'}  # OCR 误识别的非地点文本
SRC = 'shunfeng-ledger-2026-09-04-new-recalc.json'

with open(SRC) as f: data = json.load(f)
with open(GEO) as f: geo = json.load(f)
orders = data['orders']

# ---------- 1. 名称归一化 ----------
SUFFIX = re_pat = None
import re
def normalize(n):
    s = n
    s = re.sub(r'[（(][^（）()]*[)）]$', '', s)          # 尾部括号
    s = re.sub(r'[（(][^（）()]*[)）]', '', s) if len(s) > 6 else s  # 中间括号(长名才去)
    s = re.sub(r'(北侧|南侧|东侧|西侧|门口?|旁|附近)$', '', s)
    s = re.sub(r'[-—·]?(南门|北门|东门|西门|上车点|地下车库入口|地面停车场|南北?进站口|东西?进站口|进出站口|出站口|进站口)$', '', s)
    s = re.sub(r'([0-9一二三四五六七八]期|[A-Ha-h]区)$', '', s)
    s = s.replace('...', '').replace('…', '')
    s = s.replace('三号院', '3号院')
    s = re.sub(r'[\s·，,。\-—_/]', '', s)
    return s

# ---------- 2. 有效性过滤（距市中心 >30km 视为解析失败/市外误单） ----------
import math as _math
CENTER = (113.667, 34.767)
def _far(n, g):
    dx = (g['lng']-CENTER[0]) * 91300
    dy = (g['lat']-CENTER[1]) * 111320
    return _math.hypot(dx, dy) > 30000
valid = {n for n, g in geo.items() if g and n not in BLACKLIST and not _far(n, g)}
print(f'有效地点 {len(valid)}/{len(geo)}（排除: {sorted(set(geo)-valid)}）')

# ---------- 3. 分组合并 ----------
norm_groups = {}   # normName -> {'names': set, 'lng':, 'lat':}
for n in valid:
    g = geo[n]
    k = normalize(n)
    if k not in norm_groups:
        norm_groups[k] = {'names': set(), 'lng': g['lng'], 'lat': g['lat']}
    norm_groups[k]['names'].add(n)

def dist_m(a, b):
    dx = (a[0]-b[0]) * 111320 * math.cos(math.radians((a[1]+b[1])/2))
    dy = (a[1]-b[1]) * 110540
    return math.hypot(dx, dy)

# 前缀/相似 + 近距离 合并（union-find）
keys = list(norm_groups)
parent = {k: k for k in keys}
def find(k):
    while parent[k] != k: parent[k] = parent[parent[k]]; k = parent[k]
    return k
def union(a, b):
    ra, rb = find(a), find(b)
    if ra != rb: parent[rb] = ra

for i in range(len(keys)):
    for j in range(i+1, len(keys)):
        a, b = keys[i], keys[j]
        ga, gb = norm_groups[a], norm_groups[b]
        if dist_m((ga['lng'], ga['lat']), (gb['lng'], gb['lat'])) > 200: continue
        ratio = difflib.SequenceMatcher(None, a, b).ratio()
        if a.startswith(b) or b.startswith(a) or ratio >= 0.72:
            union(a, b)

groups = {}
for k in keys:
    r = find(k)
    groups.setdefault(r, {'names': set(), 'lng': 0, 'lat': 0, 'm': 0})
    g = norm_groups[k]
    groups[r]['names'] |= g['names']
    groups[r]['lng'] += g['lng']; groups[r]['lat'] += g['lat']; groups[r]['m'] += 1
for r in groups:
    groups[r]['lng'] /= groups[r]['m']; groups[r]['lat'] /= groups[r]['m']

# 显示名：原始名中最长者保留完整、频次相同取更短更通用的
key_list = []
for r, grp in groups.items():
    names = list(grp['names'])
    best = sorted(names, key=lambda n: (len(n), n))[0]
    # 优先不带后缀省略号的
    grp['display'] = best.replace('...', '').replace('…', '')
    key_list.append(r)
print(f'合并后地点数: {len(groups)}')
for r, grp in groups.items():
    if len(grp['names']) > 1:
        print('  合并:', ' | '.join(grp['names']), '->', grp['display'])

norm2group = {}
for n in valid:
    norm2group[n] = find(normalize(n))

# 组的区县：取组内任一
for r, grp in groups.items():
    for n in grp['names']:
        if geo[n]['district']:
            grp['district'] = geo[n]['district']; break
    else:
        grp['district'] = ''

# ---------- 4. 订单聚合 ----------
pick_f = Counter(); drop_f = Counter()
od = {}
dmatrix = {}
heat = [[0]*24 for _ in range(7)]
heatAmt = [[0.0]*24 for _ in range(7)]
scatter = []

od_orders = []
for o in orders:
    p, d = o.get('pickup',''), o.get('dropoff','')
    if p in BLACKLIST or d in BLACKLIST: continue
    if p not in valid or d not in valid: continue
    od_orders.append(o)
    gi, gj = norm2group[p], norm2group[d]
    if gi == gj:  # 同一点起终（极少）也计入但标记
        pass
    pick_f[gi] += 1; drop_f[gj] += 1
    k = (gi, gj)
    if k not in od: od[k] = {'n':0,'amount':0.0,'distance':0.0,'duration':0}
    od[k]['n'] += 1
    od[k]['amount'] += o['amount']+o.get('bonus',0)
    od[k]['distance'] += o.get('distance',0) or 0
    od[k]['duration'] += o.get('duration',0) or 0
    pd = groups[gi]['district'] or '其他'
    dd = groups[gj]['district'] or '其他'
    dmatrix[(pd,dd)] = dmatrix.get((pd,dd),0)+1
    y,m,dd_ = map(int, o['date'].split('-'))
    wd = datetime.date(y,m,dd_).weekday()
    hh = int(o['time'][:2])
    heat[wd][hh] += 1
    heatAmt[wd][hh] += o['amount']+o.get('bonus',0)
    if o.get('distance'):
        scatter.append({'d':o['distance'],'u':round((o['amount']+o.get('bonus',0))/o['distance'],2),
                        'a':o['amount']+o.get('bonus',0),'p':o['platform']})

# 组频次写入 display
gi2idx = {}
nodes = []
for idx, (r, grp) in enumerate(sorted(groups.items(), key=lambda kv: -(pick_f[kv[0]]+drop_f[kv[0]]))):
    gi2idx[r] = idx
    nodes.append({'i':idx,'name':grp['display'],'lng':round(grp['lng'],6),'lat':round(grp['lat'],6),
                  'district':grp['district'],'p':pick_f.get(r,0),'dr':drop_f.get(r,0)})

od_list = []
for (gi, gj), v in od.items():
    od_list.append({'p':gi2idx[gi],'d':gi2idx[gj],**v})
od_list.sort(key=lambda x:-x['n'])

districts = sorted(set(n['district'] for n in nodes if n['district']))
dm_rows = sorted(set(r for r,_ in dmatrix), key=lambda x: districts.index(x) if x in districts else 99)
dm_cols = sorted(set(c for _,c in dmatrix), key=lambda x: districts.index(x) if x in districts else 99)
dm_cells = [[dmatrix.get((r,c),0) for c in dm_cols] for r in dm_rows]

out = {
  'generatedAt':'2026-09-04',
  'nodes':nodes,
  'od':od_list,
  'districts':{'rows':dm_rows,'cols':dm_cols,'cells':dm_cells},
  'heat':{'count':heat,'amount':[[round(x,1) for x in row] for row in heatAmt]},
  'scatter':scatter,
  'kpis':{
    'ordersTotal':len(orders), 'ordersOD':len(od_orders),
    'locations':len(nodes), 'routes':len(od_list),
    'amountOD':round(sum(o['amount']+o.get('bonus',0) for o in od_orders),1),
    'distOD':round(sum(o.get('distance',0) for o in od_orders),1),
  }
}
with open('viz-data-2026-09-04.js','w') as f:
    f.write('// 由 build-viz-data.py 生成 · 数据源 shunfeng-ledger-2026-09-04-recalc.json\n')
    f.write('const VIZ = ')
    json.dump(out, f, ensure_ascii=False)
    f.write(';\n')
print('OD 线路:', len(od_list), '| 节点:', len(nodes), '| 散点:', len(scatter))
print('跨区矩阵:', dm_rows, '×', dm_cols)
top = [(nodes[o['p']]['name'], nodes[o['d']]['name'], o['n']) for o in od_list[:8]]
for t in top: print('  TOP:', t[0], '→', t[1], '×', t[2])

# ---------- 5. 静态地图底图 ----------
lngs = [n['lng'] for n in nodes]; lats = [n['lat'] for n in nodes]
clng = (min(lngs)+max(lngs))/2; clat = (min(lats)+max(lats))/2
Z = 11
pxdeg = 256*2**Z/360
content_w = (max(lngs)-min(lngs))*pxdeg
scaleF = 1/math.cos(math.radians(clat))
content_h = (max(lats)-min(lats))*pxdeg*scaleF
W = int(math.ceil(content_w*1.28/2)*2); H = int(math.ceil(content_h*1.30/2)*2)
W = max(W, 520); H = max(H, 520)
print(f'内容 {content_w:.0f}×{content_h:.0f} px -> 底图 {W}×{H} @ z{Z} 中心 {clng:.5f},{clat:.5f}')

q = urllib.parse.urlencode({'location':f'{clng:.6f},{clat:.6f}','zoom':Z,'size':f'{W}*{H}',
                            'scale':2,'key':KEY})
urllib.request.urlretrieve(f'https://restapi.amap.com/v3/staticmap?{q}', 'map-base.png')
import os
print('底图已保存 map-base.png', os.path.getsize('map-base.png'), 'bytes')
# 验证 PNG 实际尺寸
with open('map-base.png','rb') as f:
    head = f.read(24)
w_px = int.from_bytes(head[16:20],'big'); h_px = int.from_bytes(head[20:24],'big')
print(f'PNG 实际像素: {w_px}×{h_px} (scale2={w_px==W*2})')
with open('map-proj.json','w') as f:
    json.dump({'W':W,'H':H,'pngW':w_px,'pngH':h_px,'zoom':Z,'clng':round(clng,6),'clat':round(clat,6)}, f)
