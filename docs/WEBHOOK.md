# 事件回调

微信消息推送：收到消息、好友申请、进群退群、掉线，都会推到你填的地址上。

在控制台给实例填一个地址，之后每条事件都会 `POST` 过去。
不想开公网地址，也可以轮询 `GET /v1/events`，内容一模一样。

> **仅供学习与技术交流。** 这份文档是照着一个真实服务的接口清单生成的，
> 可以拿来看一个消息平台的参数、错误、事件是怎么定下来的。
> 提供免费试用，项目长期维护。接入需要服务地址和试用授权码，
> 回 [首页](../README.md#免费试用与长期维护)通过 QQ 联系，简单说明用途即可。

## 请求头

| 头 | 类型 | 说明 |
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

签名是 `"sha256=" + HMAC-SHA256(密钥, 原始请求体)` 的小写十六进制。

**必须拿原始字节算**，不要先反序列化再重新序列化 —— 字段顺序和空格都会变。
四种语言的 SDK 里都带了现成的：

```python
from welink import verify_webhook
verify_webhook(secret, raw_body, request.headers["X-Orbit-Signature"])
```

```javascript
import { verifyWebhook } from './welink.js'
verifyWebhook(secret, rawBody, req.headers['x-orbit-signature'])
```

```go
welink.VerifyWebhook(secret, rawBody, r.Header.Get("X-Orbit-Signature"))
```

```java
WeLink.verifyWebhook(secret, rawBody, request.getHeader("X-Orbit-Signature"));
```

## 事件类型

| 类型 | 名称 | 什么时候来 |
| --- | --- | --- |
| `message.received` | 收到消息 | 收到别人发来的一条消息（结构同 `message`） |
| `message.sent` | 自己发出的消息 | 这个微信号在手机或其他设备上发出了消息，此时 self 为 true。通过本平台接口发送的消息不会推送这个事件，因为接口调用时已经同步返回了结果（结构同 `message`） |
| `message.revoked` | 消息被撤回 | 有人撤回了一条消息，此时 type 为 revoke（结构同 `message`） |
| `friend.request` | 好友申请 | 有人申请添加好友，此时 type 为 friend_request（结构同 `message`） |
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

## 消息事件的字段

```json
{
  "common": [
    {
      "name": "message_id",
      "type": "string",
      "note": "平台分配的消息 ID，下载附件、撤回消息时使用"
    },
    {
      "name": "chat_id",
      "type": "string",
      "note": "会话 ID：私聊为对方的 wxid，群聊为群 ID"
    },
    {
      "name": "is_group",
      "type": "boolean",
      "note": "是否为群消息"
    },
    {
      "name": "from",
      "type": "string",
      "note": "发送方。群消息中为群 ID，发言的成员见 sender"
    },
    {
      "name": "to",
      "type": "string",
      "note": "接收方"
    },
    {
      "name": "sender",
      "type": "string",
      "note": "消息的实际发送人。群消息中为发言成员的 wxid，私聊中与 from 相同。判断消息由谁发送时请使用 sender，不要使用 from"
    },
    {
      "name": "self",
      "type": "boolean",
      "note": "是否为本实例的微信号自己发送的消息（在手机或其他设备上发送）"
    },
    {
      "name": "type",
      "type": "string",
      "note": "消息类型，决定消息附带哪个对象"
    },
    {
      "name": "text",
      "type": "string",
      "note": "文字内容。非文字消息可能没有这个字段"
    },
    {
      "name": "mentions",
      "type": "array",
      "note": "这条消息 @ 的成员，为 wxid 数组。只有群消息才有这个字段，没有 @ 任何人时不出现。请不要从 text 中提取昵称来判断，因为通过昵称无法确定是哪个人"
    },
    {
      "name": "mentions_all",
      "type": "boolean",
      "note": "是否 @ 了所有人。这种情况下没有具体成员被 @，mentions 为空"
    },
    {
      "name": "mentions_me",
      "type": "boolean",
      "note": "本实例是否在被 @ 的成员中。实现「有人 @ 我就回复」这类功能时，直接使用这个字段"
    },
    {
      "name": "created_at / created_ts",
      "type": "string / integer",
      "note": "消息时间，分别为 ISO8601 字符串和 Unix 秒级时间戳"
    }
  ],
  "kinds": [
    {
      "kind": "text",
      "name": "文字"
    },
    {
      "kind": "image",
      "name": "图片",
      "carries": "media"
    },
    {
      "kind": "voice",
      "name": "语音",
      "carries": "media"
    },
    {
      "kind": "video",
      "name": "视频",
      "carries": "media"
    },
    {
      "kind": "file",
      "name": "文件",
      "carries": "media"
    },
    {
      "kind": "emoji",
      "name": "动图表情",
      "carries": "media"
    },
    {
      "kind": "link",
      "name": "链接卡片、公众号文章",
      "carries": "link"
    },
    {
      "kind": "miniapp",
      "name": "小程序卡片",
      "carries": "link"
    },
    {
      "kind": "card",
      "name": "名片",
      "carries": "card"
    },
    {
      "kind": "location",
      "name": "位置",
      "carries": "location"
    },
    {
      "kind": "quote",
      "name": "引用回复",
      "carries": "quoted"
    },
    {
      "kind": "friend_request",
      "name": "好友申请",
      "carries": "friend_request"
    },
    {
      "kind": "system",
      "name": "系统提示（进群、改群名、拍一拍等）",
      "carries": "system"
    },
    {
      "kind": "revoke",
      "name": "撤回通知",
      "carries": "system"
    },
    {
      "kind": "call",
      "name": "语音/视频通话结束",
      "carries": "call"
    },
    {
      "kind": "chat_record",
      "name": "转发的聊天记录",
      "carries": "chat_record"
    },
    {
      "kind": "transfer",
      "name": "转账"
    },
    {
      "kind": "channel",
      "name": "视频号"
    },
    {
      "kind": "unknown",
      "name": "
```
