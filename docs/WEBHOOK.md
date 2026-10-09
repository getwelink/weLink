# 事件回调

Webhook 与事件列表使用相同的报文结构。SSE 只推送连接建立后的实时事件，历史记录使用事件列表查询。

> 提供免费试用，项目长期维护。服务地址和试用授权码请通过 [首页 QQ](../README.md#免费试用与长期维护) 联系。

## 接入顺序

1. 在控制台为实例配置 Webhook 地址和密钥。
2. 使用原始请求体校验签名，再解析 JSON。
3. 以 `event_id` 去重，将任务放入队列，再尽快返回 2xx。
4. 未成功处理时保留事件，按业务要求重试；SDK 不会自动重发消息。

## 请求头

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `X-Orbit-Event` | string | 事件类型。无需解析 body 即可按类型分发处理 |
| `X-Orbit-Delivery` | string | 本次投递的 ID，重试时保持不变 |
| `X-Orbit-Timestamp` | integer | 发出时间，Unix 秒级时间戳 |
| `X-Orbit-Attempt` | integer | 第几次投递尝试，首次为 1 |
| `X-Orbit-Signature` | string | sha256=HMAC-SHA256(密钥, 原始 body)，仅在设置了密钥时才有 |

## 报文结构

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `event_id` | string | 事件 ID。重试时 ID 保持不变，可用于幂等处理 |
| `type` | string | 事件类型，见下表 |
| `account_id` | string | 事件所属的实例 ID |
| `timestamp` | integer | 事件发生时间，Unix 秒级时间戳 |
| `created_at` | string | 事件发生时间，ISO8601 格式，与 timestamp 是同一时刻 |
| `data` | object | 事件内容，结构随 type 不同而不同 |

## 验签

签名是 `"sha256=" + HMAC-SHA256(密钥, 原始请求体)` 的小写十六进制。不要先解析再重新序列化 JSON。

```python
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
```

## 事件类型

| 类型 | 名称 | 触发条件 |
| --- | --- | --- |

| `message.received` | 收到消息 | 收到别人发来的一条消息 |
| `message.sent` | 自己发出的消息 | 这个微信号在手机或其他设备上发出了消息，此时 self 为 true。通过本平台接口发送的消息不会推送这个事件，因为接口调用时已经同步返回了结果 |
| `message.revoked` | 消息被撤回 | 有人撤回了一条消息，此时 type 为 revoke |
| `friend.request` | 好友申请 | 有人申请添加好友，此时 type 为 friend_request |
| `friend.added` | 新增好友 | 对方通过了你的好友申请，或你通过了对方的好友申请 |
| `contact.updated` | 联系人变更 | 联系人的昵称、备注、头像等资料发生变化。只有资料确实有变化时才推送；你自己通过接口修改的资料不会推送 |
| `contact.deleted` | 联系人删除 | 对方删除了你，或你删除了对方。事件内容只保证包含 wxid |
| `account.scanned` | 已扫码 | 扫码登录时，二维码已被手机扫描，但还没有在手机上确认登录 |
| `account.online` | 实例上线 | 扫码登录完成，或自动恢复连接成功 |
| `account.offline` | 实例掉线 | 实例不再可用。reason 是供程序判断的固定取值，reason_text 是对应的中文说明，可以直接展示给用户。cause 表示由哪一方判定下线：wechat 表示微信通知会话已结束，detail 是微信返回的原文；proxy 表示代理网络无法连接，请求没有到达微信；platform 表示由平台决定下线，原因包括主动退出、额度到期、恢复超时 |
| `account.recovering` | 正在自动恢复 | 连接已断开，平台正在自动恢复，你无需进行任何操作。如果到 deadline_at 时仍未恢复，会转为 account.offline 事件 |
| `group.member_joined` | 群成员加入 | 有成员加入群聊 |
| `group.member_left` | 群成员退出 | 有成员主动退出群聊，或被移出群聊 |
| `group.renamed` | 群名变更 | 群名称被修改 |
| `group.invited` | 被邀请进群 | 这个微信号被邀请加入了一个群 |
| `raw.unknown` | 未识别的事件 | 平台收到了无法识别的推送。正常情况下不会出现；如果出现，说明有新的推送类型需要平台适配 |

## 消息公共字段

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `message_id` | string | 平台分配的消息 ID，下载附件、撤回消息时使用 |
| `chat_id` | string | 会话 ID：私聊为对方的 wxid，群聊为群 ID |
| `is_group` | boolean | 是否为群消息 |
| `from` | string | 发送方。群消息中为群 ID，发言的成员见 sender |
| `to` | string | 接收方 |
| `sender` | string | 消息的实际发送人。群消息中为发言成员的 wxid，私聊中与 from 相同。判断消息由谁发送时请使用 sender，不要使用 from |
| `self` | boolean | 是否为本实例的微信号自己发送的消息（在手机或其他设备上发送） |
| `type` | string | 消息类型，决定消息附带哪个对象 |
| `text` | string | 文字内容。非文字消息可能没有这个字段 |
| `mentions` | array | 这条消息 @ 的成员，为 wxid 数组。只有群消息才有这个字段，没有 @ 任何人时不出现。请不要从 text 中提取昵称来判断，因为通过昵称无法确定是哪个人 |
| `mentions_all` | boolean | 是否 @ 了所有人。这种情况下没有具体成员被 @，mentions 为空 |
| `mentions_me` | boolean | 本实例是否在被 @ 的成员中。实现「有人 @ 我就回复」这类功能时，直接使用这个字段 |
| `created_at / created_ts` | string / integer | 消息时间，分别为 ISO8601 字符串和 Unix 秒级时间戳 |

## 消息类型

| type | 名称 | 附带对象 |
| --- | --- | --- |

| `text` | 文字 | — |
| `image` | 图片 | `media` |
| `voice` | 语音 | `media` |
| `video` | 视频 | `media` |
| `file` | 文件 | `media` |
| `emoji` | 动图表情 | `media` |
| `link` | 链接卡片、公众号文章 | `link` |
| `miniapp` | 小程序卡片 | `link` |
| `card` | 名片 | `card` |
| `location` | 位置 | `location` |
| `quote` | 引用回复 | `quoted` |
| `friend_request` | 好友申请 | `friend_request` |
| `system` | 系统提示（进群、改群名、拍一拍等） | `system` |
| `revoke` | 撤回通知 | `system` |
| `call` | 语音/视频通话结束 | `call` |
| `chat_record` | 转发的聊天记录 | `chat_record` |
| `transfer` | 转账 | `payment` |
| `red_packet` | 红包 | `payment` |
| `channel` | 视频号 | — |
| `unknown` | 平台未能识别的消息 | — |

## payment：转账与红包

只展示消息报文中实际提供的信息，不查询实时支付状态。红包卡片通常不含金额，金额缺失时不会返回 0。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `title / description` | string | 卡片标题和说明 |
| `amount` | string | 金额，单位为元，保留两位小数；报文没有金额时不返回，不代表零元 |
| `amount_fen` | integer | 金额，单位为分，便于精确计算 |
| `currency` | string | 币种，报文明确为人民币时为 CNY |
| `subtype` | integer | 转账回调的类型编码；不能作为实时收款状态 |
| `memo` | string | 转账备注 |
| `payer / receiver` | string | 报文提供的付款人、收款人 wxid；未提供时省略 |
| `transaction_id / transfer_id / packet_id` | string | 交易标识、转账标识或红包标识 |
| `started_at / started_ts` | string / integer | 转账发起时间，ISO8601 和 Unix 秒 |
| `expires_at / expires_ts` | string / integer | 报文提供的有效截止时间，ISO8601 和 Unix 秒 |
| `valid_days` | integer | 报文提供的有效天数 |
| `sender_title / receiver_title` | string | 红包发送方和接收方的祝福语 |
| `sender_description / receiver_description` | string | 红包发送方和接收方的操作说明 |
| `scene` | string | 红包场景说明 |
| `packet_type` | integer | 报文提供的红包类型编码，0 也会保留 |

```json
{
  "amount": "0.01",
  "amount_fen": 1,
  "currency": "CNY",
  "subtype": 1,
  "title": "微信转账"
}
```

## media：附件

事件中既不包含文件内容，也不包含下载地址。请用 message_id 调用「下载消息附件」获取限时下载地址，拿到地址后请尽快下载。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `downloadable` | boolean | 能否下载到文件。false 表示原文件已在服务器上过期 |
| `size` | integer | 文件大小，单位为字节 |
| `filename` | string | 文件名，仅 file 类型有 |
| `duration` | integer | 时长，单位为秒，语音和视频有 |
| `width / height` | integer | 宽和高，单位为像素，图片和视频有 |

```json
{
  "downloadable": true,
  "duration": 2,
  "height": 398,
  "size": 591761,
  "width": 224
}
```

## link：链接与小程序

下表中最后三个字段只有小程序卡片才有。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `title` | string | 标题 |
| `description` | string | 摘要 |
| `url` | string | 点击后打开的地址 |
| `thumb_url` | string | 封面图 |
| `source_name` | string | 来源名称，例如公众号名称 |
| `app_id` | string | 小程序标识 |
| `username` | string | 小程序的原始 ID |
| `path` | string | 小程序页面路径 |

```json
{
  "description": "摘要",
  "source_name": "公众号名字",
  "title": "文章标题",
  "url": "https://mp.weixin.qq.com/s/..."
}
```

## location：位置

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `lat / lng` | number | 经纬度 |
| `label` | string | 完整地址 |
| `poi_name` | string | 地点名称 |

```json
{
  "label": "上海市…",
  "lat": 31.23,
  "lng": 121.47,
  "poi_name": "外滩"
}
```

## card：名片

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `wxid` | string | 名片所属用户的 wxid |
| `nickname` | string | 昵称 |
| `alias` | string | 微信号 |
| `avatar` | string | 头像 |

```json
{
  "alias": "xiaoming88",
  "nickname": "小明",
  "wxid": "wxid_x"
}
```

## chat_record：转发的聊天记录

xml 是这份聊天记录的原始内容，平台原样给出，不解析其中的条目。转发的聊天记录可以包含任意类型的消息，还可能再嵌套一层聊天记录，只解析一部分不如完全不解析。其中的图片和文件以地址和密钥的形式给出，没有 media_id，因为平台从未收到过这些消息，无法为它们提供 ID。这是整个平台唯一以这种方式提供附件的地方。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `title` | string | 卡片标题，例如「小明的聊天记录」，同时也是这条消息的 text |
| `summary` | string | 卡片上显示的预览内容 |
| `xml` | string | 聊天记录的完整内容，是一份 XML 文档 |

```json
{
  "summary": "小明: 明天十点",
  "title": "小明的聊天记录",
  "xml": "<recordinfo>…</recordinfo>"
}
```

## call：通话

通话结束后才会推送这条消息，无论是否接通都会推送。text 是聊天界面中显示的文字（例如「通话时长 04:42」「已取消」），语言与该微信号自身的语言设置一致。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `seconds` | integer | 通话时长，单位为秒。未接通的通话没有这个字段 |

```json
{
  "seconds": 282
}
```

## quoted：引用回复

被引用的原消息。回复的内容在外层的 text 中。这里只提供原消息的可读文字，原消息的 XML 结构不会出现在 text 中。需要原消息的附件时，用这里的 message_id 调用「下载消息附件」。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `message_id` | string | 原消息在平台中的 ID，可用于下载附件。平台没有收到过原消息时不返回这个字段 |
| `type` | string | 原消息的类型，取值与外层的 type 相同 |
| `sender` | string | 原消息的发送人 |
| `text` | string | 原消息的文字：文字消息为原文，卡片消息为标题，图片、视频、语音消息没有这个字段 |

```json
{
  "message_id": "msg_xxx",
  "sender": "wxid_x",
  "text": "明天十点开会？",
  "type": "text"
}
```

## friend_request：好友申请

friend_request_token 会过期，并且只对当前实例有效。同意申请时，把它原样传给「通过好友申请」接口。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `wxid` | string | 申请人的 wxid |
| `nickname` | string | 昵称 |
| `avatar` | string | 头像 |
| `greeting` | string | 验证消息 |
| `scene` | integer | 添加来源，如搜索、群聊、二维码等 |
| `friend_request_token` | string | 同意申请时需要传回的凭据 |

```json
{
  "friend_request_token": "frq_…",
  "greeting": "我是隔壁老王",
  "nickname": "老王",
  "scene": 30,
  "wxid": "wxid_x"
}
```

## system：系统提示

kind 是微信自身的提示分类，种类多且可能变化，不要把它当作固定的枚举来处理。群成员进出、修改群名等情况，平台已经单独推送了 group.* 事件，请优先使用这些事件。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `kind` | string | 提示分类 |
| `text` | string | 提示原文 |
| `actor` | string | 操作人 |
| `target` | string | 操作对象 |

```json
{
  "kind": "sysmsgtemplate",
  "text": "「小明」邀请「小林」加入了群聊"
}
```

## 轮询与 SSE

轮询示例：[Python](../examples/python/3_poll_events.py)、[Node.js](../examples/node/3-poll-events.mjs)。首次可指定 `since`，后续保留 `next_cursor`，空页时等待再查询。生产环境应持久化游标和去重状态。

SSE 的四种语言用法见 [接口文档的事件流](API.md#事件流)。断开后需要由调用方重连；SSE 不补发离线期间的事件，需要时用事件列表补查。

## 处理红包与转账

转账为 `data.type = "transfer"`，红包为 `data.type = "red_packet"`，详情在 `data.payment`。金额缺失表示原始报文没有提供，不能显示成 0 元。类型编码只描述本次消息，不代表实时收款状态。

```python
payment = event.get("data", {}).get("payment") or {}
amount_fen = payment.get("amount_fen")
if amount_fen is None:
    print("报文未提供金额")
else:
    print(payment.get("amount"), payment.get("currency", ""))
```
