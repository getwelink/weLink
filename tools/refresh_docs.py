"""Regenerate callback and error documentation from the public reference snapshot."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
reference = json.loads((ROOT / 'docs/reference.json').read_text(encoding='utf-8'))


def cell(value):
    return str(value).replace('|', '\\|').replace('\n', '<br>')


def fields(rows):
    result = ['| 字段 | 类型 | 说明 |', '| --- | --- | --- |']
    result += ['| `%s` | %s | %s |' % (cell(r['name']), cell(r['type']), cell(r['note'])) for r in rows]
    return '\n'.join(result)


intro = '> 提供免费试用，项目长期维护。服务地址和试用授权码请通过 [首页 QQ](../README.md#免费试用与长期维护) 联系。'
events = reference['events']
parts = ['# 事件回调',
         'Webhook 与事件列表使用相同的报文结构。SSE 只推送连接建立后的实时事件，历史记录使用事件列表查询。', intro,
         '## 接入顺序',
         '1. 在控制台为实例配置 Webhook 地址和密钥。\n2. 使用原始请求体校验签名，再解析 JSON。\n3. 以 `event_id` 去重，将任务放入队列，再尽快返回 2xx。\n4. 未成功处理时保留事件，按业务要求重试；SDK 不会自动重发消息。',
         '## 请求头', fields(events['headers']), '## 报文结构', fields(events['envelope']),
         '## 验签', '签名是 `"sha256=" + HMAC-SHA256(密钥, 原始请求体)` 的小写十六进制。不要先解析再重新序列化 JSON。',
         '''```python
from welink import verify_webhook
valid = verify_webhook(secret, raw_body, signature)
```

```javascript
import { verifyWebhook } from './welink.js'
const valid = verifyWebhook(secret, rawBody, signature)
```

```go
valid := welink.VerifyWebhook(secret, rawBody, signature)
```

```java
boolean valid = WeLink.verifyWebhook(secret, rawBody, signature);
```''', '## 事件类型', '| 类型 | 名称 | 触发条件 |\n| --- | --- | --- |']
parts.append('\n'.join('| `%s` | %s | %s |' % (r['type'], cell(r['name']), cell(r['when'])) for r in events['events']))
parts += ['## 消息公共字段', fields(events['message']['common']), '## 消息类型', '| type | 名称 | 附带对象 |\n| --- | --- | --- |']
parts.append('\n'.join('| `%s` | %s | %s |' % (r['kind'], cell(r['name']), '`%s`' % r['carries'] if r.get('carries') else '—') for r in events['message']['kinds']))
for obj in events['message']['objects']:
    parts += ['## %s：%s' % (obj['name'], obj['title'])]
    if obj.get('note'):
        parts.append(obj['note'])
    parts.append(fields(obj['fields']))
    if obj.get('example'):
        parts.append('```json\n'+json.dumps(obj['example'], ensure_ascii=False, indent=2)+'\n```')
parts += ['## 轮询与 SSE',
          '轮询示例：[Python](../examples/python/3_poll_events.py)、[Node.js](../examples/node/3-poll-events.mjs)。首次可指定 `since`，后续保留 `next_cursor`，空页时等待再查询。生产环境应持久化游标和去重状态。',
          'SSE 的四种语言用法见 [接口文档的事件流](API.md#事件流)。断开后需要由调用方重连；SSE 不补发离线期间的事件，需要时用事件列表补查。',
          '## 处理红包与转账',
          '转账为 `data.type = "transfer"`，红包为 `data.type = "red_packet"`，详情在 `data.payment`。金额缺失表示原始报文没有提供，不能显示成 0 元。类型编码只描述本次消息，不代表实时收款状态。',
          '''```python
payment = event.get("data", {}).get("payment") or {}
amount_fen = payment.get("amount_fen")
if amount_fen is None:
    print("报文未提供金额")
else:
    print(payment.get("amount"), payment.get("currency", ""))
```''']
(ROOT/'docs/WEBHOOK.md').write_text('\n\n'.join(parts)+'\n', encoding='utf-8')
rows = ['# 错误码',
        '同时判断 HTTP 状态和平台 `code`。SDK 调用失败会抛出或返回各语言的错误类型，保留错误码、HTTP 状态和 `request_id`。', intro,
        '| code | HTTP | 名称 | 说明 |\n| --- | --- | --- | --- |']
rows.append('\n'.join('| `%s` | %s | %s | %s |' % (r['code'], r['http_status'], r['name'], cell(r['message'])) for r in reference['errors']))
rows += ['## 超时与重试',
         'SDK 的普通请求默认超时为 30 秒，不会自动重发。发送消息、添加好友等写操作超时后，服务端可能已经处理成功；先核对结果，避免重复执行。需要幂等重试时，可在自行构造的 HTTP 请求中带上同一操作的 `Idempotency-Key`，不同操作不能共用该值。',
         'SSE 使用独立的流读取接口，生命周期由调用方管理。断开后重连并按 `event_id` 去重，需要补历史时使用事件列表。']
(ROOT/'docs/ERRORS.md').write_text('\n\n'.join(rows)+'\n', encoding='utf-8')
