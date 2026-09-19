# WeLink Go 客户端

只用标准库，Go 1.21 以上。

## 装

```bash
go get github.com/wechatLink/weLink/sdk/go/welink
```

或者把 `welink/` 目录拷进你的项目。

## 用

```go
wx := welink.New("key_xxx", "https://你的服务地址")

raw, err := wx.MessageText(ctx, "acc_xxx", welink.M{
    "to":      "filehelper",
    "content": "你好",
})

var apiErr *welink.Error
if errors.As(err, &apiErr) {
    log.Println(apiErr.Code, apiErr.Message, apiErr.RequestID)
}
```

返回的是 `data` 字段的原始 JSON，用 `json.Unmarshal` 解成你自己的结构体。

方法名是接口 ID 去掉点转成大驼峰：`message.text` → `MessageText`。

清单里还没有的新接口，用底层的 `Call`：

```go
raw, err := wx.Call(ctx, "POST", "/accounts/acc_xxx/some/new/route", nil, welink.M{"a": 1})
```

## 收事件

```go
if !welink.VerifyWebhook(secret, rawBody, r.Header.Get("X-Orbit-Signature")) {
    w.WriteHeader(http.StatusForbidden)
    return
}
```
