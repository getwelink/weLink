# WeLink Python 客户端

只用标准库，Python 3.8 以上。

## 装

把 `welink/` 目录拷进你的项目就行，没有依赖要装。

或者从这个目录装：

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

90 个接口对应 90 个方法，名字就是接口 ID 把点换成下划线：
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
