# WeLink Go 客户端

只用标准库，Go 1.21 以上。

## 装

```bash
go get github.com/getwelink/weLink/sdk/go/welink@main
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

## 接入约定

服务地址填写根地址，不带 `/v1`。SDK 返回响应中的 `data`；失败时保留错误码、HTTP 状态和请求 ID。普通请求默认超时 30 秒，写操作超时后先核对结果，SDK 不自动重发。

红包与转账的字段位于事件的 `data.payment`，金额为 `amount`（两位小数字符串）和 `amount_fen`（整数分）；红包报文没有金额时这些字段会缺省。详见 [回调字段](../../docs/WEBHOOK.md#payment转账与红包)。

## SSE 事件流

长连接用法与关闭方式见 [四种语言示例](../../docs/API.md#事件流)。SSE 只接收实时事件，断开后由程序重连，历史记录使用事件列表补查。
