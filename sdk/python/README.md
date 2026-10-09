# WeLink Python 客户端

只用标准库，Python 3.8 以上。

## 装

把 `welink/` 目录拷进你的项目就行，没有依赖要装。

在仓库根目录安装：

```bash
pip install ./sdk/python
```

## 用

```python
from welink import WeLink, WeLinkError

wx = WeLink(api_key="key_xxx", base_url="https://你的服务地址")

try:
    wx.message_text("acc_xxx", to="filehelper", content="你好")
except WeLinkError as e:
    print(e.code, e.message, e.request_id)
```

88 个接口对应 88 个方法，名字就是接口 ID 把点换成下划线：
`message.text` → `message_text`，`moment.post_images` → `moment_post_images`。

清单里还没有的新接口，用底层的 `call`：

```python
wx.call("POST", "/accounts/acc_xxx/some/new/route", body={"a": 1})
```

## 收事件

```python
from welink import verify_webhook

if not verify_webhook(secret, raw_body, request.headers["X-Orbit-Signature"]):
    abort(403)
```

`raw_body` 必须是原始字节 —— 先反序列化再重新序列化，签名就对不上了。

## 接入约定

服务地址填写根地址，不带 `/v1`。SDK 返回响应中的 `data`；失败时保留错误码、HTTP 状态和请求 ID。普通请求默认超时 30 秒，写操作超时后先核对结果，SDK 不自动重发。

红包与转账的字段位于事件的 `data.payment`，金额为 `amount`（两位小数字符串）和 `amount_fen`（整数分）；红包报文没有金额时这些字段会缺省。详见 [回调字段](../../docs/WEBHOOK.md#payment转账与红包)。

## SSE 事件流

长连接用法与关闭方式见 [四种语言示例](../../docs/API.md#事件流)。SSE 只接收实时事件，断开后由程序重连，历史记录使用事件列表补查。
