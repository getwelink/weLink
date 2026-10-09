# 错误码

同时判断 HTTP 状态和平台 `code`。SDK 调用失败会抛出或返回各语言的错误类型，保留错误码、HTTP 状态和 `request_id`。

> 提供免费试用，项目长期维护。服务地址和试用授权码请通过 [首页 QQ](../README.md#免费试用与长期维护) 联系。

| code | HTTP | 名称 | 说明 |
| --- | --- | --- | --- |

| `0` | 200 | OK | ok |
| `40000` | 400 | INVALID_PARAM | 参数错误 |
| `40100` | 401 | UNAUTHORIZED | API Key 无效或已禁用 |
| `40300` | 403 | FORBIDDEN | 无权访问该资源 |
| `40301` | 403 | LICENSE_EXPIRED | 授权已到期，请续期后再试 |
| `40302` | 403 | QUOTA_EXCEEDED | 实例额度不足 |
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
| `52006` | 200 | EGRESS_UNREACHABLE | 无法连接代理网络，请检查后重试 |
| `52100` | 200 | CONTACT_NOT_FOUND | 未搜索到该用户 |
| `52101` | 200 | FRIEND_REQUEST_LIMITED | 添加好友过于频繁或已受限 |
| `52200` | 200 | SEND_FAILED | 消息发送失败 |
| `52201` | 200 | CONTACT_UNREACHABLE | 对方已将你删除，或该群聊不存在 |
| `52202` | 200 | GROUP_MEMBERSHIP_LOST | 你已不在该群聊中 |
| `52203` | 200 | VERIFICATION_REQUIRED | 对方需要先通过好友验证才能接收消息 |
| `52204` | 200 | CONTENT_BLOCKED | 内容被微信安全策略拦截，请调整后重试 |
| `52205` | 200 | SEND_TOO_FREQUENT | 发送过于频繁，已被微信临时限制 |
| `52300` | 200 | MOMENT_FAILED | 朋友圈操作失败 |
| `52301` | 200 | MOMENT_COOLDOWN | 账号登录未满 24 小时，暂时只能浏览朋友圈 |

## 超时与重试

SDK 的普通请求默认超时为 30 秒，不会自动重发。发送消息、添加好友等写操作超时后，服务端可能已经处理成功；先核对结果，避免重复执行。需要幂等重试时，可在自行构造的 HTTP 请求中带上同一操作的 `Idempotency-Key`，不同操作不能共用该值。

SSE 使用独立的流读取接口，生命周期由调用方管理。断开后重连并按 `event_id` 去重，需要补历史时使用事件列表。
