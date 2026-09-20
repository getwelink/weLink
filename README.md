<div align="center">

<img src="assets/logo.png" width="72" alt="WeLink">

# WeLink · 微信个人号 HTTP 接口

**群、主动发消息、朋友圈、通讯录** —— 官方 Bot API 不做的那些，交给代码

### 现在完全免费

90 个接口 · 16 种事件 · Python / Node.js / Go / Java 客户端

<sub>微信机器人 · 微信 API · 微信 SDK · 微信协议 · 微信个人号开发 · 微信自动回复 · 微信消息推送 · 微信群管理 · 朋友圈自动化</sub>

<sub><i>WeChat personal account HTTP API — bot, automation, message webhook, contacts, group and moments, with Python / Node.js / Go / Java SDKs.</i></sub>

</div>

---

> **先看这个：如果你只要一对一的问答机器人，别用这个，用微信官方的 iLink Bot API。**
> 2026 年微信开放了个人号 Bot 能力，免费、合规、没有封号风险，chatgpt-on-wechat
> 和 AstrBot 都已经内置，社区也有现成的多语言 SDK（比如 [wechatbot.dev](https://wechatbot.dev/)，
> 第三方项目，非官方出品）。客服问答、知识库助手这类"用户来问、机器人答"的场景，
> 官方那条就是标准答案。
>
> 官方那套**不支持群聊、不能主动发起对话**（每次回复都要带用户消息里的
> `context_token`），也没有朋友圈和通讯录。**这个项目只在那几件事上有意义。**

---

> **仅供学习与技术交流。** 可以拿来研究一个消息平台该怎么设计、SDK 该怎么生成、
> 事件回调该怎么验签。请遵守微信的用户协议与当地法律，不要用于骚扰、群发广告、
> 爬取他人隐私。用它做了什么，责任在使用的人。

---

## 目录

- [这是什么](#这是什么)
- [能做什么](#能做什么)
- [五分钟上手](#五分钟上手)
- [四种语言的微信 SDK](#四种语言的微信-sdk)
- [照着学的路径](#照着学的路径)
- [一次调用长什么样](#一次调用长什么样)
- [怎么用起来（免费）](#怎么用起来免费)

---

## 这是什么

一个微信号能做的事，WeLink 把它们做成了 HTTP 接口。

扫码把号挂上来，之后你的程序就能像用任何一个 API 一样用它：发消息、读通讯录、
建群、发朋友圈。别人发来的消息和各种变动，会实时推到你填的地址上。

没有 UI 自动化，不用一直开着一台电脑，也不用碰微信协议那一摊。你只面对 JSON。

```python
wx = WeLink(api_key="key_xxx", base_url="https://你的服务地址")
wx.message_text("acc_xxx", to="filehelper", content="你好")
```

**这个仓库里有什么**：四种语言的 SDK、能直接跑的示例、90 个接口的完整文档。

**适合谁看**：需要机器人在**群里**干活的、需要**定时或按事件主动推送**的、
要自动发朋友圈或批量维护通讯录的、要同时托管多个号的。
或者单纯想看看这类平台的接口该怎么设计。

只要一对一问答的话，上面那段说过了——官方的更合适。

---

## 能做什么

### 消息

文字、图片、视频、语音、文件、动图表情、链接卡片、小程序卡片 —— 收和发都支持。

- **文件直接传**：手里只有字节也能发 —— 程序刚生成的图片直接传上来换一个 ID，不用先挂到公网上
- **转发不用重新上传**：收到的图片和文件可以直接转给别人，走的是秒传；自己传上来的那个 ID 发过一次之后，再发也不重传
- **撤回**：自己发的能撤回，别人撤回了你也会收到事件
- **@ 谁**：群里能精确 @ 到人，收到的消息里也会告诉你被 @ 的是谁
- **引用消息**：谁引用了哪一条，能对应上
- **历史与补拉**：平台存着消息记录；服务断过的那段，可以直接从微信补回来

### 联系人

- 通讯录全量拉取、批量取详情、检测好友关系还在不在
- 搜索用户、发好友申请、通过别人的申请、改备注、删好友
- 标签的增删改查，以及给标签换一批人
- 企业微信那边的外部联系人也能拿到

### 群

- 建群、拉人、踢人、设管理员
- 改群名、发群公告、设群备注、改自己的群昵称
- 群二维码、通过链接进群、处理入群邀请
- 群成员列表与单个成员的详情
- 免打扰、置顶

### 朋友圈

- 读自己的时间线，也能读指定某个人的
- 发文字、图片、视频，转发别人的
- 点赞、取消赞、评论、删评论
- 删自己的动态，或者把它设成私密

### 实例管理

- 扫码登录，二维码状态实时可查，掉线会自动重连
- 改头像、改昵称、设置微信号、隐私设置
- 看这个号在哪些设备上登录着，并把某个设备踢下线
- 出口网络可以指定，不同的号走不同的线路

### 事件

16 种事件，发生什么推什么：

| | |
| --- | --- |
| 消息 | 收到消息、自己在别的设备发了消息、消息被撤回 |
| 好友 | 收到好友申请、新增好友、联系人资料变更、联系人被删 |
| 群 | 成员加入、成员退出、群名变更、自己被拉进群 |
| 实例 | 二维码被扫、上线、掉线、正在自动恢复 |

两种收法，内容一模一样：填一个地址让平台推过来，或者不开公网地址、自己来轮询。

---

## 五分钟上手

```bash
export WELINK_API_KEY=key_xxx
export WELINK_BASE_URL=https://你的服务地址

python examples/python/1_quickstart.py
```

这个脚本会：建一个实例 → 取登录二维码 → 等你扫 → 上线后给文件传输助手发一条。
四十行，从零到发出第一条消息。

想看收消息的，跑 [`2_echo_bot.py`](examples/python/2_echo_bot.py) —— 一个复读机，
包含回调怎么收、签名怎么验、消息怎么回。

---

## 四种语言的微信 SDK

都是**零依赖**，从接口清单生成，和服务端同源，不会对不上。

| 语言 | 位置 | 要求 |
| --- | --- | --- |
| Python | [`sdk/python`](sdk/python) | 只用标准库，3.8+ |
| Node.js | [`sdk/node`](sdk/node) | 只用内置 fetch，18+ |
| Go | [`sdk/go`](sdk/go) | 只用标准库，1.21+ |
| Java | [`sdk/java`](sdk/java) | 只用 JDK，11+（JSON 读写自带） |

每个都带签名校验函数，收事件时直接用。

<details><summary><b>Python</b></summary>

```python
from welink import WeLink

wx = WeLink(api_key="key_xxx", base_url="https://你的服务地址")

wx.message_text("acc_xxx", to="wxid_abc", content="在吗")
wx.message_image("acc_xxx", to="wxid_abc", url="https://…/cat.jpg")
wx.moment_post_text("acc_xxx", content="今天天气不错")

for wxid in wx.contact_ids("acc_xxx")["items"]:
    print(wxid)
```

</details>

<details><summary><b>Node.js</b></summary>

```javascript
import { WeLink } from './welink.js'

const wx = new WeLink({ apiKey: 'key_xxx', baseUrl: 'https://你的服务地址' })

await wx.message.text('acc_xxx', { to: 'wxid_abc', content: '在吗' })
await wx.group.create('acc_xxx', { members: ['wxid_a', 'wxid_b'] })
```

</details>

<details><summary><b>Go</b></summary>

```go
wx := welink.New("key_xxx", "https://你的服务地址")

_, err := wx.MessageText(ctx, "acc_xxx", welink.M{
    "to":      "wxid_abc",
    "content": "在吗",
})
```

</details>

<details><summary><b>Java</b></summary>

```java
WeLink wx = new WeLink("key_xxx", "https://你的服务地址");

wx.messageText("acc_xxx", Map.of("to", "wxid_abc", "content", "在吗"));
```

</details>

---

## 照着学的路径

不是按功能列的，是按"先看什么再看什么"排的：

**1. 先看一次调用长什么样** —— 下面那节，三十秒。

**2. 跑通登录** —— [`1_quickstart.py`](examples/python/1_quickstart.py)。
扫码登录这套流程（取码 → 轮询状态 → 上线）是后面所有操作的前提。

**3. 收第一条事件** —— [`2_echo_bot.py`](examples/python/2_echo_bot.py)。
重点看验签那几行，以及"先回 2xx 再干活"为什么重要。

**4. 不想开公网地址** —— [`3_poll_events.py`](examples/python/3_poll_events.py)。
游标怎么用，为什么没拉到东西时游标不能动。

**5. 查具体接口** —— [接口清单](docs/API.md)，90 个，每条都带各语言的调用写法。

**6. 事件和错误** —— [事件回调](docs/WEBHOOK.md)（16 种事件 + 验签）、
[错误码](docs/ERRORS.md)（31 个）。

**7. 读 SDK 源码** —— 生成出来的代码很平，一个方法对一个接口，底层就一个 `call`。
想知道请求是怎么拼的、错误是怎么抛的，看那一个函数就够了。

---

## 一次调用长什么样

请求：

```http
POST /v1/accounts/acc_xxx/messages/text
Authorization: Bearer key_xxx
Content-Type: application/json

{ "to": "wxid_abc", "content": "在吗" }
```

响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": { "message_id": "msg_xxx", "created_at": "2026-01-01T12:00:00+08:00" },
  "request_id": "req_xxx"
}
```

`code` 永远有，`0` 是成功。失败时 `message` 是给人看的中文，`request_id` 留着排查。

事件推过来长这样：

```json
{
  "event_id": "evt_xxx",
  "type": "message.received",
  "account_id": "acc_xxx",
  "created_at": "2026-01-01T12:00:01+08:00",
  "data": {
    "message_id": "msg_xxx",
    "chat_id": "12345@chatroom",
    "is_group": true,
    "sender": "wxid_abc",
    "type": "text",
    "text": "在吗",
    "mentions_me": true
  }
}
```

---

## 怎么用起来（免费）

**不要钱。** 现阶段完全免费，没有试用期，没有按条计费，也不用绑卡。

代码、文档、四种语言的 SDK 就在这个仓库里，直接拿走。要真正连上号跑起来，
还差一个授权码 —— **扫下面的码加我微信，说一句你打算拿它做什么**，我发给你。

问用途不是走流程，是这东西确实不该给所有人：**群发广告、批量加粉、爬别人
资料这类我不发。** 其余的基本都发 —— 自己的号想省点事、做个自动回复、接个
AI、公司内部要个工具，一两句话说清楚就行，不用写方案，也不会有人追着你推销。

<div align="center">

<img src="assets/contact-qr.png" width="200" alt="加微信领授权码">

**扫码加我微信 · 免费领授权码**

接入、用法、哪个接口该怎么调，也都可以直接问。

</div>

---

## 常见问题

**要自己研究微信协议吗？**
不用。协议那层在服务端做掉了，你这边只有 HTTP 和 JSON，四种语言的微信 SDK 直接调。

**和微信官方的 Bot API 有什么不一样？**
官方那套是一对一的：不支持群，发消息必须带 `context_token`，而这个 token 只能从对方
发来的消息里取。所以**对方从没跟你说过话，你就发不出去**——token 本身可以存下来复用，
给聊过的人定时推没问题，卡死的是陌生人那一步。这个项目能进群、能给任何联系人发、
能发朋友圈、能读写通讯录。**如果你不需要这几样，官方的更划算，没有封号风险。**

**和 itchat、wechaty 这类有什么不一样？**
那些是在你自己机器上跑一个客户端，得一直开着；这个是服务端托管的，你的程序只发
HTTP 请求，你的进程挂了不影响号在线。

**能挂几个号？**
一个 Key 下可以挂多个，每个号一个 `account_id`，互不影响。

**消息能存多久？**
默认 7 天。断线期间漏掉的，可以用同步接口从微信那边补回来。

**掉线了怎么办？**
会自动重连。真的需要重新扫码时，会推一条 `account.offline` 事件告诉你。

**Key 丢了怎么办？**
控制台里验证一次密码就能把完整的 Key 复制出来，不用重新建。

---

<div align="center">

<sub>觉得有用的话，点个 star，让更多人找得到。</sub>

<sub>仅供学习与技术交流</sub>

</div>
