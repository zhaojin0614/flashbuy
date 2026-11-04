# -*- coding: utf-8 -*-
# 端到端验证：登录 → 发评论 → 查评论 → 评论计数 → 我的订单（urllib 强制 UTF-8）
import json, os, re, time, urllib.request

LOG = os.path.join(os.environ['TEMP'], 'fb-comments.log')

BASE = 'http://localhost:8080/api'

def call(method, path, token=None, body=None):
    req = urllib.request.Request(BASE + path, method=method)
    if token:
        req.add_header('authorization', token)
    data = None
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode('utf-8')
        req.add_header('Content-Type', 'application/json; charset=utf-8')
    try:
        with urllib.request.urlopen(req, data=data, timeout=10) as r:
            return json.loads(r.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        return {'success': False, 'errorMsg': e.read().decode('utf-8', 'ignore')[:120]}

# 1. 登录
call('POST', '/user/code?phone=13900002222'); time.sleep(2)
code = None
log = open(LOG, encoding='utf-8', errors='ignore').read()
m = re.findall(r'验证码为：(\d{6})', log)
code = m[-1]
r = call('POST', '/user/login', body={'phone': '13900002222', 'code': code})
print('登录响应:', r)
token = r['data']
print('1.登录 ✓')

# 2. 发中文评论
r = call('POST', '/post-comments', token=token, body={'postId': 4, 'content': '开箱体验：抢购到落库全程丝滑，强烈推荐！'})
print('2.发评论:', r)

# 3. 评论列表
r = call('GET', '/post-comments/of/4', token=token)
for c in r['data']:
    print('3.评论:', c.get('name'), '|', c.get('content'), '| 头像有值:', bool(c.get('icon')))

# 4. 评论计数
r = call('GET', '/post/4', token=token)
print('4.笔记 comments =', r['data']['comments'])

# 5. 我的订单（13700003333 买过商品7）
call('POST', '/user/code?phone=13700003333'); time.sleep(2)
m2 = re.findall(r'验证码为：(\d{6})', open(r'/tmp/fb-comments.log', encoding='utf-8', errors='ignore').read())
r = call('POST', '/user/login', body={'phone': '13700003333', 'code': m2[-1]})
r = call('GET', '/order/of/me', token=r['data'])
for o in r['data']:
    print('5.订单:', o['orderId'], '|', o.get('title'), '|', o.get('merchantName'), '| ¥%.2f' % (o.get('payValue', 0) / 100))
