# 事件回调

微信消息推送：收到消息、好友申请、进群退群、掉线，都会推到你填的地址上。

在控制台给实例填一个地址，之后每条事件都会 `POST` 过去。
不想开公网地址，也可以轮询 `GET /v1/events`，内容一模一样。

> **仅供学习与技术交流。** 这份文档是照着一个真实服务的接口清单生成的，
> 可以拿来看一个消息平台的参数、错误、事件是怎么定下来的。
> 想动手跑一遍，还需要一个服务地址和一个授权码 —— 现在是免费的，
> 回 [首页](../README.md#怎么用起来免费)加我微信说一句用途就行。

## 请求头

| 头 | 类型 | 说明 |
| --- | --- | --- |
| `X-Orbit-Event` | string | 事件类型，不解析 body 就能路由 |
| `X-Orbit-Delivery` | string | 这一次投递的 ID，重试时不变 |
| `X-Orbit-Timestamp` | integer | 发出时间，秒 |
| `X-Orbit-Attempt` | integer | 第几次尝试，首次是 1 |
| `X-Orbit-Signature` | string | sha256=HMAC-SHA256(密钥, 原始 body)，设了密钥才有 |

## 报文结构

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `event_id` | string | 事件 ID。用它做幂等：重试带同一个 ID |
| `type` | string | 事件类型，见下表 |
| `account_id` | string | 哪个实例 |
| `timestamp` | integer | 发生时间，秒 |
| `created_at` | string | 同一时刻的 ISO8601 写法 |
| `data` | object | 事件内容，随 type 变化 |

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
| `message.received` | 收到消息 | 别人发来一条消息（结构同 `message`） |
| `message.sent` | 自己发了消息 | 这个号在手机或别的设备上发了消息，self 为 true。通过本平台接口发的不会推——接口已经同步返回了结果（结构同 `message`） |
| `message.revoked` | 消息被撤回 | 有人撤回了消息，type 为 revoke（结构同 `message`） |
| `friend.request` | 好友申请 | 有人申请加好友，type 为 friend_request（结构同 `message`） |
| `friend.added` | 新增好友 | 对方通过了申请，或你通过了对方 |
| `contact.updated` | 联系人变更 | 联系人的昵称、备注、头像等变了。只有真的变了才推；你自己调接口改的不会推 |
| `contact.deleted` | 联系人删除 | 对方把你删了，或你删了对方。只保证有 wxid |
| `account.scanned` | 二维码被扫 | 扫码登录时对方扫了码，还没点确认 |
| `account.online` | 实例上线 | 扫码完成，或自动恢复成功 |
| `account.offline` | 实例掉线 | 实例不再可用。reason 是结果，cause 是谁判定的：wechat（微信自己说会话结束，detail 是它的原话）、proxy（代理网络连不上，根本没到微信）、platform（平台自己决定：主动退出、额度到期、恢复超时） |
| `account.recovering` | 正在自动恢复 | 连接断了，平台正在自己恢复。你什么都不用做，到 deadline_at 还没好会转成 account.offline |
| `group.member_joined` | 群成员加入 | 有人进群 |
| `group.member_left` | 群成员退出 | 有人退群或被移出 |
| `group.renamed` | 群名变更 | 群名被改 |
| `group.invited` | 被拉进群 | 这个号被邀请进了一个群 |
| `raw.unknown` | 未识别的推送 | 平台收到了没见过的推送。正常情况下不该出现；看到了说明有新东西要适配 |

## 消息事件的字段

```json
{
  "common": [
    {
      "name": "message_id",
      "type": "string",
      "note": "平台的消息 ID。取附件、撤回都用它"
    },
    {
      "name": "chat_id",
      "type": "string",
      "note": "会话：私聊是对方 wxid，群聊是群 ID"
    },
    {
      "name": "is_group",
      "type": "boolean",
      "note": "是不是群消息"
    },
    {
      "name": "from",
      "type": "string",
      "note": "发送方。群消息里这是群，不是说话的人"
    },
    {
      "name": "to",
      "type": "string",
      "note": "接收方"
    },
    {
      "name": "sender",
      "type": "string",
      "note": "谁说的。群里是那个成员，私聊等于 from。判断消息是谁发的要用它，不要用 from"
    },
    {
      "name": "self",
      "type": "boolean",
      "note": "这个号自己发的（在手机或别的设备上）"
    },
    {
      "name": "type",
      "type": "string",
      "note": "消息类型，决定带哪个对象"
    },
    {
      "name": "text",
      "type": "string",
      "note": "文字内容。非文字消息可能没有"
    },
    {
      "name": "mentions",
      "type": "array",
      "note": "这条消息 @ 了谁，wxid 数组。只有群消息有，没 @ 人时不出现。别从 text 里抠昵称，昵称查不到人"
    },
    {
      "name": "mentions_all",
      "type": "boolean",
      "note": "@ 了所有人。这种情况谁也没被点名，mentions 是空的"
    },
    {
      "name": "mentions_me",
      "type": "boolean",
      "note": "这个实例在被 @ 的人里面。做「有人 @ 我就回」直接用它"
    },
    {
      "name": "created_at / created_ts",
      "type": "string / integer",
      "note": "消息时间的两种写法"
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
      "name": "系统提示（进群、改群名、拍一拍…）",
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
      "name": "平台没识别出来"
    }
  ],
  "objects": [
    {
      "name": "media",
      "title": "附件",
      "note": "事件里不带文件内容也不带地址。用 message_id 调
```
