# 错误码

每次调用都返回一个 `code`。`0` 是成功，其余对照下表。
`request_id` 建议一起记下来，排查时用得上。

> **仅供学习与技术交流。** 这份文档是照着一个真实服务的接口清单生成的，
> 可以拿来看一个消息平台的参数、错误、事件是怎么定下来的。
> 想动手跑一遍，还需要一个服务地址和一个授权码 —— 现在是免费的，
> 回 [首页](../README.md#怎么用起来免费)加我微信说一句用途就行。

| code | HTTP | 含义 | 文案 |
| --- | --- | --- | --- |
| `0` | 200 | OK | ok |
| `40000` | 400 | INVALID_PARAM | 参数错误 |
| `40100` | 401 | UNAUTHORIZED | API Key 无效或已禁用 |
| `40300` | 403 | FORBIDDEN | 无权访问该资源 |
| `40301` | 403 | LICENSE_EXPIRED | 授权已到期，请续期后再试 |
| `40302` | 403 | QUOTA_EXCEEDED | 账号额度不足 |
| `40303` | 403 | PENDING_REVIEW | 该账号正在等待人工审核 |
| `40304` | 403 | TRAFFIC_EXHAUSTED | 今日流量已用完 |
| `40400` | 404 | NOT_FOUND | 资源不存在 |
| `40900` | 409 | ACCOUNT_STATE_CONFLICT | 当前账号状态不允许该操作 |
| `42900` | 429 | RATE_LIMITED | 请求过于频繁，请稍后重试 |
| `50000` | 500 | INTERNAL | 服务内部错误 |
| `51000` | 502 | UPSTREAM_UNAVAILABLE | 微信服务暂时不可用，请稍后重试 |
| `51001` | 200 | UPSTREAM_ERROR | 请求微信服务失败，请稍后重试 |
| `52000` | 200 | ACCOUNT_OFFLINE | 微信已离线 |
| `52001` | 200 | RELOGIN_REQUIRED | 需要重新扫码登录 |
| `52002` | 200 | LOGIN_CANCELLED | 用户已取消扫码 |
| `52003` | 200 | QRCODE_EXPIRED | 二维码已过期，请重新获取 |
| `52004` | 200 | CAPTCHA_REQUIRED | 需要完成安全验证 |
| `52005` | 200 | SESSION_UNSTABLE | 登录环境异常，正在尝试恢复，请稍后重试 |
| `52006` | 200 | EGRESS_UNREACHABLE | 代理网络连不上，请检查后重试 |
| `52100` | 200 | CONTACT_NOT_FOUND | 未搜索到该用户 |
| `52101` | 200 | FRIEND_REQUEST_LIMITED | 添加好友过于频繁或已受限 |
| `52200` | 200 | SEND_FAILED | 消息发送失败 |
| `52201` | 200 | CONTACT_UNREACHABLE | 对方已将你删除，或该群聊不存在 |
| `52202` | 200 | GROUP_MEMBERSHIP_LOST | 你已不在该群聊中 |
| `52203` | 200 | VERIFICATION_REQUIRED | 对方需要先通过好友验证才能接收消息 |
| `52204` | 200 | CONTENT_BLOCKED | 内容被微信安全策略拦截，请调整后重试 |
| `52205` | 200 | SEND_TOO_FREQUENT | 发送过于频繁，已被微信临时限制 |
| `52300` | 200 | MOMENT_FAILED | 朋友圈操作失败 |
| `52301` | 200 | MOMENT_COOLDOWN | 账号新登录未满 24 小时，暂时只能浏览朋友圈 |
