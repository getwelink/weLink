# WeLink Node.js 客户端

只用内置 `fetch`，Node 18 以上，没有依赖。

## 用

```javascript
import { WeLink, WeLinkError } from './welink.js'

const wx = new WeLink({ apiKey: 'key_xxx', baseUrl: 'https://你的服务地址' })

try {
  await wx.message.text('acc_xxx', { to: 'filehelper', content: '你好' })
} catch (e) {
  if (e instanceof WeLinkError) console.log(e.code, e.message, e.requestId)
}
```

方法按接口 ID 分了组：`message.text` → `wx.message.text`，
`moment.post_images` → `wx.moment.postImages`。

路径参数是位置参数，其余都放在最后那个对象里。

清单里还没有的新接口，用底层的 `call`：

```javascript
await wx.call('POST', '/accounts/acc_xxx/some/new/route', { body: { a: 1 } })
```

## 收事件

```javascript
import { verifyWebhook } from './welink.js'

if (!verifyWebhook(secret, rawBody, req.headers['x-orbit-signature'])) {
  return res.writeHead(403).end()
}
```

`rawBody` 必须是原始字节。用 express 的话别让 `express.json()` 先把它吃掉 ——
用 `express.raw({ type: 'application/json' })`。

## 接入约定

服务地址填写根地址，不带 `/v1`。SDK 返回响应中的 `data`；失败时保留错误码、HTTP 状态和请求 ID。普通请求默认超时 30 秒，写操作超时后先核对结果，SDK 不自动重发。

红包与转账的字段位于事件的 `data.payment`，金额为 `amount`（两位小数字符串）和 `amount_fen`（整数分）；红包报文没有金额时这些字段会缺省。详见 [回调字段](../../docs/WEBHOOK.md#payment转账与红包)。

## SSE 事件流

长连接用法与关闭方式见 [四种语言示例](../../docs/API.md#事件流)。SSE 只接收实时事件，断开后由程序重连，历史记录使用事件列表补查。
