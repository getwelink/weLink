# 接口清单

微信个人号的 HTTP 接口：收发消息、通讯录、群、朋友圈、事件回调。
下面每一个接口，四种语言的微信 SDK 里都有对应的方法；微信协议那层不用你碰。

共 89 个接口，按用途分成 7 组。

所有请求都带 `Authorization: Bearer <你的 Key>`，路径前缀 `/v1`。
响应统一是 `{ "code": 0, "message": "ok", "data": ..., "request_id": "..." }`，
`code` 不为 0 就是失败，对照[错误码](ERRORS.md)。

> **仅供学习与技术交流。** 这份文档是照着一个真实服务的接口清单生成的，
> 可以拿来看一个消息平台的参数、错误、事件是怎么定下来的。
> 想动手跑一遍，还需要一个服务地址和一个授权码 —— 现在是免费的，
> 回 [首页](../README.md#怎么用起来免费)加我微信说一句用途就行。

## 目录

- [实例](#实例)（19）
- [联系人](#联系人)（16）
- [群](#群)（18）
- [消息](#消息)（16）
- [媒体](#媒体)（4）
- [朋友圈](#朋友圈)（13）
- [平台](#平台)（3）


## 实例

### 创建实例

```
POST /v1/accounts
```

开一个实例。占用一个额度，删除后归还。创建后还要扫码才会上线。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `platform` | 请求体 | string | 是 | 登录方式，可选值：`ipad` / `mac` |
| `name` | 请求体 | string | 否 | 备注名称，只给自己看 |
| `proxy` | 请求体 | string | 否 | 代理网络，留空则直连。两种填法：socks5 代理地址（socks5://user:pass@host:port），或者网络助手的网络ID（把网络助手装到一台手机上，打开即可看到，这台手机的网络就是这个实例的出口）—— 填网络ID时地址由平台代取，网络助手离线会被拒绝，凭据轮换后重连前会自动重取 |
| `webhook_url` | 请求体 | string | 否 | 该实例的事件推送地址 |

<details><summary>各语言怎么调</summary>

```python
wx.account_create(platform="ipad")
```

```javascript
await wx.account.create({ platform: 'ipad' })
```

</details>

### 实例列表

```
GET /v1/accounts
```

列出你的全部实例与它们的状态。

<details><summary>各语言怎么调</summary>

```python
wx.account_list()
```

```javascript
await wx.account.list()
```

</details>

### 实例详情

```
GET /v1/accounts/{account_id}
```

读一个实例。不在线时 reason 会说明原因（manual 主动退出、kicked 被别处挤下线、relogin_required 需重新扫码、recover_timeout 恢复超时、expired 授权到期）；status 为 recovering 时 recovering 里带恢复方式与放弃时间。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |

<details><summary>各语言怎么调</summary>

```python
wx.account_get("acc_xxx")
```

```javascript
await wx.account.get('acc_xxx')
```

</details>

### 获取登录二维码

```
POST /v1/accounts/{account_id}/login/qrcode
```

取一张登录二维码，用手机扫。expires_in 是这张码还剩多少秒，以返回值为准，不要写死；过期了再取一张即可。带上 proxy 可以顺便换代理网络 —— 它是开会话时定下的，换了要重开会话，所以只能在扫码这一刻换；不传则沿用原来的。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `proxy` | 请求体 | string | 否 | 改用这个代理网络：socks5 代理地址或网络助手的网络ID；传空串改为直连，不传则不动 |

<details><summary>各语言怎么调</summary>

```python
wx.account_qrcode("acc_xxx")
```

```javascript
await wx.account.qrcode('acc_xxx')
```

</details>

### 登录状态

```
GET /v1/accounts/{account_id}/login/status
```

轮询扫码进度：waiting（等待扫码）、scanned（已扫码待确认）、online（已上线）、cancelled、expired。等待扫码时还带 expires_in，是这张码此刻还剩多少秒，用它校准倒计时。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |

<details><summary>各语言怎么调</summary>

```python
wx.account_login_status("acc_xxx")
```

```javascript
await wx.account.loginStatus('acc_xxx')
```

</details>

### 取消扫码

```
POST /v1/accounts/{account_id}/login/cancel
```

放弃这次扫码。已经发出去的码会连同它背后的会话一起作废，扫了也不会让这个实例上线；实例回到未登录，重新取码即可。关闭扫码页面时调用它，别把一张还能用的码留在外面。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |

<details><summary>各语言怎么调</summary>

```python
wx.account_login_cancel("acc_xxx")
```

```javascript
await wx.account.loginCancel('acc_xxx')
```

</details>

### 提交安全验证

```
POST /v1/accounts/{account_id}/login/captcha
```

登录过程中出现安全验证时，把验证结果提交回来。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `fields` | 请求体 | object | 是 | 验证所需的字段，按提示填写 |

<details><summary>各语言怎么调</summary>

```python
wx.account_captcha("acc_xxx", fields={})
```

```javascript
await wx.account.captcha('acc_xxx', { fields: {} })
```

</details>

### 重新连接

```
POST /v1/accounts/{account_id}/reconnect
```

掉线后尝试不重新扫码就恢复连接。恢复不了才需要重新扫码。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |

<details><summary>各语言怎么调</summary>

```python
wx.account_reconnect("acc_xxx")
```

```javascript
await wx.account.reconnect('acc_xxx')
```

</details>

### 退出登录

```
POST /v1/accounts/{account_id}/logout
```

让实例下线。实例与额度保留，可以再扫码上线。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |

<details><summary>各语言怎么调</summary>

```python
wx.account_logout("acc_xxx")
```

```javascript
await wx.account.logout('acc_xxx')
```

</details>

### 删除实例

```
DELETE /v1/accounts/{account_id}
```

删除槽位并归还额度。历史消息不会立刻清除。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |

<details><summary>各语言怎么调</summary>

```python
wx.account_delete("acc_xxx")
```

```javascript
await wx.account.delete('acc_xxx')
```

</details>

### 实例资料

```
GET /v1/accounts/{account_id}/profile
```

读这个实例自己的昵称、头像、地区等资料。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |

<details><summary>各语言怎么调</summary>

```python
wx.account_profile("acc_xxx")
```

```javascript
await wx.account.profile('acc_xxx')
```

</details>

### 修改个人资料

```
PUT /v1/accounts/{account_id}/profile
```

改昵称、签名、性别与地区。字段留空就是清空该项，请把要保留的一起传。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `nickname` | 请求体 | string | 否 | 昵称 |
| `signature` | 请求体 | string | 否 | 个性签名 |
| `sex` | 请求体 | string | 否 | 1 男，2 女，0 不显示，可选值：`0` / `1` / `2` |
| `country` | 请求体 | string | 否 | 国家 |
| `province` | 请求体 | string | 否 | 省 |
| `city` | 请求体 | string | 否 | 市 |

<details><summary>各语言怎么调</summary>

```python
wx.account_update_profile("acc_xxx")
```

```javascript
await wx.account.updateProfile('acc_xxx')
```

</details>

### 设置微信号

```
PUT /v1/accounts/{account_id}/profile/alias
```

设置可被搜索的微信号。微信只允许设置一次，之后会拒绝。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `alias` | 请求体 | string | 是 | 要设置的微信号 |

<details><summary>各语言怎么调</summary>

```python
wx.account_set_alias("acc_xxx", alias="my_wx_id")
```

```javascript
await wx.account.setAlias('acc_xxx', { alias: 'my_wx_id' })
```

</details>

### 修改头像

```
PUT /v1/accounts/{account_id}/profile/avatar
```

换头像。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `url` | 请求体 | string | 是 | 公网可下载的图片地址 |

<details><summary>各语言怎么调</summary>

```python
wx.account_set_avatar("acc_xxx", url="https://example.com/a.jpg")
```

```javascript
await wx.account.setAvatar('acc_xxx', { url: 'https://example.com/a.jpg' })
```

</details>

### 我的二维码

```
GET /v1/accounts/{account_id}/profile/qrcode
```

取这个实例自己的名片二维码，返回 data URL，可直接放进 img。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |

<details><summary>各语言怎么调</summary>

```python
wx.account_qrcode_self("acc_xxx")
```

```javascript
await wx.account.qrcodeSelf('acc_xxx')
```

</details>

### 隐私设置

```
PUT /v1/accounts/{account_id}/privacy
```

开关一项隐私设置。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `option` | 请求体 | string | 是 | need_confirm_to_add 加我需验证；findable_by_phone 手机号可搜；findable_by_alias 微信号可搜；recommend_contacts 向我推荐通讯录好友；strangers_see_ten 陌生人看十条朋友圈；visible_days 朋友圈仅展示最近时段，可选值：`need_confirm_to_add` / `findable_by_phone` / `findable_by_alias` / `recommend_contacts` / `strangers_see_ten` / `visible_days` |
| `enabled` | 请求体 | boolean | 是 | 开或关 |

<details><summary>各语言怎么调</summary>

```python
wx.account_privacy("acc_xxx", option="need_confirm_to_add", enabled=true)
```

```javascript
await wx.account.privacy('acc_xxx', { option: 'need_confirm_to_add', enabled: true })
```

</details>

### 已登录设备

```
GET /v1/accounts/{account_id}/devices
```

列出这个微信号登录过的设备，本平台也在其中。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |

<details><summary>各语言怎么调</summary>

```python
wx.account_devices("acc_xxx")
```

```javascript
await wx.account.devices('acc_xxx')
```

</details>

### 下线某个设备

```
DELETE /v1/accounts/{account_id}/devices/{device_id}
```

把某个已登录设备踢下线。注意别把本平台自己踢了。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `device_id` | 路径 | string | 是 | 设备 ID，取自已登录设备列表 |

<details><summary>各语言怎么调</summary>

```python
wx.account_device_signout("acc_xxx", "...")
```

```javascript
await wx.account.deviceSignout('acc_xxx', '...')
```

</details>

### 设置 Webhook

```
PUT /v1/accounts/{account_id}/webhook
```

设置该实例事件的推送地址。每次投递都带签名，用 secret 校验。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `url` | 请求体 | string | 是 | 接收事件的地址 |
| `secret` | 请求体 | string | 否 | 签名密钥，留空则保持不变 |
| `events` | 请求体 | array | 否 | 只推这些类型，留空推全部 |

<details><summary>各语言怎么调</summary>

```python
wx.account_webhook("acc_xxx", url="https://example.com/wechat/hook")
```

```javascript
await wx.account.webhook('acc_xxx', { url: 'https://example.com/wechat/hook' })
```

</details>


## 联系人

### 通讯录标识

```
GET /v1/accounts/{account_id}/contacts
```

列出通讯录里都有谁，只给标识：好友的 wxid、群的 @chatroom、公众号的 gh_ 开头，一个不筛。要资料再用「联系人详情」按需取——一千个人里你可能只关心十个。直接向微信取，实例要在线；一页多大由微信定，翻页把 next_cursor 原样带回来，为空表示到底。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `cursor` | 查询串 | string | 否 | 上一页返回的 next_cursor，首页留空 |

<details><summary>各语言怎么调</summary>

```python
wx.contact_ids("acc_xxx")
```

```javascript
await wx.contact.ids('acc_xxx')
```

</details>

### 批量取详情

```
POST /v1/accounts/{account_id}/contacts/batch
```

按 wxid 批量取联系人资料。与「联系人详情」走的是微信的两条不同路径，字段相同，这条更适合一次问很多人。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `wxids` | 请求体 | array | 是 | 要查的 wxid 列表 |

<details><summary>各语言怎么调</summary>

```python
wx.contact_batch("acc_xxx", wxids=["wxid_example"])
```

```javascript
await wx.contact.batch('acc_xxx', { wxids: ['wxid_example'] })
```

</details>

### 联系人详情

```
POST /v1/accounts/{account_id}/contacts/detail
```

读联系人的完整资料：昵称、备注、微信号、头像、性别、地区、签名。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `wxids` | 请求体 | array | 是 | 要查的 wxid，一次最多 50 个 |

<details><summary>各语言怎么调</summary>

```python
wx.contact_detail("acc_xxx", wxids=["wxid_a"])
```

```javascript
await wx.contact.detail('acc_xxx', { wxids: ['wxid_a'] })
```

</details>

### 检测好友关系

```
POST /v1/accounts/{account_id}/contacts/check
```

查这些人是否还是好友。注意：微信对这个操作盯得很紧，查得多或查得频繁会导致实例被限制，一次最多 20 个，请按需使用。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `wxids` | 请求体 | array | 是 | 要检测的 wxid，一次最多 20 个 |

<details><summary>各语言怎么调</summary>

```python
wx.contact_check("acc_xxx", wxids=["wxid_a"])
```

```javascript
await wx.contact.check('acc_xxx', { wxids: ['wxid_a'] })
```

</details>

### 企微联系人

```
GET /v1/accounts/{account_id}/contacts/external
```

读企业微信那边的外部联系人。这些人不在普通通讯录里，「通讯录标识」拉不到他们。读的是平台存下来的那一份，先调一次同步。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |

<details><summary>各语言怎么调</summary>

```python
wx.contact_external("acc_xxx")
```

```javascript
await wx.contact.external('acc_xxx')
```

</details>

### 同步企微联系人

```
POST /v1/accounts/{account_id}/contacts/external/sync
```

去微信那边重新拉一遍企微外部联系人并存下来，返回拉到多少个。没有头像的会逐个补拉，人多时会慢一些，不建议频繁调用。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |

<details><summary>各语言怎么调</summary>

```python
wx.contact_external_sync("acc_xxx")
```

```javascript
await wx.contact.externalSync('acc_xxx')
```

</details>

### 搜索用户

```
POST /v1/accounts/{account_id}/contacts/search
```

按微信号或手机号搜人，返回一个可用于加好友的 contact_token。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `keyword` | 请求体 | string | 是 | 微信号或手机号 |

<details><summary>各语言怎么调</summary>

```python
wx.contact_search("acc_xxx", keyword="wxid_example")
```

```javascript
await wx.contact.search('acc_xxx', { keyword: 'wxid_example' })
```

</details>

### 添加好友

```
POST /v1/accounts/{account_id}/contacts/add
```

用搜索得到的 contact_token 发起好友申请。**这一条慢**：微信自己要 5～20 秒才回，实测平均 9 秒、最慢 16 秒，客户端超时请留够 30 秒。超时了不要直接重发——请求多半已经送出去了，要重试就带上 Idempotency-Key。加得太频繁会被微信限制。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `contact_token` | 请求体 | string | 是 | 搜索结果里的 contact_token |
| `greeting` | 请求体 | string | 否 | 打招呼的话 |
| `scene` | 请求体 | string | 否 | 申请来源，留空用默认值 |

<details><summary>各语言怎么调</summary>

```python
wx.contact_add("acc_xxx", contact_token="...")
```

```javascript
await wx.contact.add('acc_xxx', { contact_token: '...' })
```

</details>

### 通过好友申请

```
POST /v1/accounts/{account_id}/contacts/accept
```

同意别人的好友申请，用事件里给出的 friend_request_token。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `friend_request_token` | 请求体 | string | 是 | 好友申请事件里的 token |

<details><summary>各语言怎么调</summary>

```python
wx.contact_accept("acc_xxx", friend_request_token="...")
```

```javascript
await wx.contact.accept('acc_xxx', { friend_request_token: '...' })
```

</details>

### 设置备注

```
PUT /v1/accounts/{account_id}/contacts/{wxid}/remark
```

给一个联系人改备注名。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `wxid` | 路径 | string | 是 | 联系人的 wxid |
| `remark` | 请求体 | string | 是 | 新的备注名 |

<details><summary>各语言怎么调</summary>

```python
wx.contact_remark("acc_xxx", "...", remark="老王")
```

```javascript
await wx.contact.remark('acc_xxx', '...', { remark: '老王' })
```

</details>

### 删除好友

```
DELETE /v1/accounts/{account_id}/contacts/{wxid}
```

把人从通讯录里删掉。对方不会收到通知，但从此发不进来；要恢复得重新加。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `wxid` | 路径 | string | 是 | 要删除的 wxid |

<details><summary>各语言怎么调</summary>

```python
wx.contact_delete("acc_xxx", "...")
```

```javascript
await wx.contact.delete('acc_xxx', '...')
```

</details>

### 标签列表

```
GET /v1/accounts/{account_id}/labels
```

列出这个实例的联系人标签。标签只有自己看得见。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |

<details><summary>各语言怎么调</summary>

```python
wx.label_list("acc_xxx")
```

```javascript
await wx.label.list('acc_xxx')
```

</details>

### 新建标签

```
POST /v1/accounts/{account_id}/labels
```

新建一个联系人标签，返回它的 label_id。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `name` | 请求体 | string | 是 | 标签名 |

<details><summary>各语言怎么调</summary>

```python
wx.label_add("acc_xxx", name="重点客户")
```

```javascript
await wx.label.add('acc_xxx', { name: '重点客户' })
```

</details>

### 改标签名

```
PUT /v1/accounts/{account_id}/labels/{label_id}
```

改一个标签的名字。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `label_id` | 路径 | integer | 是 | 标签 ID |
| `name` | 请求体 | string | 是 | 新的标签名 |

<details><summary>各语言怎么调</summary>

```python
wx.label_rename("acc_xxx", "...", name="老客户")
```

```javascript
await wx.label.rename('acc_xxx', '...', { name: '老客户' })
```

</details>

### 删除标签

```
DELETE /v1/accounts/{account_id}/labels/{label_id}
```

删掉一个标签。带这个标签的联系人不受影响，只是不再带它。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `label_id` | 路径 | integer | 是 | 标签 ID |

<details><summary>各语言怎么调</summary>

```python
wx.label_delete("acc_xxx", "...")
```

```javascript
await wx.label.delete('acc_xxx', '...')
```

</details>

### 设置标签成员

```
PUT /v1/accounts/{account_id}/labels/{label_id}/members
```

设置哪些联系人带这个标签。是覆盖不是追加：没列进来的会被摘掉。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `label_id` | 路径 | integer | 是 | 标签 ID |
| `wxids` | 请求体 | array | 是 | 带这个标签的 wxid 全集 |

<details><summary>各语言怎么调</summary>

```python
wx.label_members("acc_xxx", "...", wxids=["wxid_a"])
```

```javascript
await wx.label.members('acc_xxx', '...', { wxids: ['wxid_a'] })
```

</details>


## 群

### 创建群聊

```
POST /v1/accounts/{account_id}/groups
```

拉几个好友建一个群。至少两个成员。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `members` | 请求体 | array | 是 | 初始成员的 wxid |

<details><summary>各语言怎么调</summary>

```python
wx.group_create("acc_xxx", members=["wxid_a", "wxid_b"])
```

```javascript
await wx.group.create('acc_xxx', { members: ['wxid_a', 'wxid_b'] })
```

</details>

### 群详情

```
GET /v1/accounts/{account_id}/groups/{group_id}
```

读群的名称、公告、群主等资料。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `group_id` | 路径 | string | 是 | 群 ID |

<details><summary>各语言怎么调</summary>

```python
wx.group_get("acc_xxx", "...")
```

```javascript
await wx.group.get('acc_xxx', '...')
```

</details>

### 群成员

```
GET /v1/accounts/{account_id}/groups/{group_id}/members
```

列出群成员。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `group_id` | 路径 | string | 是 | 群 ID |

<details><summary>各语言怎么调</summary>

```python
wx.group_members("acc_xxx", "...")
```

```javascript
await wx.group.members('acc_xxx', '...')
```

</details>

### 群成员详情

```
POST /v1/accounts/{account_id}/groups/{group_id}/members/detail
```

读指定几个群成员的完整资料，比群成员列表更全。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `group_id` | 路径 | string | 是 | 群 ID |
| `members` | 请求体 | array | 是 | 要查的 wxid |

<details><summary>各语言怎么调</summary>

```python
wx.group_member_detail("acc_xxx", "...", members=["wxid_a"])
```

```javascript
await wx.group.memberDetail('acc_xxx', '...', { members: ['wxid_a'] })
```

</details>

### 邀请入群

```
POST /v1/accounts/{account_id}/groups/{group_id}/invite
```

邀请好友进群。群人数多时微信会改为发邀请链接。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `group_id` | 路径 | string | 是 | 群 ID |
| `members` | 请求体 | array | 是 | 要邀请的 wxid |
| `reason` | 请求体 | string | 否 | 邀请说明 |

<details><summary>各语言怎么调</summary>

```python
wx.group_invite("acc_xxx", "...", members=["wxid_a"])
```

```javascript
await wx.group.invite('acc_xxx', '...', { members: ['wxid_a'] })
```

</details>

### 移出群成员

```
POST /v1/accounts/{account_id}/groups/{group_id}/members/remove
```

把人移出群。只有群主和管理员能做。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `group_id` | 路径 | string | 是 | 群 ID |
| `members` | 请求体 | array | 是 | 要移出的 wxid |

<details><summary>各语言怎么调</summary>

```python
wx.group_remove("acc_xxx", "...", members=["wxid_a"])
```

```javascript
await wx.group.remove('acc_xxx', '...', { members: ['wxid_a'] })
```

</details>

### 群管理员

```
POST /v1/accounts/{account_id}/groups/{group_id}/admins
```

设置或取消群管理员，也可以转让群主。只有群主能做。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `group_id` | 路径 | string | 是 | 群 ID |
| `action` | 请求体 | string | 是 | grant 设为管理员，revoke 取消，transfer 转让群主（只能一个人），可选值：`grant` / `revoke` / `transfer` |
| `members` | 请求体 | array | 是 | 目标 wxid |

<details><summary>各语言怎么调</summary>

```python
wx.group_admins("acc_xxx", "...", action="grant", members=["wxid_a"])
```

```javascript
await wx.group.admins('acc_xxx', '...', { action: 'grant', members: ['wxid_a'] })
```

</details>

### 修改群名

```
PUT /v1/accounts/{account_id}/groups/{group_id}/name
```

改群名称。需要有权限改。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `group_id` | 路径 | string | 是 | 群 ID |
| `name` | 请求体 | string | 是 | 新的群名称 |

<details><summary>各语言怎么调</summary>

```python
wx.group_rename("acc_xxx", "...", name="项目群")
```

```javascript
await wx.group.rename('acc_xxx', '...', { name: '项目群' })
```

</details>

### 设置群公告

```
PUT /v1/accounts/{account_id}/groups/{group_id}/announcement
```

改群公告。只有群主和管理员能做，会给全群发一条提示。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `group_id` | 路径 | string | 是 | 群 ID |
| `content` | 请求体 | string | 是 | 公告正文，留空表示清除 |

<details><summary>各语言怎么调</summary>

```python
wx.group_announcement("acc_xxx", "...", content="今晚八点开会")
```

```javascript
await wx.group.announcement('acc_xxx', '...', { content: '今晚八点开会' })
```

</details>

### 设置群备注

```
PUT /v1/accounts/{account_id}/groups/{group_id}/remark
```

给群起一个只有自己看得到的名字。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `group_id` | 路径 | string | 是 | 群 ID |
| `remark` | 请求体 | string | 是 | 备注名，留空表示清除 |

<details><summary>各语言怎么调</summary>

```python
wx.group_remark("acc_xxx", "...", remark="客户群 A")
```

```javascript
await wx.group.remark('acc_xxx', '...', { remark: '客户群 A' })
```

</details>

### 设置我的群昵称

```
PUT /v1/accounts/{account_id}/groups/{group_id}/nickname
```

改自己在这个群里显示的名字。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `group_id` | 路径 | string | 是 | 群 ID |
| `nickname` | 请求体 | string | 是 | 群内昵称 |

<details><summary>各语言怎么调</summary>

```python
wx.group_nickname("acc_xxx", "...", nickname="小林-客服")
```

```javascript
await wx.group.nickname('acc_xxx', '...', { nickname: '小林-客服' })
```

</details>

### 保存到通讯录

```
PUT /v1/accounts/{account_id}/groups/{group_id}/kept
```

把群保存到通讯录，或取消保存。不保存的群在会话删除后就找不回来了。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `group_id` | 路径 | string | 是 | 群 ID |
| `enabled` | 请求体 | boolean | 是 | true 保存，false 取消 |

<details><summary>各语言怎么调</summary>

```python
wx.group_kept("acc_xxx", "...", enabled=true)
```

```javascript
await wx.group.kept('acc_xxx', '...', { enabled: true })
```

</details>

### 群二维码

```
GET /v1/accounts/{account_id}/groups/{group_id}/qrcode
```

取群的邀请二维码，返回 data URL，可直接放进 img。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `group_id` | 路径 | string | 是 | 群 ID |

<details><summary>各语言怎么调</summary>

```python
wx.group_qrcode("acc_xxx", "...")
```

```javascript
await wx.group.qrcode('acc_xxx', '...')
```

</details>

### 通过链接进群

```
POST /v1/accounts/{account_id}/groups/join
```

用收到的群邀请链接进群。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `url` | 请求体 | string | 是 | 邀请链接 |

<details><summary>各语言怎么调</summary>

```python
wx.group_join("acc_xxx", url="https://support.weixin.qq.com/...")
```

```javascript
await wx.group.join('acc_xxx', { url: 'https://support.weixin.qq.com/...' })
```

</details>

### 查看群邀请

```
POST /v1/accounts/{account_id}/groups/preview
```

拿一个群邀请链接先看看是什么群，不进群。usable 为 false 时 notice 说明原因，比如链接已过期。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `url` | 请求体 | string | 是 | 邀请链接 |

<details><summary>各语言怎么调</summary>

```python
wx.group_preview("acc_xxx", url="https://weixin.qq.com/g/xxxx")
```

```javascript
await wx.group.preview('acc_xxx', { url: 'https://weixin.qq.com/g/xxxx' })
```

</details>

### 同意入群邀请

```
POST /v1/accounts/{account_id}/groups/{group_id}/approve
```

群成员邀请了人进群，群主在这里放行。四个参数都来自那条邀请事件。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `group_id` | 路径 | string | 是 | 群 ID |
| `inviter` | 请求体 | string | 是 | 邀请人的 wxid |
| `message_id` | 请求体 | string | 是 | 邀请事件里的消息 ID |
| `ticket` | 请求体 | string | 是 | 邀请事件里的凭据 |
| `members` | 请求体 | array | 是 | 被邀请人的 wxid |

<details><summary>各语言怎么调</summary>

```python
wx.group_approve("acc_xxx", "...", inviter="wxid_a", message_id="...", ticket="...", members=["wxid_b"])
```

```javascript
await wx.group.approve('acc_xxx', '...', { inviter: 'wxid_a', message_id: '...', ticket: '...', members: ['wxid_b'] })
```

</details>

### 消息免打扰

```
PUT /v1/accounts/{account_id}/chats/{chat_id}/muted
```

对一个群或一个好友开关消息免打扰。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `chat_id` | 路径 | string | 是 | 群 ID 或好友 wxid |
| `enabled` | 请求体 | boolean | 是 | true 免打扰，false 恢复提醒 |

<details><summary>各语言怎么调</summary>

```python
wx.chat_muted("acc_xxx", "...", enabled=true)
```

```javascript
await wx.chat.muted('acc_xxx', '...', { enabled: true })
```

</details>

### 聊天置顶

```
PUT /v1/accounts/{account_id}/chats/{chat_id}/pinned
```

把一个群或一个好友的会话置顶，或取消置顶。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `chat_id` | 路径 | string | 是 | 群 ID 或好友 wxid |
| `enabled` | 请求体 | boolean | 是 | true 置顶，false 取消 |

<details><summary>各语言怎么调</summary>

```python
wx.chat_pinned("acc_xxx", "...", enabled=true)
```

```javascript
await wx.chat.pinned('acc_xxx', '...', { enabled: true })
```

</details>


## 消息

### 发文字

```
POST /v1/accounts/{account_id}/messages/text
```

发一条文字消息。群里可以 @人。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `to` | 请求体 | string | 是 | 接收者：wxid、群 ID，或 filehelper（自己的文件传输助手） |
| `to_list` | 请求体 | array | 否 | 一次发给多个接收者，与 to 二选一 |
| `content` | 请求体 | string | 是 | 消息正文 |
| `mentions` | 请求体 | array | 否 | 要 @ 的 wxid，只在群里有意义 |

<details><summary>各语言怎么调</summary>

```python
wx.message_text("acc_xxx", to="filehelper", content="你好")
```

```javascript
await wx.message.text('acc_xxx', { to: 'filehelper', content: '你好' })
```

</details>

### 发图片

```
POST /v1/accounts/{account_id}/messages/image
```

发一张图片。url 与 media_id 二选一，media_id 可以复用平台已存的文件。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `to` | 请求体 | string | 是 | 接收者：wxid、群 ID，或 filehelper（自己的文件传输助手） |
| `url` | 请求体 | string | 否 | 公网可下载的地址 |
| `media_id` | 请求体 | string | 否 | 平台里已有的媒体 ID |
| `use_cache` | 请求体 | boolean | 否 | 同一个 url 之前发过就直接转发，不重新上传。默认 true；地址没变但内容换了才需要传 false |

<details><summary>各语言怎么调</summary>

```python
wx.message_image("acc_xxx", to="filehelper")
```

```javascript
await wx.message.image('acc_xxx', { to: 'filehelper' })
```

</details>

### 发视频

```
POST /v1/accounts/{account_id}/messages/video
```

发一段视频。不填时长的话由平台估算，有些客户端会显示得不好看。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `to` | 请求体 | string | 是 | 接收者：wxid、群 ID，或 filehelper（自己的文件传输助手） |
| `url` | 请求体 | string | 否 | 公网可下载的地址 |
| `media_id` | 请求体 | string | 否 | 平台里已有的媒体 ID |
| `use_cache` | 请求体 | boolean | 否 | 同一个 url 之前发过就直接转发，不重新上传。默认 true；地址没变但内容换了才需要传 false |
| `duration` | 请求体 | integer | 否 | 时长（秒） |
| `width` | 请求体 | integer | 否 | 画面宽，不传则动态里不带尺寸 |
| `height` | 请求体 | integer | 否 | 画面高，不传则动态里不带尺寸 |
| `thumbnail_url` | 请求体 | string | 否 | 封面图地址，公网可下载的一张图片 |

<details><summary>各语言怎么调</summary>

```python
wx.message_video("acc_xxx", to="filehelper")
```

```javascript
await wx.message.video('acc_xxx', { to: 'filehelper' })
```

</details>

### 发语音

```
POST /v1/accounts/{account_id}/messages/voice
```

发一条语音。seconds 是时长，显示在气泡上。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `to` | 请求体 | string | 是 | 接收者：wxid、群 ID，或 filehelper（自己的文件传输助手） |
| `url` | 请求体 | string | 是 | 公网可下载的音频地址 |
| `seconds` | 请求体 | integer | 否 | 时长（秒） |

<details><summary>各语言怎么调</summary>

```python
wx.message_voice("acc_xxx", to="filehelper", url="https://example.com/a.silk")
```

```javascript
await wx.message.voice('acc_xxx', { to: 'filehelper', url: 'https://example.com/a.silk' })
```

</details>

### 发文件

```
POST /v1/accounts/{account_id}/messages/file
```

发一个文件。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `to` | 请求体 | string | 是 | 接收者：wxid、群 ID，或 filehelper（自己的文件传输助手） |
| `url` | 请求体 | string | 否 | 公网可下载的地址 |
| `media_id` | 请求体 | string | 否 | 平台里已有的媒体 ID |
| `use_cache` | 请求体 | boolean | 否 | 同一个 url 之前发过就直接转发，不重新上传。默认 true；地址没变但内容换了才需要传 false |
| `filename` | 请求体 | string | 否 | 对方看到的文件名 |

<details><summary>各语言怎么调</summary>

```python
wx.message_file("acc_xxx", to="filehelper")
```

```javascript
await wx.message.file('acc_xxx', { to: 'filehelper' })
```

</details>

### 发动图表情

```
POST /v1/accounts/{account_id}/messages/sticker
```

转发一个动图表情。表情是引用不是上传：checksum 与 length 来自收到的那条表情消息。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `to` | 请求体 | string | 是 | 接收者：wxid、群 ID，或 filehelper（自己的文件传输助手） |
| `checksum` | 请求体 | string | 是 | 表情的校验值，来自收到的表情消息 |
| `length` | 请求体 | integer | 是 | 表情的字节数，来自同一条消息 |

<details><summary>各语言怎么调</summary>

```python
wx.message_sticker("acc_xxx", to="filehelper", checksum="...", length=0)
```

```javascript
await wx.message.sticker('acc_xxx', { to: 'filehelper', checksum: '...', length: 0 })
```

</details>

### 发链接卡片

```
POST /v1/accounts/{account_id}/messages/link
```

发一张可点击的链接卡片。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `to` | 请求体 | string | 是 | 接收者：wxid、群 ID，或 filehelper（自己的文件传输助手） |
| `title` | 请求体 | string | 是 | 卡片标题 |
| `description` | 请求体 | string | 否 | 卡片摘要 |
| `url` | 请求体 | string | 是 | 点击后打开的地址 |
| `thumb_url` | 请求体 | string | 否 | 封面图地址 |
| `source_name` | 请求体 | string | 否 | 来源名称 |

<details><summary>各语言怎么调</summary>

```python
wx.message_link("acc_xxx", to="filehelper", title="标题", url="https://example.com")
```

```javascript
await wx.message.link('acc_xxx', { to: 'filehelper', title: '标题', url: 'https://example.com' })
```

</details>

### 发小程序卡片

```
POST /v1/accounts/{account_id}/messages/miniapp
```

发一张小程序卡片。需要小程序自己的标识。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `to` | 请求体 | string | 是 | 接收者：wxid、群 ID，或 filehelper（自己的文件传输助手） |
| `app_id` | 请求体 | string | 是 | 小程序的公开标识 |
| `username` | 请求体 | string | 是 | 小程序的原始 ID |
| `title` | 请求体 | string | 是 | 卡片标题 |
| `description` | 请求体 | string | 否 | 卡片摘要 |
| `path` | 请求体 | string | 否 | 打开的页面路径 |
| `thumb_url` | 请求体 | string | 否 | 封面图地址 |
| `source_name` | 请求体 | string | 否 | 来源名称 |

<details><summary>各语言怎么调</summary>

```python
wx.message_miniapp("acc_xxx", to="filehelper", app_id="...", username="...", title="标题")
```

```javascript
await wx.message.miniapp('acc_xxx', { to: 'filehelper', app_id: '...', username: '...', title: '标题' })
```

</details>

### 转发消息

```
POST /v1/accounts/{account_id}/messages/forward
```

把收到过的一条消息原样转给别人。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `to` | 请求体 | string | 是 | 接收者：wxid、群 ID，或 filehelper（自己的文件传输助手） |
| `message_id` | 请求体 | string | 是 | 要转发的消息 ID |

<details><summary>各语言怎么调</summary>

```python
wx.message_forward("acc_xxx", to="filehelper", message_id="...")
```

```javascript
await wx.message.forward('acc_xxx', { to: 'filehelper', message_id: '...' })
```

</details>

### 撤回消息

```
POST /v1/accounts/{account_id}/messages/{message_id}/recall
```

撤回自己发出的一条消息。微信只允许发出后约两分钟内撤回，超时会被拒绝。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `message_id` | 路径 | string | 是 | 发送时返回的 message_id |

<details><summary>各语言怎么调</summary>

```python
wx.message_recall("acc_xxx", "...")
```

```javascript
await wx.message.recall('acc_xxx', '...')
```

</details>

### 消息记录

```
GET /v1/accounts/{account_id}/messages
```

读平台保存的消息记录，可按会话筛选。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `peer` | 查询串 | string | 否 | 只看与某个 wxid 或群的会话 |
| `cursor` | 查询串 | string | 否 | 上一页返回的 next_cursor，首页留空 |
| `limit` | 查询串 | integer | 否 | 每页条数，最多 200，超过按 200 处理 |

<details><summary>各语言怎么调</summary>

```python
wx.message_history("acc_xxx")
```

```javascript
await wx.message.history('acc_xxx')
```

</details>

### 同步消息

```
POST /v1/accounts/{account_id}/messages/sync
```

主动拉取这个实例收到的消息，内容和 Webhook 推的完全一样。没配 Webhook、Webhook 断过、或者服务重启过，用它把这段时间的消息补回来。cursor 留空从最早还留着的消息开始（大约一天），之后每次带上一次返回的 next_cursor；has_more 为 true 说明还没拉完，立刻再调一次。没有新消息时 next_cursor 原样返回，游标不动。拉到的消息不入库、不触发 Webhook，重复拉不会有副作用。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `cursor` | 请求体 | string | 否 | 上一次返回的 next_cursor，第一次留空 |

<details><summary>各语言怎么调</summary>

```python
wx.message_sync("acc_xxx")
```

```javascript
await wx.message.sync('acc_xxx')
```

</details>

### 消息详情

```
GET /v1/accounts/{account_id}/messages/{message_id}
```

读一条消息。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `message_id` | 路径 | string | 是 | 消息 ID |

<details><summary>各语言怎么调</summary>

```python
wx.message_get("acc_xxx", "...")
```

```javascript
await wx.message.get('acc_xxx', '...')
```

</details>

### 收藏列表

```
GET /v1/accounts/{account_id}/favorites
```

列出这个实例收藏的内容。cursor 留空从头读，返回的 next_cursor 为空表示到底。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `cursor` | 查询串 | string | 否 | 上一页返回的 next_cursor，首页留空 |

<details><summary>各语言怎么调</summary>

```python
wx.favorite_list("acc_xxx")
```

```javascript
await wx.favorite.list('acc_xxx')
```

</details>

### 收藏详情

```
GET /v1/accounts/{account_id}/favorites/{fav_id}
```

读一条收藏的完整内容。内容是微信自己的 XML，不同类型结构不同，原样返回。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `fav_id` | 路径 | integer | 是 | 收藏 ID，来自收藏列表 |

<details><summary>各语言怎么调</summary>

```python
wx.favorite_get("acc_xxx", "...")
```

```javascript
await wx.favorite.get('acc_xxx', '...')
```

</details>

### 删除收藏

```
DELETE /v1/accounts/{account_id}/favorites/{fav_id}
```

删掉一条收藏。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `fav_id` | 路径 | integer | 是 | 收藏 ID |

<details><summary>各语言怎么调</summary>

```python
wx.favorite_delete("acc_xxx", "...")
```

```javascript
await wx.favorite.delete('acc_xxx', '...')
```

</details>


## 媒体

### 下载消息附件

```
POST /v1/accounts/{account_id}/media/download
```

取一条消息里的图片、视频、文件或语音，返回一个限时下载地址。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `message_id` | 请求体 | string | 是 | 带附件的消息 ID |

<details><summary>各语言怎么调</summary>

```python
wx.media_from_message("acc_xxx", message_id="...")
```

```javascript
await wx.media.fromMessage('acc_xxx', { message_id: '...' })
```

</details>

### 查文件是否已缓存

```
POST /v1/accounts/{account_id}/media/cached
```

问一个地址平台是否已经发过。发过就能直接转发，不用重新上传，也不算流量 —— 在你把文件准备好挂到公网之前先问一句，省的就是这一趟。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `url` | 请求体 | string | 是 | 要发的那个地址，和发送时填的一模一样才算命中 |
| `kind` | 请求体 | string | 是 | image、video 或 file |

<details><summary>各语言怎么调</summary>

```python
wx.media_cached("acc_xxx", url="https://example.com/a.jpg", kind="image")
```

```javascript
await wx.media.cached('acc_xxx', { url: 'https://example.com/a.jpg', kind: 'image' })
```

</details>

### 重新取下载地址

```
GET /v1/accounts/{account_id}/media/{media_id}
```

为已经下载过的文件换一个新的限时地址。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `media_id` | 路径 | string | 是 | 媒体 ID |

<details><summary>各语言怎么调</summary>

```python
wx.media_get("acc_xxx", "...")
```

```javascript
await wx.media.get('acc_xxx', '...')
```

</details>

### 下载动态媒体

```
POST /v1/accounts/{account_id}/moments/{moment_id}/media/download
```

取一条朋友圈动态里的第 N 张图，或它的视频。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `moment_id` | 路径 | string | 是 | 动态 ID |
| `index` | 请求体 | integer | 否 | 第几张图，从 0 开始；视频动态忽略这个值 |

<details><summary>各语言怎么调</summary>

```python
wx.media_moment("acc_xxx", "...")
```

```javascript
await wx.media.moment('acc_xxx', '...')
```

</details>


## 朋友圈

### 我的朋友圈

```
GET /v1/accounts/{account_id}/moments
```

读自己看到的朋友圈时间线。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `cursor` | 查询串 | string | 否 | 上一页返回的 next_cursor |

<details><summary>各语言怎么调</summary>

```python
wx.moment_timeline("acc_xxx")
```

```javascript
await wx.moment.timeline('acc_xxx')
```

</details>

### 朋友圈详情

```
GET /v1/accounts/{account_id}/moments/{moment_id}
```

读一条朋友圈。列表会截断点赞与评论，这里是完整的。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `moment_id` | 路径 | string | 是 | 朋友圈 ID |

<details><summary>各语言怎么调</summary>

```python
wx.moment_get("acc_xxx", "...")
```

```javascript
await wx.moment.get('acc_xxx', '...')
```

</details>

### 某人的朋友圈

```
GET /v1/accounts/{account_id}/moments/user/{wxid}
```

读某个联系人的朋友圈主页。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `wxid` | 路径 | string | 是 | 联系人的 wxid |
| `cursor` | 查询串 | string | 否 | 上一页返回的 next_cursor |

<details><summary>各语言怎么调</summary>

```python
wx.moment_user("acc_xxx", "...")
```

```javascript
await wx.moment.user('acc_xxx', '...')
```

</details>

### 发文字动态

```
POST /v1/accounts/{account_id}/moments/text
```

发一条纯文字朋友圈。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `content` | 请求体 | string | 是 | 正文 |
| `mentions` | 请求体 | array | 否 | 要 @ 的 wxid |
| `visibility` | 请求体 | object | 否 | 可见范围：mode 为 public / private / allow / deny，后两种要给 wxids 或 tag_ids |

<details><summary>各语言怎么调</summary>

```python
wx.moment_post_text("acc_xxx", content="今天天气不错")
```

```javascript
await wx.moment.postText('acc_xxx', { content: '今天天气不错' })
```

</details>

### 发图片动态

```
POST /v1/accounts/{account_id}/moments/images
```

发一条带图的朋友圈。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `content` | 请求体 | string | 否 | 正文 |
| `images` | 请求体 | array | 是 | 图片列表 |
| `visibility` | 请求体 | object | 否 | 可见范围：mode 为 public / private / allow / deny，后两种要给 wxids 或 tag_ids |

<details><summary>各语言怎么调</summary>

```python
wx.moment_post_images("acc_xxx", images=[{"url": "https://example.com/a.jpg"}])
```

```javascript
await wx.moment.postImages('acc_xxx', { images: [{'url': 'https://example.com/a.jpg'}] })
```

</details>

### 发视频动态

```
POST /v1/accounts/{account_id}/moments/video
```

发一条视频朋友圈。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `content` | 请求体 | string | 否 | 正文 |
| `video` | 请求体 | object | 是 | 视频，给一个公网可下载的地址 |
| `cover` | 请求体 | object | 否 | 封面图 |
| `duration` | 请求体 | integer | 否 | 时长（秒） |
| `visibility` | 请求体 | object | 否 | 可见范围：mode 为 public / private / allow / deny，后两种要给 wxids 或 tag_ids |

<details><summary>各语言怎么调</summary>

```python
wx.moment_post_video("acc_xxx", video={"url": ""})
```

```javascript
await wx.moment.postVideo('acc_xxx', { video: {'url': ''} })
```

</details>

### 转发动态

```
POST /v1/accounts/{account_id}/moments/forward
```

把看到的一条动态原样再发一遍。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `moment_id` | 请求体 | string | 是 | 要转发的动态 ID |
| `visibility` | 请求体 | object | 否 | 可见范围：mode 为 public / private / allow / deny，后两种要给 wxids 或 tag_ids |

<details><summary>各语言怎么调</summary>

```python
wx.moment_repost("acc_xxx", moment_id="...")
```

```javascript
await wx.moment.repost('acc_xxx', { moment_id: '...' })
```

</details>

### 点赞

```
POST /v1/accounts/{account_id}/moments/{moment_id}/like
```

给一条动态点赞。动态要先读过一次，24 小时内有效。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `moment_id` | 路径 | string | 是 | 动态 ID |

<details><summary>各语言怎么调</summary>

```python
wx.moment_like("acc_xxx", "...")
```

```javascript
await wx.moment.like('acc_xxx', '...')
```

</details>

### 取消赞

```
DELETE /v1/accounts/{account_id}/moments/{moment_id}/like
```

取消对一条动态的赞。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `moment_id` | 路径 | string | 是 | 动态 ID |

<details><summary>各语言怎么调</summary>

```python
wx.moment_unlike("acc_xxx", "...")
```

```javascript
await wx.moment.unlike('acc_xxx', '...')
```

</details>

### 评论

```
POST /v1/accounts/{account_id}/moments/{moment_id}/comments
```

评论一条动态，或回复别人的评论。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `moment_id` | 路径 | string | 是 | 动态 ID |
| `content` | 请求体 | string | 是 | 评论内容，最多 500 字 |
| `reply_to` | 请求体 | integer | 否 | 要回复的评论 ID，留空为一级评论 |

<details><summary>各语言怎么调</summary>

```python
wx.moment_comment("acc_xxx", "...", content="说得好")
```

```javascript
await wx.moment.comment('acc_xxx', '...', { content: '说得好' })
```

</details>

### 删除评论

```
DELETE /v1/accounts/{account_id}/moments/{moment_id}/comments/{comment_id}
```

删掉自己发的一条评论。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `moment_id` | 路径 | string | 是 | 动态 ID |
| `comment_id` | 路径 | integer | 是 | 评论 ID |

<details><summary>各语言怎么调</summary>

```python
wx.moment_delete_comment("acc_xxx", "...", "...")
```

```javascript
await wx.moment.deleteComment('acc_xxx', '...', '...')
```

</details>

### 删除动态

```
DELETE /v1/accounts/{account_id}/moments/{moment_id}
```

删掉自己发的一条朋友圈。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `moment_id` | 路径 | string | 是 | 动态 ID |

<details><summary>各语言怎么调</summary>

```python
wx.moment_delete("acc_xxx", "...")
```

```javascript
await wx.moment.delete('acc_xxx', '...')
```

</details>

### 设为私密 / 公开

```
PUT /v1/accounts/{account_id}/moments/{moment_id}/privacy
```

把自己的一条动态设为仅自己可见，或改回公开。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `moment_id` | 路径 | string | 是 | 动态 ID |
| `private` | 请求体 | boolean | 是 | true 为仅自己可见 |

<details><summary>各语言怎么调</summary>

```python
wx.moment_privacy("acc_xxx", "...", private=true)
```

```javascript
await wx.moment.privacy('acc_xxx', '...', { private: true })
```

</details>


## 平台

### 当前用户

```
GET /v1/me
```

读这个 Key 属于谁，以及实例数量。

<details><summary>各语言怎么调</summary>

```python
wx.platform_me()
```

```javascript
await wx.platform.me()
```

</details>

### 事件列表

```
GET /v1/events
```

读平台记录的事件，可按实例、类型、消息类型与时间筛选。没配 Webhook 时可以轮询这里。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 查询串 | string | 否 | 只看某个实例 |
| `type` | 查询串 | string | 否 | 只看某种事件，可重复 |
| `message_type` | 查询串 | string | 否 | 只看某种消息，如 text、image、file，可重复；非消息事件不会命中 |
| `since` | 查询串 | string | 否 | 只看这个时间之后的，RFC3339 或 Unix 秒 |
| `until` | 查询串 | string | 否 | 只看这个时间之前的，RFC3339 或 Unix 秒 |
| `keyword` | 查询串 | string | 否 | 按事件内容搜索。需要同时给时间范围，且不超过 1 小时 |
| `order` | 查询串 | string | 否 | oldest 从头逐条读（默认），newest 先看最近发生的，可选值：`oldest` / `newest` |
| `cursor` | 查询串 | string | 否 | 上一页返回的 next_cursor，首页留空 |
| `limit` | 查询串 | integer | 否 | 每页条数，最多 200，超过按 200 处理 |

<details><summary>各语言怎么调</summary>

```python
wx.platform_events()
```

```javascript
await wx.platform.events()
```

</details>

### 事件流

```
GET /v1/accounts/{account_id}/stream
```

以 SSE 长连接实时接收该实例的事件，内容与 Webhook 相同。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |

<details><summary>各语言怎么调</summary>

```python
wx.platform_stream("acc_xxx")
```

```javascript
await wx.platform.stream('acc_xxx')
```

</details>

