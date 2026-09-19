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
