# 接口清单

微信个人号的 HTTP 接口：收发消息、通讯录、群、朋友圈、事件回调。
下面每一个接口，四种语言的微信 SDK 里都有对应的方法；微信协议那层不用你碰。

共 88 个接口，按用途分成 7 组。

所有请求都带 `Authorization: Bearer <你的 Key>`，路径前缀 `/v1`。
响应统一是 `{ "code": 0, "message": "ok", "data": ..., "request_id": "..." }`，
`code` 不为 0 就是失败，对照[错误码](ERRORS.md)。

> **仅供学习与技术交流。** 这份文档是照着一个真实服务的接口清单生成的，
> 可以拿来看一个消息平台的参数、错误、事件是怎么定下来的。
> 想动手跑一遍，还需要一个服务地址和一个授权码 —— 现在是免费的，
> 回 [首页](../README.md#怎么用起来免费)加我微信说一句用途就行。

有些接口的说明旁边多一句提醒，两种：

- **注意（易封号）**：调用不当容易被微信限制甚至封号，比如加好友、通过好友申请。照着提醒控制频率。
- **建议缓存**：每调一次都是现去微信取，平台这边不留副本，而答案又很少变——通讯录、群成员、个人资料这类。取一次存到你自己那边，收到对应事件再更新；别每来一条消息就调一次，慢，也容易被当成脚本。

## 目录

- [实例](#实例)（17）
- [联系人](#联系人)（16）
- [群](#群)（18）
- [消息](#消息)（16）
- [媒体](#媒体)（5）
- [朋友圈](#朋友圈)（13）
- [平台](#平台)（3）


## 实例

### 创建实例

```
POST /v1/accounts
```

创建一个实例。每个实例占用一个额度，删除实例后额度归还。实例创建后需要扫码登录才会上线。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `platform` | 请求体 | string | 否 | 登录方式，留空时使用默认方式。并非每个部署都同时开通了两种方式。选择未开通的方式会直接报错，错误信息中会列出可选的方式，可选值：`ipad` / `mac` |
| `name` | 请求体 | string | 否 | 备注名称，仅自己可见 |
| `proxy` | 请求体 | string | 是 | 代理网络，必填，不能直连。有两种填法：socks5 代理地址，如 socks5://user:pass@host:port；网络助手的网络ID，在一台手机上安装并打开网络助手即可看到，实例将通过这台手机的网络连接微信。填网络ID时，代理地址由平台自动获取。网络助手不在线时，请求会被拒绝。网络助手的凭据更新后，平台会在重新连接前自动获取新的代理地址 |
| `webhook_url` | 请求体 | string | 否 | 接收该实例事件的 Webhook 地址 |
| `keep_history` | 请求体 | boolean | 否 | 是否保存收发的消息和推送记录，默认 true。设为 false 时，消息不写入数据库，推送记录在投递结束后立即删除。图片等文件仍可下载，撤回功能仍可使用；但无法查询历史消息，也无法转发文字和卡片消息 |

<details><summary>各语言怎么调</summary>

```python
wx.account_create(proxy="socks5://user:pass@host:port")
```

```javascript
await wx.account.create({ proxy: 'socks5://user:pass@host:port' })
```

</details>

### 实例列表

```
GET /v1/accounts
```

列出你的全部实例及其状态。

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

查询一个实例的详情。实例不在线时，reason 字段说明原因：manual 表示主动退出，kicked 表示因其他设备登录而被挤下线，relogin_required 表示需要重新扫码登录，recover_timeout 表示自动恢复超时，expired 表示授权到期。status 为 recovering 时，recovering 字段给出恢复方式和放弃恢复的时间。

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

获取一张登录二维码，用手机微信扫码登录。expires_in 是二维码剩余的有效秒数，请以返回值为准，不要写死。二维码过期后重新获取即可。请求时可以带上 proxy 来更换代理网络。代理网络在建立登录会话时确定，更换后需要重新建立会话，所以只能在获取二维码时更换。不传 proxy 则沿用原来的代理网络。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `proxy` | 请求体 | string | 否 | 要改用的代理网络，可以是socks5 地址或网络助手的网络ID。不传则沿用实例现有的代理网络。不能传空字符串，因为实例必须配置代理网络，不允许直连 |

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

查询扫码登录的进度，供轮询使用。状态取值：waiting（等待扫码）、scanned（已扫码，等待确认）、verify（等待验证）、online（已上线）、cancelled（已取消）、expired（已过期）。状态为 waiting 时还会返回 expires_in，表示二维码此刻剩余的有效秒数，可用于校准倒计时。返回 notice 时，请把它原样展示给用户。Mac 端扫码后需要通过一次新设备验证，平台会自动完成这一步，这期间状态会一直保持为 scanned。请提示用户耐心等待，避免用户误以为登录卡住而取消登录。

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

### 重新连接

```
POST /v1/accounts/{account_id}/reconnect
```

实例掉线后，尝试在不重新扫码的情况下恢复连接。无法恢复时，才需要重新扫码登录。

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

让实例下线。实例和额度都会保留，之后可以重新扫码上线。

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

删除实例并归还额度。历史消息不会立即清除。在线的实例不能直接删除，请先调用「退出登录」。如果直接删除，微信端的登录会话会继续保持，而平台已经无法再关闭它。

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

查询这个实例自己的昵称、头像、地区等资料。

> **建议缓存**：资料很少变化，登录成功后获取一次并保存即可。平时需要 wxid、昵称、头像时，请读取「实例详情」中的 profile 字段。该字段来自平台已保存的数据，不会向微信发起请求。

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

修改昵称、签名、性别和地区。留空的字段会被清空，请把需要保留的字段一并传入。

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

### 修改头像

```
PUT /v1/accounts/{account_id}/profile/avatar
```

修改头像。

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

获取这个实例自己的名片二维码。返回 data URL，可直接用作 img 标签的 src。

> **建议缓存**：名片二维码基本不会变化。获取一次后保存为图片重复使用，不要每次展示时都重新获取。

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

开启或关闭一项隐私设置。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `option` | 请求体 | string | 是 | need_confirm_to_add：加我为好友时需要验证；findable_by_phone：可以通过手机号搜到我；findable_by_alias：可以通过微信号搜到我；recommend_contacts：向我推荐通讯录好友；strangers_see_ten：允许陌生人查看十条朋友圈；visible_days：朋友圈只展示最近一段时间的内容，可选值：`need_confirm_to_add` / `findable_by_phone` / `findable_by_alias` / `recommend_contacts` / `strangers_see_ten` / `visible_days` |
| `enabled` | 请求体 | boolean | 是 | true 开启，false 关闭 |

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

列出这个微信号登录过的设备，其中包括本平台。

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

让某个已登录的设备强制下线。注意不要把本平台自己也下线。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `device_id` | 路径 | string | 是 | 设备 ID，从「已登录设备」接口获取 |

<details><summary>各语言怎么调</summary>

```python
wx.account_device_signout("acc_xxx", "...")
```

```javascript
await wx.account.deviceSignout('acc_xxx', '...')
```

</details>

### 消息保存设置

```
PUT /v1/accounts/{account_id}/history
```

设置这个实例是否保存收发的消息和推送记录。关闭后，新收发的消息不写入数据库，Webhook 推送记录在投递成功或放弃重试后立即删除。图片、语音、视频、文件仍然可以下载，自己发的消息仍然可以撤回，重复的推送仍然会去重。但无法查询历史消息，也无法转发文字和卡片消息。事件仍然会保存。关闭前已保存的消息不会立即删除，会按原来的保存期限自动清理。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `keep` | 请求体 | boolean | 是 | true 保存，false 不保存 |

<details><summary>各语言怎么调</summary>

```python
wx.account_history("acc_xxx", keep=false)
```

```javascript
await wx.account.history('acc_xxx', { keep: false })
```

</details>

### 设置 Webhook

```
PUT /v1/accounts/{account_id}/webhook
```

设置接收该实例事件的 Webhook 地址。每次推送都带有签名，可以用 secret 校验。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `url` | 请求体 | string | 是 | 接收事件的地址 |
| `secret` | 请求体 | string | 否 | 签名密钥，留空则保持不变 |
| `events` | 请求体 | array | 否 | 只推送这些类型的事件，留空则推送全部事件 |

<details><summary>各语言怎么调</summary>

```python
wx.account_webhook("acc_xxx", url="https://example.com/wechat/hook")
```

```javascript
await wx.account.webhook('acc_xxx', { url: 'https://example.com/wechat/hook' })
```

</details>


## 联系人

### 通讯录列表

```
GET /v1/accounts/{account_id}/contacts
```

列出通讯录中的全部条目，不做任何筛选，只返回标识：好友为 wxid，群 ID 以 @chatroom 结尾，公众号以 gh_ 开头。需要资料时，再用「联系人详情」按需查询。数据直接从微信获取，实例需要在线。每页条数由微信决定。翻页时把 next_cursor 原样传回，next_cursor 为空表示已经到最后一页。

> **建议缓存**：登录成功后拉一次完整列表，保存在你自己的系统中，之后根据事件更新：收到 friend.added 时添加新好友，收到 contact.updated 时更新联系人资料，收到 contact.deleted 时移除联系人。不要定时整份重拉：每次调用都会从微信拉取完整列表，联系人多时耗时长、开销大，频繁拉取还会增加被微信风控的概率。

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

按 wxid 批量查询联系人资料。它和「联系人详情」调用的是微信的两个不同接口，一次查询很多人时，更适合用这个接口。 对个人好友，还会返回加好友的时间和方式：added_at、added_ts 是添加时间；add_source 是微信记录的添加方式编号，add_source_text 是它的中文说明，比如「扫一扫」「群聊」「搜索手机号」「名片分享」。含义还没有确认的编号只返回 add_source，不返回中文说明。通过群聊加的好友，add_source_group 是来源群的 ID。微信没有记录的项不返回。群和公众号不返回这几个字段。 所有联系人还会返回：avatar_large 高清头像（avatar 是小图）；pinyin 昵称和备注的拼音，全拼小写、首字母大写，可以用来排序和搜索；phones 是在微信备注里给这个人填写的电话号码。没有的项不返回。

> **建议缓存**：按 wxid 保存查到的资料，收到 contact.updated 事件时再更新对应联系人的资料。不要每收到一条消息就查询一次发送人的资料。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `wxids` | 请求体 | array | 是 | 要查询的 wxid 列表 |

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

查询联系人的完整资料，包括昵称、备注、微信号、头像、性别、地区、签名，以及该联系人的标签。标签在 label_ids 字段中，对应「标签列表」里的 ID；联系人没有标签时不返回这个字段。个人好友还会返回加好友的方式 add_source 和 add_source_text，通过群聊加的好友还有来源群 add_source_group；加好友的时间只有「批量取详情」能查到，这里不返回。高清头像 avatar_large、拼音 pinyin、备注电话 phones 和「批量取详情」一样返回。

> **建议缓存**：按 wxid 保存查到的资料，收到 contact.updated 事件时再更新对应联系人的资料。不要每收到一条消息就查询一次发送人的资料。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `wxids` | 请求体 | array | 是 | 要查询的 wxid，一次最多 50 个 |

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

检测这些人是否仍是你的好友。注意：微信对这个操作限制很严，一次检测的人数多或检测频繁，都可能导致实例被限制。一次最多检测 20 个，请按需使用。

> **建议缓存**：保存检测结果，同一个人在短时间内不要重复检测。

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

查询企业微信的外部联系人。这些联系人不在普通通讯录中，「通讯录列表」接口查不到他们。本接口返回平台已保存的数据，使用前请先调用一次「同步企微联系人」。

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

从微信重新拉取企业微信的外部联系人并保存到平台，返回拉取到的人数。没有头像的联系人会逐个补充获取头像，人数多时耗时较长，不建议频繁调用。

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

按微信号或手机号搜索用户，返回可用于添加好友的 contact_token。

> **建议缓存**：保存搜索到的 wxid 和昵称，不要反复搜索同一个号。搜索过于频繁时，微信会提示操作过于频繁，之后一段时间内都无法搜索。contact_token 会过期，真正要添加好友时，再搜索一次获取新的 contact_token。

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

> **注意（易封号）**：敏感接口，调用不当容易被微信限制甚至封号。添加好友是微信风控最严格的操作之一。不要在短时间内连续添加，不要批量自动加人，每次添加之间要留出间隔。新注册的号、刚换设备或刚登录的号风险更高，建议先正常使用几天再添加好友。

用搜索得到的 contact_token 发起好友申请。**这个接口响应较慢**：微信需要 5～20 秒才返回结果，实测平均 9 秒，最慢 16 秒。客户端超时时间请至少设为 30 秒。请求超时后不要直接重发，因为请求很可能已经发送成功。需要重试时，请带上 Idempotency-Key。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `contact_token` | 请求体 | string | 是 | 搜索结果中的 contact_token |
| `greeting` | 请求体 | string | 否 | 发给对方的验证消息 |
| `scene` | 请求体 | string | 否 | 申请来源，留空则使用默认值 |

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

> **注意（易封号）**：敏感接口，调用不当容易被微信限制甚至封号。短时间内大量通过好友申请同样会触发风控。不要在收到申请后立即批量自动通过，每次通过之间要留出间隔；申请数量多时，请分散到不同时间段处理。

通过他人的好友申请。需要传入好友申请事件中的 friend_request_token。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `friend_request_token` | 请求体 | string | 是 | 好友申请事件中的 friend_request_token |

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

修改一个联系人的备注名。

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

将联系人从通讯录中删除。对方不会收到通知，但之后无法再给你发消息。如需恢复，需要重新添加好友。

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

列出这个实例的联系人标签。标签仅自己可见。

> **建议缓存**：标签只有在你自己修改时才会变化。获取一次并保存，之后在新建、改名或删除标签后，再更新你保存的数据。

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

新建一个联系人标签，返回该标签的 label_id。

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

修改一个标签的名称。

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

删除一个标签。带有这个标签的联系人本身不受影响，只是不再带有该标签。

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

### 设置联系人的标签

```
PUT /v1/accounts/{account_id}/contacts/labels
```

为指定的联系人设置标签。设置采用覆盖方式：这些联系人原有的标签会全部替换为本次传入的标签。label_ids 传空数组表示移除他们的全部标签。不在 wxids 中的联系人不受影响。给某些联系人设置一个标签，不会把这个标签从其他联系人身上移除。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `wxids` | 请求体 | array | 是 | 要设置标签的联系人 wxid，一次最多 50 个 |
| `label_ids` | 请求体 | array | 是 | 设置后这些联系人拥有的全部标签 ID，对应「标签列表」里的 ID。传空数组表示不带任何标签 |

<details><summary>各语言怎么调</summary>

```python
wx.contact_labels("acc_xxx", wxids=["wxid_a"], label_ids=[1, 6])
```

```javascript
await wx.contact.labels('acc_xxx', { wxids: ['wxid_a'], label_ids: [1, 6] })
```

</details>


## 群

### 创建群聊

```
POST /v1/accounts/{account_id}/groups
```

邀请几位好友创建一个群聊，至少需要两个成员。

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

查询群的名称、公告、群主等资料。

> **建议缓存**：保存群资料，收到 group.renamed 事件时再重新获取。公告和群主很少变化，不要每收到一条群消息就查询一次。

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

> **建议缓存**：保存成员列表，之后根据 group.member_joined 和 group.member_left 事件增减成员。每次调用都会实时从微信拉取，大群耗时长、开销大，不要定时重新拉取整个列表。

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

查询指定群成员的完整资料，字段比「群成员」接口更全。

> **建议缓存**：按 wxid 保存成员资料，不要每收到一条群消息就查询一次发言人的资料。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `group_id` | 路径 | string | 是 | 群 ID |
| `members` | 请求体 | array | 是 | 要查询的 wxid |

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

邀请好友入群。群人数较多时，微信会改为发送邀请链接。

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

将成员移出群聊。只有群主和管理员可以操作。

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

设置或取消群管理员，也可以转让群主。只有群主可以操作。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `group_id` | 路径 | string | 是 | 群 ID |
| `action` | 请求体 | string | 是 | grant 设为管理员，revoke 取消管理员，transfer 转让群主（转让群主时 members 只能填一个人），可选值：`grant` / `revoke` / `transfer` |
| `members` | 请求体 | array | 是 | 目标成员的 wxid |

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

修改群名称。需要有修改群名称的权限。

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

修改群公告。只有群主和管理员可以操作，修改后会向全群发送一条提示。

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

为群设置一个仅自己可见的备注名。

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

修改自己在这个群里显示的昵称。

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

将群保存到通讯录，或取消保存。没有保存到通讯录的群，在聊天会话被删除后将无法再找到。

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

获取群的邀请二维码。返回 data URL，可直接用作 img 标签的 src。

> **建议缓存**：群二维码 7 天内有效。获取一次后保存为图片，快过期时再重新获取。

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

通过收到的群邀请链接加入群聊。

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

查看群邀请链接对应的群信息，不会加入该群。usable 为 false 时，notice 字段说明原因，比如链接已过期。

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

群成员邀请他人入群后，群主用这个接口同意邀请。inviter、message_id、ticket、members 四个参数都来自这条邀请事件。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `group_id` | 路径 | string | 是 | 群 ID |
| `inviter` | 请求体 | string | 是 | 邀请人的 wxid |
| `message_id` | 请求体 | string | 是 | 邀请事件中的消息 ID |
| `ticket` | 请求体 | string | 是 | 邀请事件中的凭据 |
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

为一个群或一个好友开启或关闭消息免打扰。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `chat_id` | 路径 | string | 是 | 群 ID 或好友 wxid |
| `enabled` | 请求体 | boolean | 是 | true 开启免打扰，false 恢复消息提醒 |

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

将一个群或一个好友的会话置顶，或取消置顶。

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

发送一条文字消息。在群里发送时可以 @ 群成员。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `to` | 请求体 | string | 是 | 接收方：好友的 wxid、群 ID，或 filehelper（自己的文件传输助手） |
| `content` | 请求体 | string | 是 | 消息正文 |
| `mentions` | 请求体 | array | 否 | 要 @ 的成员 wxid，仅在群聊中有效 |

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

发送一张图片。url 和 media_id 二选一，使用 media_id 可以复用平台已保存的文件。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `to` | 请求体 | string | 是 | 接收方：好友的 wxid、群 ID，或 filehelper（自己的文件传输助手） |
| `url` | 请求体 | string | 否 | 可从公网下载的文件地址 |
| `media_id` | 请求体 | string | 否 | 平台中已有文件的媒体 ID |
| `use_cache` | 请求体 | boolean | 否 | 同一个 url 之前发送过时，直接复用已上传的文件，不再重新上传。默认 true。只有地址不变但文件内容已更换时，才需要传 false |

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

发送一段视频。不填时长时由平台估算，部分客户端可能会显示异常。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `to` | 请求体 | string | 是 | 接收方：好友的 wxid、群 ID，或 filehelper（自己的文件传输助手） |
| `url` | 请求体 | string | 否 | 可从公网下载的文件地址 |
| `media_id` | 请求体 | string | 否 | 平台中已有文件的媒体 ID |
| `use_cache` | 请求体 | boolean | 否 | 同一个 url 之前发送过时，直接复用已上传的文件，不再重新上传。默认 true。只有地址不变但文件内容已更换时，才需要传 false |
| `duration` | 请求体 | integer | 否 | 时长（秒） |
| `thumbnail_url` | 请求体 | string | 否 | 封面图地址，需要是可从公网下载的图片 |

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

发送一条语音。seconds 是语音时长，会显示在聊天中的语音消息上。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `to` | 请求体 | string | 是 | 接收方：好友的 wxid、群 ID，或 filehelper（自己的文件传输助手） |
| `url` | 请求体 | string | 是 | 可从公网下载的音频地址 |
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

发送一个文件。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `to` | 请求体 | string | 是 | 接收方：好友的 wxid、群 ID，或 filehelper（自己的文件传输助手） |
| `url` | 请求体 | string | 否 | 可从公网下载的文件地址 |
| `media_id` | 请求体 | string | 否 | 平台中已有文件的媒体 ID |
| `use_cache` | 请求体 | boolean | 否 | 同一个 url 之前发送过时，直接复用已上传的文件，不再重新上传。默认 true。只有地址不变但文件内容已更换时，才需要传 false |
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

转发一个动图表情。表情通过引用发送，无需上传文件。checksum 和 length 取自收到的表情消息。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `to` | 请求体 | string | 是 | 接收方：好友的 wxid、群 ID，或 filehelper（自己的文件传输助手） |
| `checksum` | 请求体 | string | 是 | 表情的校验值，取自收到的表情消息 |
| `length` | 请求体 | integer | 是 | 表情的字节数，取自同一条表情消息 |

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

发送一张可点击的链接卡片。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `to` | 请求体 | string | 是 | 接收方：好友的 wxid、群 ID，或 filehelper（自己的文件传输助手） |
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

发送一张小程序卡片。需要提供小程序的标识。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `to` | 请求体 | string | 是 | 接收方：好友的 wxid、群 ID，或 filehelper（自己的文件传输助手） |
| `app_id` | 请求体 | string | 是 | 小程序的公开标识 |
| `username` | 请求体 | string | 是 | 小程序的原始 ID |
| `title` | 请求体 | string | 是 | 卡片标题 |
| `description` | 请求体 | string | 否 | 卡片摘要 |
| `path` | 请求体 | string | 否 | 点击后打开的小程序页面路径 |
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

将收到过的一条消息原样转发给其他人。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `to` | 请求体 | string | 是 | 接收方：好友的 wxid、群 ID，或 filehelper（自己的文件传输助手） |
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

撤回自己发出的一条消息。微信只允许在发出后约两分钟内撤回，超过时间会被拒绝。

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

查询平台保存的消息记录，可以按会话筛选。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `peer` | 查询串 | string | 否 | 只返回与某个 wxid 或群的会话中的消息 |
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

主动拉取这个实例收到的消息，内容与 Webhook 推送的完全相同。如果没有配置 Webhook、Webhook 中断过，或者服务重启过，可以用它补回这段时间的消息。cursor 留空时，从目前仍保留的最早一条消息开始返回（大约可追溯一天）。之后每次调用都传入上一次返回的 next_cursor。has_more 为 true 表示还没有拉取完，请立即再调用一次。没有新消息时，返回的 next_cursor 与传入的相同，游标不会前进。拉取到的消息不会写入数据库，也不会触发 Webhook，重复拉取没有副作用。

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

查询一条消息。

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

列出这个实例收藏的内容。cursor 留空时从第一页开始，返回的 next_cursor 为空表示已经到最后一页。

> **建议缓存**：收藏只在你自己新增或删除收藏时才会变化。获取一次并保存，不要轮询；在你新增或删除收藏后再重新获取。

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

查询一条收藏的完整内容。内容为微信原始的 XML，平台原样返回，不同类型的收藏结构不同。

> **建议缓存**：收藏的内容不会变化。按 fav_id 保存，获取过一次就不需要再获取。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `fav_id` | 路径 | integer | 是 | 收藏 ID，从「收藏列表」获取 |

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

删除一条收藏。

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

### 上传文件

```
POST /v1/accounts/{account_id}/media/upload
```

直接上传文件，获取一个 media_id。之后发送图片、视频、语音或文件时，只需传入这个 ID。适用于文件在你自己的机器上、没有公网地址的情况，比如程序刚生成的一张图片。请用 multipart/form-data 提交，文件放在 file 字段中，最大 20 MB。文件在第一次发送时才会真正上传到微信，之后用同一个 ID 发送不会重复上传。上传后一直没有发送过的文件保留 24 小时。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `file` | 请求体 | file | 是 | 要上传的文件，multipart/form-data |
| `kind` | 请求体 | string | 否 | 这个文件将作为哪种消息发送，不填则根据文件类型自动判断，可选值：`image` / `video` / `voice` / `file` |

<details><summary>各语言怎么调</summary>

```python
wx.media_upload("acc_xxx", file="...")
```

```javascript
await wx.media.upload('acc_xxx', { file: '...' })
```

</details>

### 下载消息附件

```
POST /v1/accounts/{account_id}/media/download
```

获取一条消息中的图片、视频、文件或语音，返回一个限时有效的下载地址。

> **建议缓存**：下载地址有时效。拿到文件后请保存到你自己的存储中，不要每次展示时都重新下载。平台上已下载文件的总量超过上限时，会清除最早的一半，请不要把平台当作长期存储。

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

查询平台是否已经发送过某个地址的文件。发送过的文件可以直接复用，不需要重新上传，也不计流量。建议在把文件放到公网之前先调用这个接口。如果平台已经发送过，就不必再把文件放到公网。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `url` | 请求体 | string | 是 | 要发送的文件地址，必须与发送时填写的地址完全一致才算命中 |
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

为已经下载过的文件重新生成一个限时有效的下载地址。

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

获取一条朋友圈动态中的第 N 张图片，或动态中的视频。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `moment_id` | 路径 | string | 是 | 动态 ID |
| `index` | 请求体 | integer | 否 | 图片序号，从 0 开始。视频动态会忽略这个值 |

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

查询这个实例能看到的朋友圈时间线。

> **建议缓存**：每次调用都会实时向微信请求，请不要定时刷新，刷新过于频繁会被视为异常行为。需要时再获取，并把获取到的动态保存在你自己的系统中。

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

查询一条朋友圈的完整内容。列表接口中的点赞和评论会被截断，这个接口返回完整的点赞和评论。

> **建议缓存**：动态的正文和图片不会变化，获取后请保存。只有需要查看最新的点赞和评论时，才需要重新获取。

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

查询某个联系人的朋友圈主页。

> **建议缓存**：每次调用都会实时向微信请求，请不要定时刷新，刷新过于频繁会被视为异常行为。需要时再获取，并把获取到的动态保存在你自己的系统中。

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

发布一条纯文字朋友圈。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `content` | 请求体 | string | 是 | 正文 |
| `mentions` | 请求体 | array | 否 | 要 @ 的 wxid |
| `visibility` | 请求体 | object | 否 | 可见范围。mode 可选 public、private、allow、deny；选择 allow 或 deny 时，需要同时提供 wxids 或 tag_ids |

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

发布一条带图片的朋友圈。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `content` | 请求体 | string | 否 | 正文 |
| `images` | 请求体 | array | 是 | 图片列表 |
| `visibility` | 请求体 | object | 否 | 可见范围。mode 可选 public、private、allow、deny；选择 allow 或 deny 时，需要同时提供 wxids 或 tag_ids |

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

发布一条视频朋友圈。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `content` | 请求体 | string | 否 | 正文 |
| `video` | 请求体 | object | 是 | 视频，需提供可从公网下载的地址 |
| `cover` | 请求体 | object | 否 | 封面图 |
| `duration` | 请求体 | integer | 否 | 时长（秒） |
| `visibility` | 请求体 | object | 否 | 可见范围。mode 可选 public、private、allow、deny；选择 allow 或 deny 时，需要同时提供 wxids 或 tag_ids |

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

将看到的一条动态原样重新发布一次。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `moment_id` | 请求体 | string | 是 | 要转发的动态 ID |
| `visibility` | 请求体 | object | 否 | 可见范围。mode 可选 public、private、allow、deny；选择 allow 或 deny 时，需要同时提供 wxids 或 tag_ids |

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

给一条动态点赞。点赞前需要先通过朋友圈列表或详情接口读取过这条动态，读取后 24 小时内可以点赞。

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
| `reply_to` | 请求体 | integer | 否 | 要回复的评论 ID，留空表示直接评论这条动态 |

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

删除自己发表的一条评论。

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

删除自己发布的一条朋友圈。

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

将自己的一条动态设为仅自己可见，或恢复为公开。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 路径 | string | 是 | 实例 ID，形如 acc_xxx |
| `moment_id` | 路径 | string | 是 | 动态 ID |
| `private` | 请求体 | boolean | 是 | true 表示仅自己可见，false 表示公开 |

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

查询当前 API Key 所属的用户，以及该用户的实例数量。

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

查询平台记录的事件，可以按实例、事件类型、消息类型和时间筛选。没有配置 Webhook 时，可以轮询这个接口获取事件。 轮询方法：第一次调用可以用 since 指定起始时间。之后每次调用都传入上一次返回的 next_cursor，从该位置之后继续读取。只要本页有事件，就一定会返回 next_cursor。没有新事件时 next_cursor 为空，此时请继续使用你已保存的上一个 next_cursor。has_more 为 true 表示后面还有事件，请立即继续读取；否则请等待几秒后再轮询。如果处理过程中程序重启，而最新的游标还没来得及保存，重新读取时会再次拿到相同的几条事件，因此建议按 event_id 去重。

| 参数 | 位置 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- | --- |
| `account_id` | 查询串 | string | 否 | 只返回某个实例的事件 |
| `type` | 查询串 | string | 否 | 只返回某种类型的事件，参数可重复传入 |
| `message_type` | 查询串 | string | 否 | 只返回某种消息类型的事件，如 text、image、file，参数可重复传入。设置后，非消息类事件不会出现在结果中 |
| `since` | 查询串 | string | 否 | 只返回这个时间之后的事件，格式为 RFC3339 或 Unix 秒级时间戳 |
| `until` | 查询串 | string | 否 | 只返回这个时间之前的事件，格式为 RFC3339 或 Unix 秒级时间戳 |
| `keyword` | 查询串 | string | 否 | 按事件内容搜索。需要同时指定时间范围，且范围不超过 1 小时 |
| `order` | 查询串 | string | 否 | oldest 按时间从早到晚返回（默认），newest 从最近发生的事件开始返回，可选值：`oldest` / `newest` |
| `cursor` | 查询串 | string | 否 | 上一次返回的 next_cursor，从该位置之后继续读取。第一次调用时留空。如果返回的 next_cursor 为空，请继续使用上一次的值 |
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

通过 SSE 长连接实时接收该实例的事件，事件内容与 Webhook 推送的相同。

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

