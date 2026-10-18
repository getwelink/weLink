# WebSocket 实时推送

一个平台账号只需建立一条连接，即可接收其全部微信实例的事件，通过每条消息的 `account_id` 区分来源。新增实例自动纳入；单个实例被锁定或删除后停止推送该实例，其余实例继续接收。旧的 `/v1/accounts/{account_id}/ws` 单实例接口保留兼容。

完整教程、协议说明和四种语言可运行示例见 [WebSocket / WSS 接入示例](../examples/websocket/README.md)。

- 接口：`GET /v1/ws`，支持 wss / ws。
- 鉴权：`Authorization: Bearer <API_KEY>` 或 `X-Api-Key`，不使用 URL 凭据。
- 事件：与 Webhook 的 JSON 结构一致；连接级控制帧使用 `connected`、`pong`、`gap`、`error`。
- 心跳：协议 Ping 每 25 秒一次，75 秒无响应关闭。
- 重连：`cursor` 为最后处理成功的事件 ID，最多补齐 199 条可见历史；收到 gap 需先补拉。
- WebSocket、Webhook、SSE 可并行使用，不需要公网回调地址。

请先确认所连接网关已开放此接口，旧版本需要更新服务端后使用。
