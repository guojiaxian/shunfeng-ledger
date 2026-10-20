#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把底图 base64 / 聚合数据 / 投影参数注入模板，产出 preview-viz.html"""
import base64, json, re

html = open('preview-viz-template.html').read()
html = html.replace('__MAP_B64__', base64.b64encode(open('map-base.png','rb').read()).decode())
proj = json.load(open('map-proj.json'))
html = html.replace('__MAP_W__', str(proj['W'])).replace('__MAP_H__', str(proj['H']))
html = html.replace('const P = __PROJ__;',
                    'const P = ' + json.dumps({k: proj[k] for k in ('W','H','zoom','clng','clat')}) + ';')
viz = open('viz-data-2026-09-04.js').read().split('=',1)[1].rstrip().rstrip(';')
html = html.replace('/*__VIZ__*/', 'const VIZ = ' + viz + ';')
open('preview-viz.html','w').write(html)
print('注入完成:', len(html)//1024, 'KB',
      '| 残留占位符:', re.findall(r'__[A-Z_]+__', html)[:5] or '无')
