#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
设计预览用：把 recalc JSON 里的所有起终点名称通过高德 Web 服务解析成坐标+行政区，
并按"距离 400m 内归并为同一地点"做聚类，输出 geocode-cache-2026-09-04.json
"""
import json, time, math, re, urllib.request, urllib.parse, sys

KEY = '22c4f1ac678523a67063342559dbe287'
SRC = 'shunfeng-ledger-2026-09-04-new-recalc.json'
OUT = 'geocode-cache-2026-09-04.json'
REGION = '郑州'

def http_json(url):
    req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
    with urllib.request.urlopen(req, timeout=15) as r:
        return json.loads(r.read().decode('utf-8'))

def poi_search(name):
    """v3 POI 检索，region 限定郑州，返回 (lng,lat,adname,真实名称) 或 None"""
    q = urllib.parse.urlencode({
        'keywords': name, 'city': REGION, 'citylimit': 'true',
        'key': KEY, 'offset': '3', 'page': '1',
        'extensions': 'base',
    })
    try:
        j = http_json(f'https://restapi.amap.com/v3/place/text?{q}')
    except Exception as e:
        print('  ! poi err', name, e); return None
    if j.get('status') == '1' and int(j.get('count', '0') or 0) > 0:
        p = j['pois'][0]
        loc = p.get('location', '')
        if ',' in loc:
            lng, lat = loc.split(',')[:2]
            return (float(lng), float(lat), p.get('adname') or '', p.get('name') or name)
    return None

def geocode(name):
    """v3 地理编码兜底"""
    q = urllib.parse.urlencode({'address': name, 'city': REGION, 'key': KEY})
    try:
        j = http_json(f'https://restapi.amap.com/v3/geocode/geo?{q}')
    except Exception as e:
        print('  ! geo err', name, e); return None
    if j.get('status') == '1' and j.get('geocodes'):
        g = j['geocodes'][0]
        loc = g.get('location', '')
        if ',' in loc:
            lng, lat = loc.split(',')[:2]
            dist = g.get('district') or ''
            if not dist:
                return (float(lng), float(lat), '', g.get('formatted_address') or name)
            return (float(lng), float(lat), dist, name)
    return None

def dist_m(a, b):
    dx = (a[0]-b[0]) * 111320 * math.cos(math.radians((a[1]+b[1])/2))
    dy = (a[1]-b[1]) * 110540
    return math.hypot(dx, dy)

def main():
    with open(SRC) as f:
        data = json.load(f)
    orders = data['orders']
    names = set()
    for o in orders:
        if o.get('pickup'): names.add(o['pickup'])
        if o.get('dropoff'): names.add(o['dropoff'])
    names = sorted(names)
    print(f'待解析地点 {len(names)} 个')

    # 增量：已缓存的直接复用
    try:
        with open(OUT) as f:
            cache = json.load(f)
    except FileNotFoundError:
        cache = {}
    todo = [n for n in names if n not in cache]
    print(f'增量：{len(names)-len(todo)} 个已缓存，{len(todo)} 个待请求')

    for i, n in enumerate(todo):
        r = poi_search(n)
        tag = 'poi'
        if not r:
            time.sleep(0.35)
            r = geocode(n); tag = 'geo'
        if r:
            cache[n] = {'lng': r[0], 'lat': r[1], 'district': r[2], 'resolved': r[3], 'via': tag}
            print(f"[{i+1}/{len(todo)}] {tag}  {n}  ->  {r[3]} ({r[0]:.5f},{r[1]:.5f}) {r[2]}")
        else:
            cache[n] = None
            print(f"[{i+1}/{len(todo)}] FAIL  {n}")
        time.sleep(0.35)

    with open(OUT, 'w') as f:
        json.dump(cache, f, ensure_ascii=False, indent=1)
    ok = sum(1 for v in cache.values() if v)
    print(f'\n完成：{ok}/{len(names)} 解析成功，已写入 {OUT}')

if __name__ == '__main__':
    main()
