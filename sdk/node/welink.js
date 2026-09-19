/**
 * WeLink —— 把微信的能力做成 HTTP 接口。
 *
 *   import { WeLink } from './welink.js'
 *
 *   const wx = new WeLink({ apiKey: 'key_xxx', baseUrl: 'https://你的服务地址' })
 *   await wx.message.text('acc_xxx', { to: 'filehelper', content: '你好' })
 *
 * 每个方法对应一个接口，返回的是响应里 data 字段的内容。
 * 调用失败抛 WeLinkError，上面带平台错误码和 requestId。
 *
 * 只用了 Node 内置的 fetch（18 以上），没有依赖。
 *
 * 本文件由接口清单生成，不要手改。
 */

export class WeLinkError extends Error {
  constructor(code, message, requestId = '', status = 0) {
    super(`[${code}] ${message}`)
    this.name = 'WeLinkError'
    this.code = code
    this.requestId = requestId
    this.status = status
  }
}

/**
 * 校验事件回调的签名。
 *
 * 必须拿原始请求体来算 —— 先 JSON.parse 再 stringify，字段顺序和空格都会变，
 * 算出来的签名就对不上了。
 */
export function verifyWebhook(secret, rawBody, signature) {
  const { createHmac, timingSafeEqual } = require('node:crypto')
  const expected = 'sha256=' + createHmac('sha256', secret).update(rawBody).digest('hex')
  const a = Buffer.from(expected)
  const b = Buffer.from(signature || '')
  return a.length === b.length && timingSafeEqual(a, b)
}

export class WeLink {
  /**
   * @param {{apiKey: string, baseUrl: string, timeout?: number}} options
   *   baseUrl 填你拿到的服务地址，不带结尾斜杠也可以。
   */
  constructor({ apiKey, baseUrl, timeout = 30000 } = {}) {
    if (!apiKey) throw new Error('apiKey 不能为空')
    if (!baseUrl) throw new Error('baseUrl 不能为空，填你拿到的服务地址')
    this.apiKey = apiKey
    this.baseUrl = baseUrl.replace(/\/+$/, '')
    this.timeout = timeout
    build(this)
  }

  /** 直接调用一个接口。清单里还没有的新接口可以用它。 */
  async call(method, path, { query, body } = {}) {
    let url = this.baseUrl + '/v1' + path
    if (query) {
      const search = new URLSearchParams()
      for (const [key, value] of Object.entries(query)) {
        if (value === undefined || value === null || value === '') continue
        if (Array.isArray(value)) value.forEach((v) => search.append(key, String(v)))
        else search.append(key, String(value))
      }
      const q = search.toString()
      if (q) url += '?' + q
    }

    const headers = { Authorization: 'Bearer ' + this.apiKey, Accept: 'application/json' }
    let payload
    if (body !== undefined) {
      headers['Content-Type'] = 'application/json'
      payload = JSON.stringify(clean(body))
    }

    const stop = AbortSignal.timeout(this.timeout)
    let res
    try {
      res = await fetch(url, { method, headers, body: payload, signal: stop })
    } catch (err) {
      throw new WeLinkError(0, `连不上服务：${err.message}`)
    }

    const text = await res.text()
    let envelope
    try {
      envelope = JSON.parse(text)
    } catch {
      throw new WeLinkError(0, `服务返回的不是 JSON（HTTP ${res.status}）`, '', res.status)
    }
    if (envelope.code !== 0) {
      throw new WeLinkError(envelope.code ?? 0, envelope.message || '调用失败',
        envelope.request_id || '', res.status)
    }
    return envelope.data
  }
}

function clean(body) {
  const out = {}
  for (const [k, v] of Object.entries(body || {})) if (v !== undefined && v !== null) out[k] = v
  return out
}


function pick(opts, keys) {
  const out = {}
  for (const k of keys) if (opts && opts[k] !== undefined) out[k] = opts[k]
  return out
}

/** Hangs one object of methods per group off the client. */
function build(self) {
  self.account = {
    /**
     * 创建实例 —— 开一个实例。占用一个额度，删除后归还。创建后还要扫码才会上线。
     *
     * POST /v1/accounts
     * @param {object} opts
     * @param {string} [opts.platform] 必填 登录方式（ipad / mac）
     * @param {string} [opts.name] 备注名称，只给自己看
     * @param {string} [opts.proxy] 代理网络，留空则直连。两种填法：socks5 代理地址（socks5://user:pass@host:port），或者网络助手的网络ID（把网络助手装到一台手机上，打开即可看到，这台手机的网络就是这个实例的出口）——…
     * @param {string} [opts.webhook_url] 该实例的事件推送地址
     */
    create(opts = {}) {
      return self.call('POST', '/accounts', { body: pick(opts, ['platform', 'name', 'proxy', 'webhook_url']) })
    },
    /**
     * 实例列表 —— 列出你的全部实例与它们的状态。
     *
     * GET /v1/accounts
     */
    list() {
      return self.call('GET', '/accounts')
    },
    /**
     * 实例详情 —— 读一个实例。不在线时 reason 会说明原因（manual 主动退出、kicked 被别处挤下线、relogin_required 需重新扫码、recover_timeout 恢复超时、expired 授权到期）；status 为 recovering 时 recovering 里带恢复方式与放弃时间。
     *
     * GET /v1/accounts/{account_id}
     * @param {string} accountId 实例 ID，形如 acc_xxx
     */
    get(accountId) {
      return self.call('GET', `/accounts/${encodeURIComponent(accountId)}`)
    },
    /**
     * 获取登录二维码 —— 取一张登录二维码，用手机扫。expires_in 是这张码还剩多少秒，以返回值为准，不要写死；过期了再取一张即可。带上 proxy 可以顺便换代理网络 —— 它是开会话时定下的，换了要重开会话，所以只能在扫码这一刻换；不传则沿用原来的。
     *
     * POST /v1/accounts/{account_id}/login/qrcode
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {string} [opts.proxy] 改用这个代理网络：socks5 代理地址或网络助手的网络ID；传空串改为直连，不传则不动
     */
    qrcode(accountId, opts = {}) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/login/qrcode`, { body: pick(opts, ['proxy']) })
    },
    /**
     * 登录状态 —— 轮询扫码进度：waiting（等待扫码）、scanned（已扫码待确认）、online（已上线）、cancelled、expired。等待扫码时还带 expires_in，是这张码此刻还剩多少秒，用它校准倒计时。
     *
     * GET /v1/accounts/{account_id}/login/status
     * @param {string} accountId 实例 ID，形如 acc_xxx
     */
    loginStatus(accountId) {
      return self.call('GET', `/accounts/${encodeURIComponent(accountId)}/login/status`)
    },
    /**
     * 取消扫码 —— 放弃这次扫码。已经发出去的码会连同它背后的会话一起作废，扫了也不会让这个实例上线；实例回到未登录，重新取码即可。关闭扫码页面时调用它，别把一张还能用的码留在外面。
     *
     * POST /v1/accounts/{account_id}/login/cancel
     * @param {string} accountId 实例 ID，形如 acc_xxx
     */
    loginCancel(accountId) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/login/cancel`)
    },
    /**
     * 提交安全验证 —— 登录过程中出现安全验证时，把验证结果提交回来。
     *
     * POST /v1/accounts/{account_id}/login/captcha
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {object} [opts.fields] 必填 验证所需的字段，按提示填写
     */
    captcha(accountId, opts = {}) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/login/captcha`, { body: pick(opts, ['fields']) })
    },
    /**
     * 重新连接 —— 掉线后尝试不重新扫码就恢复连接。恢复不了才需要重新扫码。
     *
     * POST /v1/accounts/{account_id}/reconnect
     * @param {string} accountId 实例 ID，形如 acc_xxx
     */
    reconnect(accountId) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/reconnect`)
    },
    /**
     * 退出登录 —— 让实例下线。实例与额度保留，可以再扫码上线。
     *
     * POST /v1/accounts/{account_id}/logout
     * @param {string} accountId 实例 ID，形如 acc_xxx
     */
    logout(accountId) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/logout`)
    },
    /**
     * 删除实例 —— 删除槽位并归还额度。历史消息不会立刻清除。
     *
     * DELETE /v1/accounts/{account_id}
     * @param {string} accountId 实例 ID，形如 acc_xxx
     */
    delete(accountId) {
      return self.call('DELETE', `/accounts/${encodeURIComponent(accountId)}`)
    },
    /**
     * 实例资料 —— 读这个实例自己的昵称、头像、地区等资料。
     *
     * GET /v1/accounts/{account_id}/profile
     * @param {string} accountId 实例 ID，形如 acc_xxx
     */
    profile(accountId) {
      return self.call('GET', `/accounts/${encodeURIComponent(accountId)}/profile`)
    },
    /**
     * 修改个人资料 —— 改昵称、签名、性别与地区。字段留空就是清空该项，请把要保留的一起传。
     *
     * PUT /v1/accounts/{account_id}/profile
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {string} [opts.city] 市
     * @param {string} [opts.country] 国家
     * @param {string} [opts.nickname] 昵称
     * @param {string} [opts.province] 省
     * @param {string} [opts.sex] 1 男，2 女，0 不显示（0 / 1 / 2）
     * @param {string} [opts.signature] 个性签名
     */
    updateProfile(accountId, opts = {}) {
      return self.call('PUT', `/accounts/${encodeURIComponent(accountId)}/profile`, { body: pick(opts, ['nickname', 'signature', 'sex', 'country', 'province', 'city']) })
    },
    /**
     * 设置微信号 —— 设置可被搜索的微信号。微信只允许设置一次，之后会拒绝。
     *
     * PUT /v1/accounts/{account_id}/profile/alias
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {string} [opts.alias] 必填 要设置的微信号
     */
    setAlias(accountId, opts = {}) {
      return self.call('PUT', `/accounts/${encodeURIComponent(accountId)}/profile/alias`, { body: pick(opts, ['alias']) })
    },
    /**
     * 修改头像 —— 换头像。
     *
     * PUT /v1/accounts/{account_id}/profile/avatar
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {string} [opts.url] 必填 公网可下载的图片地址
     */
    setAvatar(accountId, opts = {}) {
      return self.call('PUT', `/accounts/${encodeURIComponent(accountId)}/profile/avatar`, { body: pick(opts, ['url']) })
    },
    /**
     * 我的二维码 —— 取这个实例自己的名片二维码，返回 data URL，可直接放进 img。
     *
     * GET /v1/accounts/{account_id}/profile/qrcode
     * @param {string} accountId 实例 ID，形如 acc_xxx
     */
    qrcodeSelf(accountId) {
      return self.call('GET', `/accounts/${encodeURIComponent(accountId)}/profile/qrcode`)
    },
    /**
     * 隐私设置 —— 开关一项隐私设置。
     *
     * PUT /v1/accounts/{account_id}/privacy
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {boolean} [opts.enabled] 必填 开或关
     * @param {string} [opts.option] 必填 need_confirm_to_add 加我需验证；findable_by_phone 手机号可搜；findable_by_alias 微信号可搜；recommend_contacts 向我推荐通讯录好友；strang…（need_confirm_to_add / findable_by_phone / findable_by_alias / recommend_contacts / strangers_see_ten / visible_days）
     */
    privacy(accountId, opts = {}) {
      return self.call('PUT', `/accounts/${encodeURIComponent(accountId)}/privacy`, { body: pick(opts, ['option', 'enabled']) })
    },
    /**
     * 已登录设备 —— 列出这个微信号登录过的设备，本平台也在其中。
     *
     * GET /v1/accounts/{account_id}/devices
     * @param {string} accountId 实例 ID，形如 acc_xxx
     */
    devices(accountId) {
      return self.call('GET', `/accounts/${encodeURIComponent(accountId)}/devices`)
    },
    /**
     * 下线某个设备 —— 把某个已登录设备踢下线。注意别把本平台自己踢了。
     *
     * DELETE /v1/accounts/{account_id}/devices/{device_id}
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {string} deviceId 设备 ID，取自已登录设备列表
     */
    deviceSignout(accountId, deviceId) {
      return self.call('DELETE', `/accounts/${encodeURIComponent(accountId)}/devices/${encodeURIComponent(deviceId)}`)
    },
    /**
     * 设置 Webhook —— 设置该实例事件的推送地址。每次投递都带签名，用 secret 校验。
     *
     * PUT /v1/accounts/{account_id}/webhook
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {string} [opts.url] 必填 接收事件的地址
     * @param {Array} [opts.events] 只推这些类型，留空推全部
     * @param {string} [opts.secret] 签名密钥，留空则保持不变
     */
    webhook(accountId, opts = {}) {
      return self.call('PUT', `/accounts/${encodeURIComponent(accountId)}/webhook`, { body: pick(opts, ['url', 'secret', 'events']) })
    },
  }

  self.contact = {
    /**
     * 通讯录标识 —— 列出通讯录里都有谁，只给标识：好友的 wxid、群的 @chatroom、公众号的 gh_ 开头，一个不筛。要资料再用「联系人详情」按需取——一千个人里你可能只关心十个。直接向微信取，实例要在线；一页多大由微信定，翻页把 next_cursor 原样带回来，为空表示到底。
     *
     * GET /v1/accounts/{account_id}/contacts
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {string} [opts.cursor] 上一页返回的 next_cursor，首页留空
     */
    ids(accountId, opts = {}) {
      return self.call('GET', `/accounts/${encodeURIComponent(accountId)}/contacts`, { query: pick(opts, ['cursor']) })
    },
    /**
     * 批量取详情 —— 按 wxid 批量取联系人资料。与「联系人详情」走的是微信的两条不同路径，字段相同，这条更适合一次问很多人。
     *
     * POST /v1/accounts/{account_id}/contacts/batch
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {Array} [opts.wxids] 必填 要查的 wxid 列表
     */
    batch(accountId, opts = {}) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/contacts/batch`, { body: pick(opts, ['wxids']) })
    },
    /**
     * 联系人详情 —— 读联系人的完整资料：昵称、备注、微信号、头像、性别、地区、签名。
     *
     * POST /v1/accounts/{account_id}/contacts/detail
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {Array} [opts.wxids] 必填 要查的 wxid，一次最多 50 个
     */
    detail(accountId, opts = {}) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/contacts/detail`, { body: pick(opts, ['wxids']) })
    },
    /**
     * 检测好友关系 —— 查这些人是否还是好友。注意：微信对这个操作盯得很紧，查得多或查得频繁会导致实例被限制，一次最多 20 个，请按需使用。
     *
     * POST /v1/accounts/{account_id}/contacts/check
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {Array} [opts.wxids] 必填 要检测的 wxid，一次最多 20 个
     */
    check(accountId, opts = {}) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/contacts/check`, { body: pick(opts, ['wxids']) })
    },
    /**
     * 企微联系人 —— 读企业微信那边的外部联系人。这些人不在普通通讯录里，「通讯录标识」拉不到他们。读的是平台存下来的那一份，先调一次同步。
     *
     * GET /v1/accounts/{account_id}/contacts/external
     * @param {string} accountId 实例 ID，形如 acc_xxx
     */
    external(accountId) {
      return self.call('GET', `/accounts/${encodeURIComponent(accountId)}/contacts/external`)
    },
    /**
     * 同步企微联系人 —— 去微信那边重新拉一遍企微外部联系人并存下来，返回拉到多少个。没有头像的会逐个补拉，人多时会慢一些，不建议频繁调用。
     *
     * POST /v1/accounts/{account_id}/contacts/external/sync
     * @param {string} accountId 实例 ID，形如 acc_xxx
     */
    externalSync(accountId) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/contacts/external/sync`)
    },
    /**
     * 搜索用户 —— 按微信号或手机号搜人，返回一个可用于加好友的 contact_token。
     *
     * POST /v1/accounts/{account_id}/contacts/search
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {string} [opts.keyword] 必填 微信号或手机号
     */
    search(accountId, opts = {}) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/contacts/search`, { body: pick(opts, ['keyword']) })
    },
    /**
     * 添加好友 —— 用搜索得到的 contact_token 发起好友申请。**这一条慢**：微信自己要 5～20 秒才回，实测平均 9 秒、最慢 16 秒，客户端超时请留够 30 秒。超时了不要直接重发——请求多半已经送出去了，要重试就带上 Idempotency-Key。加得太频繁会被微信限制。
     *
     * POST /v1/accounts/{account_id}/contacts/add
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {string} [opts.contact_token] 必填 搜索结果里的 contact_token
     * @param {string} [opts.greeting] 打招呼的话
     * @param {string} [opts.scene] 申请来源，留空用默认值
     */
    add(accountId, opts = {}) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/contacts/add`, { body: pick(opts, ['contact_token', 'greeting', 'scene']) })
    },
    /**
     * 通过好友申请 —— 同意别人的好友申请，用事件里给出的 friend_request_token。
     *
     * POST /v1/accounts/{account_id}/contacts/accept
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {string} [opts.friend_request_token] 必填 好友申请事件里的 token
     */
    accept(accountId, opts = {}) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/contacts/accept`, { body: pick(opts, ['friend_request_token']) })
    },
    /**
     * 设置备注 —— 给一个联系人改备注名。
     *
     * PUT /v1/accounts/{account_id}/contacts/{wxid}/remark
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {string} wxid 联系人的 wxid
     * @param {object} opts
     * @param {string} [opts.remark] 必填 新的备注名
     */
    remark(accountId, wxid, opts = {}) {
      return self.call('PUT', `/accounts/${encodeURIComponent(accountId)}/contacts/${encodeURIComponent(wxid)}/remark`, { body: pick(opts, ['remark']) })
    },
    /**
     * 删除好友 —— 把人从通讯录里删掉。对方不会收到通知，但从此发不进来；要恢复得重新加。
     *
     * DELETE /v1/accounts/{account_id}/contacts/{wxid}
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {string} wxid 要删除的 wxid
     */
    delete(accountId, wxid) {
      return self.call('DELETE', `/accounts/${encodeURIComponent(accountId)}/contacts/${encodeURIComponent(wxid)}`)
    },
  }

  self.label = {
    /**
     * 标签列表 —— 列出这个实例的联系人标签。标签只有自己看得见。
     *
     * GET /v1/accounts/{account_id}/labels
     * @param {string} accountId 实例 ID，形如 acc_xxx
     */
    list(accountId) {
      return self.call('GET', `/accounts/${encodeURIComponent(accountId)}/labels`)
    },
    /**
     * 新建标签 —— 新建一个联系人标签，返回它的 label_id。
     *
     * POST /v1/accounts/{account_id}/labels
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {string} [opts.name] 必填 标签名
     */
    add(accountId, opts = {}) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/labels`, { body: pick(opts, ['name']) })
    },
    /**
     * 改标签名 —— 改一个标签的名字。
     *
     * PUT /v1/accounts/{account_id}/labels/{label_id}
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {string} labelId 标签 ID
     * @param {object} opts
     * @param {string} [opts.name] 必填 新的标签名
     */
    rename(accountId, labelId, opts = {}) {
      return self.call('PUT', `/accounts/${encodeURIComponent(accountId)}/labels/${encodeURIComponent(labelId)}`, { body: pick(opts, ['name']) })
    },
    /**
     * 删除标签 —— 删掉一个标签。带这个标签的联系人不受影响，只是不再带它。
     *
     * DELETE /v1/accounts/{account_id}/labels/{label_id}
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {string} labelId 标签 ID
     */
    delete(accountId, labelId) {
      return self.call('DELETE', `/accounts/${encodeURIComponent(accountId)}/labels/${encodeURIComponent(labelId)}`)
    },
    /**
     * 设置标签成员 —— 设置哪些联系人带这个标签。是覆盖不是追加：没列进来的会被摘掉。
     *
     * PUT /v1/accounts/{account_id}/labels/{label_id}/members
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {string} labelId 标签 ID
     * @param {object} opts
     * @param {Array} [opts.wxids] 必填 带这个标签的 wxid 全集
     */
    members(accountId, labelId, opts = {}) {
      return self.call('PUT', `/accounts/${encodeURIComponent(accountId)}/labels/${encodeURIComponent(labelId)}/members`, { body: pick(opts, ['wxids']) })
    },
  }

  self.group = {
    /**
     * 创建群聊 —— 拉几个好友建一个群。至少两个成员。
     *
     * POST /v1/accounts/{account_id}/groups
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {Array} [opts.members] 必填 初始成员的 wxid
     */
    create(accountId, opts = {}) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/groups`, { body: pick(opts, ['members']) })
    },
    /**
     * 群详情 —— 读群的名称、公告、群主等资料。
     *
     * GET /v1/accounts/{account_id}/groups/{group_id}
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {string} groupId 群 ID
     */
    get(accountId, groupId) {
      return self.call('GET', `/accounts/${encodeURIComponent(accountId)}/groups/${encodeURIComponent(groupId)}`)
    },
    /**
     * 群成员 —— 列出群成员。
     *
     * GET /v1/accounts/{account_id}/groups/{group_id}/members
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {string} groupId 群 ID
     */
    members(accountId, groupId) {
      return self.call('GET', `/accounts/${encodeURIComponent(accountId)}/groups/${encodeURIComponent(groupId)}/members`)
    },
    /**
     * 群成员详情 —— 读指定几个群成员的完整资料，比群成员列表更全。
     *
     * POST /v1/accounts/{account_id}/groups/{group_id}/members/detail
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {string} groupId 群 ID
     * @param {object} opts
     * @param {Array} [opts.members] 必填 要查的 wxid
     */
    memberDetail(accountId, groupId, opts = {}) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/groups/${encodeURIComponent(groupId)}/members/detail`, { body: pick(opts, ['members']) })
    },
    /**
     * 邀请入群 —— 邀请好友进群。群人数多时微信会改为发邀请链接。
     *
     * POST /v1/accounts/{account_id}/groups/{group_id}/invite
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {string} groupId 群 ID
     * @param {object} opts
     * @param {Array} [opts.members] 必填 要邀请的 wxid
     * @param {string} [opts.reason] 邀请说明
     */
    invite(accountId, groupId, opts = {}) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/groups/${encodeURIComponent(groupId)}/invite`, { body: pick(opts, ['members', 'reason']) })
    },
    /**
     * 移出群成员 —— 把人移出群。只有群主和管理员能做。
     *
     * POST /v1/accounts/{account_id}/groups/{group_id}/members/remove
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {string} groupId 群 ID
     * @param {object} opts
     * @param {Array} [opts.members] 必填 要移出的 wxid
     */
    remove(accountId, groupId, opts = {}) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/groups/${encodeURIComponent(groupId)}/members/remove`, { body: pick(opts, ['members']) })
    },
    /**
     * 群管理员 —— 设置或取消群管理员，也可以转让群主。只有群主能做。
     *
     * POST /v1/accounts/{account_id}/groups/{group_id}/admins
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {string} groupId 群 ID
     * @param {object} opts
     * @param {string} [opts.action] 必填 grant 设为管理员，revoke 取消，transfer 转让群主（只能一个人）（grant / revoke / transfer）
     * @param {Array} [opts.members] 必填 目标 wxid
     */
    admins(accountId, groupId, opts = {}) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/groups/${encodeURIComponent(groupId)}/admins`, { body: pick(opts, ['action', 'members']) })
    },
    /**
     * 修改群名 —— 改群名称。需要有权限改。
     *
     * PUT /v1/accounts/{account_id}/groups/{group_id}/name
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {string} groupId 群 ID
     * @param {object} opts
     * @param {string} [opts.name] 必填 新的群名称
     */
    rename(accountId, groupId, opts = {}) {
      return self.call('PUT', `/accounts/${encodeURIComponent(accountId)}/groups/${encodeURIComponent(groupId)}/name`, { body: pick(opts, ['name']) })
    },
    /**
     * 设置群公告 —— 改群公告。只有群主和管理员能做，会给全群发一条提示。
     *
     * PUT /v1/accounts/{account_id}/groups/{group_id}/announcement
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {string} groupId 群 ID
     * @param {object} opts
     * @param {string} [opts.content] 必填 公告正文，留空表示清除
     */
    announcement(accountId, groupId, opts = {}) {
      return self.call('PUT', `/accounts/${encodeURIComponent(accountId)}/groups/${encodeURIComponent(groupId)}/announcement`, { body: pick(opts, ['content']) })
    },
    /**
     * 设置群备注 —— 给群起一个只有自己看得到的名字。
     *
     * PUT /v1/accounts/{account_id}/groups/{group_id}/remark
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {string} groupId 群 ID
     * @param {object} opts
     * @param {string} [opts.remark] 必填 备注名，留空表示清除
     */
    remark(accountId, groupId, opts = {}) {
      return self.call('PUT', `/accounts/${encodeURIComponent(accountId)}/groups/${encodeURIComponent(groupId)}/remark`, { body: pick(opts, ['remark']) })
    },
    /**
     * 设置我的群昵称 —— 改自己在这个群里显示的名字。
     *
     * PUT /v1/accounts/{account_id}/groups/{group_id}/nickname
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {string} groupId 群 ID
     * @param {object} opts
     * @param {string} [opts.nickname] 必填 群内昵称
     */
    nickname(accountId, groupId, opts = {}) {
      return self.call('PUT', `/accounts/${encodeURIComponent(accountId)}/groups/${encodeURIComponent(groupId)}/nickname`, { body: pick(opts, ['nickname']) })
    },
    /**
     * 保存到通讯录 —— 把群保存到通讯录，或取消保存。不保存的群在会话删除后就找不回来了。
     *
     * PUT /v1/accounts/{account_id}/groups/{group_id}/kept
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {string} groupId 群 ID
     * @param {object} opts
     * @param {boolean} [opts.enabled] 必填 true 保存，false 取消
     */
    kept(accountId, groupId, opts = {}) {
      return self.call('PUT', `/accounts/${encodeURIComponent(accountId)}/groups/${encodeURIComponent(groupId)}/kept`, { body: pick(opts, ['enabled']) })
    },
    /**
     * 群二维码 —— 取群的邀请二维码，返回 data URL，可直接放进 img。
     *
     * GET /v1/accounts/{account_id}/groups/{group_id}/qrcode
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {string} groupId 群 ID
     */
    qrcode(accountId, groupId) {
      return self.call('GET', `/accounts/${encodeURIComponent(accountId)}/groups/${encodeURIComponent(groupId)}/qrcode`)
    },
    /**
     * 通过链接进群 —— 用收到的群邀请链接进群。
     *
     * POST /v1/accounts/{account_id}/groups/join
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {string} [opts.url] 必填 邀请链接
     */
    join(accountId, opts = {}) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/groups/join`, { body: pick(opts, ['url']) })
    },
    /**
     * 查看群邀请 —— 拿一个群邀请链接先看看是什么群，不进群。usable 为 false 时 notice 说明原因，比如链接已过期。
     *
     * POST /v1/accounts/{account_id}/groups/preview
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {string} [opts.url] 必填 邀请链接
     */
    preview(accountId, opts = {}) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/groups/preview`, { body: pick(opts, ['url']) })
    },
    /**
     * 同意入群邀请 —— 群成员邀请了人进群，群主在这里放行。四个参数都来自那条邀请事件。
     *
     * POST /v1/accounts/{account_id}/groups/{group_id}/approve
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {string} groupId 群 ID
     * @param {object} opts
     * @param {string} [opts.inviter] 必填 邀请人的 wxid
     * @param {Array} [opts.members] 必填 被邀请人的 wxid
     * @param {string} [opts.message_id] 必填 邀请事件里的消息 ID
     * @param {string} [opts.ticket] 必填 邀请事件里的凭据
     */
    approve(accountId, groupId, opts = {}) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/groups/${encodeURIComponent(groupId)}/approve`, { body: pick(opts, ['inviter', 'message_id', 'ticket', 'members']) })
    },
  }

  self.chat = {
    /**
     * 消息免打扰 —— 对一个群或一个好友开关消息免打扰。
     *
     * PUT /v1/accounts/{account_id}/chats/{chat_id}/muted
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {string} chatId 群 ID 或好友 wxid
     * @param {object} opts
     * @param {boolean} [opts.enabled] 必填 true 免打扰，false 恢复提醒
     */
    muted(accountId, chatId, opts = {}) {
      return self.call('PUT', `/accounts/${encodeURIComponent(accountId)}/chats/${encodeURIComponent(chatId)}/muted`, { body: pick(opts, ['enabled']) })
    },
    /**
     * 聊天置顶 —— 把一个群或一个好友的会话置顶，或取消置顶。
     *
     * PUT /v1/accounts/{account_id}/chats/{chat_id}/pinned
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {string} chatId 群 ID 或好友 wxid
     * @param {object} opts
     * @param {boolean} [opts.enabled] 必填 true 置顶，false 取消
     */
    pinned(accountId, chatId, opts = {}) {
      return self.call('PUT', `/accounts/${encodeURIComponent(accountId)}/chats/${encodeURIComponent(chatId)}/pinned`, { body: pick(opts, ['enabled']) })
    },
  }

  self.message = {
    /**
     * 发文字 —— 发一条文字消息。群里可以 @人。
     *
     * POST /v1/accounts/{account_id}/messages/text
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {string} [opts.content] 必填 消息正文
     * @param {string} [opts.to] 必填 接收者：wxid、群 ID，或 filehelper（自己的文件传输助手）
     * @param {Array} [opts.mentions] 要 @ 的 wxid，只在群里有意义
     * @param {Array} [opts.to_list] 一次发给多个接收者，与 to 二选一
     */
    text(accountId, opts = {}) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/messages/text`, { body: pick(opts, ['to', 'to_list', 'content', 'mentions']) })
    },
    /**
     * 发图片 —— 发一张图片。url 与 media_id 二选一，media_id 可以复用平台已存的文件。
     *
     * POST /v1/accounts/{account_id}/messages/image
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {string} [opts.to] 必填 接收者：wxid、群 ID，或 filehelper（自己的文件传输助手）
     * @param {string} [opts.media_id] 平台里已有的媒体 ID
     * @param {string} [opts.url] 公网可下载的地址
     * @param {boolean} [opts.use_cache] 同一个 url 之前发过就直接转发，不重新上传。默认 true；地址没变但内容换了才需要传 false
     */
    image(accountId, opts = {}) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/messages/image`, { body: pick(opts, ['to', 'url', 'media_id', 'use_cache']) })
    },
    /**
     * 发视频 —— 发一段视频。不填时长的话由平台估算，有些客户端会显示得不好看。
     *
     * POST /v1/accounts/{account_id}/messages/video
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {string} [opts.to] 必填 接收者：wxid、群 ID，或 filehelper（自己的文件传输助手）
     * @param {number} [opts.duration] 时长（秒）
     * @param {number} [opts.height] 画面高，不传则动态里不带尺寸
     * @param {string} [opts.media_id] 平台里已有的媒体 ID
     * @param {string} [opts.thumbnail_url] 封面图地址，公网可下载的一张图片
     * @param {string} [opts.url] 公网可下载的地址
     * @param {boolean} [opts.use_cache] 同一个 url 之前发过就直接转发，不重新上传。默认 true；地址没变但内容换了才需要传 false
     * @param {number} [opts.width] 画面宽，不传则动态里不带尺寸
     */
    video(accountId, opts = {}) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/messages/video`, { body: pick(opts, ['to', 'url', 'media_id', 'use_cache', 'duration', 'width', 'height', 'thumbnail_url']) })
    },
    /**
     * 发语音 —— 发一条语音。seconds 是时长，显示在气泡上。
     *
     * POST /v1/accounts/{account_id}/messages/voice
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {string} [opts.to] 必填 接收者：wxid、群 ID，或 filehelper（自己的文件传输助手）
     * @param {string} [opts.url] 必填 公网可下载的音频地址
     * @param {number} [opts.seconds] 时长（秒）
     */
    voice(accountId, opts = {}) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/messages/voice`, { body: pick(opts, ['to', 'url', 'seconds']) })
    },
    /**
     * 发文件 —— 发一个文件。
     *
     * POST /v1/accounts/{account_id}/messages/file
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {string} [opts.to] 必填 接收者：wxid、群 ID，或 filehelper（自己的文件传输助手）
     * @param {string} [opts.filename] 对方看到的文件名
     * @param {string} [opts.media_id] 平台里已有的媒体 ID
     * @param {string} [opts.url] 公网可下载的地址
     * @param {boolean} [opts.use_cache] 同一个 url 之前发过就直接转发，不重新上传。默认 true；地址没变但内容换了才需要传 false
     */
    file(accountId, opts = {}) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/messages/file`, { body: pick(opts, ['to', 'url', 'media_id', 'use_cache', 'filename']) })
    },
    /**
     * 发动图表情 —— 转发一个动图表情。表情是引用不是上传：checksum 与 length 来自收到的那条表情消息。
     *
     * POST /v1/accounts/{account_id}/messages/sticker
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {string} [opts.checksum] 必填 表情的校验值，来自收到的表情消息
     * @param {number} [opts.length] 必填 表情的字节数，来自同一条消息
     * @param {string} [opts.to] 必填 接收者：wxid、群 ID，或 filehelper（自己的文件传输助手）
     */
    sticker(accountId, opts = {}) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/messages/sticker`, { body: pick(opts, ['to', 'checksum', 'length']) })
    },
    /**
     * 发链接卡片 —— 发一张可点击的链接卡片。
     *
     * POST /v1/accounts/{account_id}/messages/link
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {string} [opts.title] 必填 卡片标题
     * @param {string} [opts.to] 必填 接收者：wxid、群 ID，或 filehelper（自己的文件传输助手）
     * @param {string} [opts.url] 必填 点击后打开的地址
     * @param {string} [opts.description] 卡片摘要
     * @param {string} [opts.source_name] 来源名称
     * @param {string} [opts.thumb_url] 封面图地址
     */
    link(accountId, opts = {}) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/messages/link`, { body: pick(opts, ['to', 'title', 'description', 'url', 'thumb_url', 'source_name']) })
    },
    /**
     * 发小程序卡片 —— 发一张小程序卡片。需要小程序自己的标识。
     *
     * POST /v1/accounts/{account_id}/messages/miniapp
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {string} [opts.app_id] 必填 小程序的公开标识
     * @param {string} [opts.title] 必填 卡片标题
     * @param {string} [opts.to] 必填 接收者：wxid、群 ID，或 filehelper（自己的文件传输助手）
     * @param {string} [opts.username] 必填 小程序的原始 ID
     * @param {string} [opts.description] 卡片摘要
     * @param {string} [opts.path] 打开的页面路径
     * @param {string} [opts.source_name] 来源名称
     * @param {string} [opts.thumb_url] 封面图地址
     */
    miniapp(accountId, opts = {}) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/messages/miniapp`, { body: pick(opts, ['to', 'app_id', 'username', 'title', 'description', 'path', 'thumb_url', 'source_name']) })
    },
    /**
     * 转发消息 —— 把收到过的一条消息原样转给别人。
     *
     * POST /v1/accounts/{account_id}/messages/forward
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {string} [opts.message_id] 必填 要转发的消息 ID
     * @param {string} [opts.to] 必填 接收者：wxid、群 ID，或 filehelper（自己的文件传输助手）
     */
    forward(accountId, opts = {}) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/messages/forward`, { body: pick(opts, ['to', 'message_id']) })
    },
    /**
     * 撤回消息 —— 撤回自己发出的一条消息。微信只允许发出后约两分钟内撤回，超时会被拒绝。
     *
     * POST /v1/accounts/{account_id}/messages/{message_id}/recall
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {string} messageId 发送时返回的 message_id
     */
    recall(accountId, messageId) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/messages/${encodeURIComponent(messageId)}/recall`)
    },
    /**
     * 消息记录 —— 读平台保存的消息记录，可按会话筛选。
     *
     * GET /v1/accounts/{account_id}/messages
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {string} [opts.cursor] 上一页返回的 next_cursor，首页留空
     * @param {number} [opts.limit] 每页条数，最多 200，超过按 200 处理
     * @param {string} [opts.peer] 只看与某个 wxid 或群的会话
     */
    history(accountId, opts = {}) {
      return self.call('GET', `/accounts/${encodeURIComponent(accountId)}/messages`, { query: pick(opts, ['peer', 'cursor', 'limit']) })
    },
    /**
     * 同步消息 —— 主动拉取这个实例收到的消息，内容和 Webhook 推的完全一样。没配 Webhook、Webhook 断过、或者服务重启过，用它把这段时间的消息补回来。cursor 留空从最早还留着的消息开始（大约一天），之后每次带上一次返回的 next_cursor；has_more 为 true 说明还没拉完，立刻再调一次。没有新消息时 next_cursor 原样返回，游标不动。拉到的消息不入库、不触发 Webhook，重复拉不会有副作用。
     *
     * POST /v1/accounts/{account_id}/messages/sync
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {string} [opts.cursor] 上一次返回的 next_cursor，第一次留空
     */
    sync(accountId, opts = {}) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/messages/sync`, { body: pick(opts, ['cursor']) })
    },
    /**
     * 消息详情 —— 读一条消息。
     *
     * GET /v1/accounts/{account_id}/messages/{message_id}
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {string} messageId 消息 ID
     */
    get(accountId, messageId) {
      return self.call('GET', `/accounts/${encodeURIComponent(accountId)}/messages/${encodeURIComponent(messageId)}`)
    },
  }

  self.favorite = {
    /**
     * 收藏列表 —— 列出这个实例收藏的内容。cursor 留空从头读，返回的 next_cursor 为空表示到底。
     *
     * GET /v1/accounts/{account_id}/favorites
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {string} [opts.cursor] 上一页返回的 next_cursor，首页留空
     */
    list(accountId, opts = {}) {
      return self.call('GET', `/accounts/${encodeURIComponent(accountId)}/favorites`, { query: pick(opts, ['cursor']) })
    },
    /**
     * 收藏详情 —— 读一条收藏的完整内容。内容是微信自己的 XML，不同类型结构不同，原样返回。
     *
     * GET /v1/accounts/{account_id}/favorites/{fav_id}
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {string} favId 收藏 ID，来自收藏列表
     */
    get(accountId, favId) {
      return self.call('GET', `/accounts/${encodeURIComponent(accountId)}/favorites/${encodeURIComponent(favId)}`)
    },
    /**
     * 删除收藏 —— 删掉一条收藏。
     *
     * DELETE /v1/accounts/{account_id}/favorites/{fav_id}
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {string} favId 收藏 ID
     */
    delete(accountId, favId) {
      return self.call('DELETE', `/accounts/${encodeURIComponent(accountId)}/favorites/${encodeURIComponent(favId)}`)
    },
  }

  self.media = {
    /**
     * 下载消息附件 —— 取一条消息里的图片、视频、文件或语音，返回一个限时下载地址。
     *
     * POST /v1/accounts/{account_id}/media/download
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {string} [opts.message_id] 必填 带附件的消息 ID
     */
    fromMessage(accountId, opts = {}) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/media/download`, { body: pick(opts, ['message_id']) })
    },
    /**
     * 查文件是否已缓存 —— 问一个地址平台是否已经发过。发过就能直接转发，不用重新上传，也不算流量 —— 在你把文件准备好挂到公网之前先问一句，省的就是这一趟。
     *
     * POST /v1/accounts/{account_id}/media/cached
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {string} [opts.kind] 必填 image、video 或 file
     * @param {string} [opts.url] 必填 要发的那个地址，和发送时填的一模一样才算命中
     */
    cached(accountId, opts = {}) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/media/cached`, { body: pick(opts, ['url', 'kind']) })
    },
    /**
     * 重新取下载地址 —— 为已经下载过的文件换一个新的限时地址。
     *
     * GET /v1/accounts/{account_id}/media/{media_id}
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {string} mediaId 媒体 ID
     */
    get(accountId, mediaId) {
      return self.call('GET', `/accounts/${encodeURIComponent(accountId)}/media/${encodeURIComponent(mediaId)}`)
    },
    /**
     * 下载动态媒体 —— 取一条朋友圈动态里的第 N 张图，或它的视频。
     *
     * POST /v1/accounts/{account_id}/moments/{moment_id}/media/download
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {string} momentId 动态 ID
     * @param {object} opts
     * @param {number} [opts.index] 第几张图，从 0 开始；视频动态忽略这个值
     */
    moment(accountId, momentId, opts = {}) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/moments/${encodeURIComponent(momentId)}/media/download`, { body: pick(opts, ['index']) })
    },
  }

  self.moment = {
    /**
     * 我的朋友圈 —— 读自己看到的朋友圈时间线。
     *
     * GET /v1/accounts/{account_id}/moments
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {string} [opts.cursor] 上一页返回的 next_cursor
     */
    timeline(accountId, opts = {}) {
      return self.call('GET', `/accounts/${encodeURIComponent(accountId)}/moments`, { query: pick(opts, ['cursor']) })
    },
    /**
     * 朋友圈详情 —— 读一条朋友圈。列表会截断点赞与评论，这里是完整的。
     *
     * GET /v1/accounts/{account_id}/moments/{moment_id}
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {string} momentId 朋友圈 ID
     */
    get(accountId, momentId) {
      return self.call('GET', `/accounts/${encodeURIComponent(accountId)}/moments/${encodeURIComponent(momentId)}`)
    },
    /**
     * 某人的朋友圈 —— 读某个联系人的朋友圈主页。
     *
     * GET /v1/accounts/{account_id}/moments/user/{wxid}
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {string} wxid 联系人的 wxid
     * @param {object} opts
     * @param {string} [opts.cursor] 上一页返回的 next_cursor
     */
    user(accountId, wxid, opts = {}) {
      return self.call('GET', `/accounts/${encodeURIComponent(accountId)}/moments/user/${encodeURIComponent(wxid)}`, { query: pick(opts, ['cursor']) })
    },
    /**
     * 发文字动态 —— 发一条纯文字朋友圈。
     *
     * POST /v1/accounts/{account_id}/moments/text
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {string} [opts.content] 必填 正文
     * @param {Array} [opts.mentions] 要 @ 的 wxid
     * @param {object} [opts.visibility] 可见范围：mode 为 public / private / allow / deny，后两种要给 wxids 或 tag_ids
     */
    postText(accountId, opts = {}) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/moments/text`, { body: pick(opts, ['content', 'mentions', 'visibility']) })
    },
    /**
     * 发图片动态 —— 发一条带图的朋友圈。
     *
     * POST /v1/accounts/{account_id}/moments/images
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {Array} [opts.images] 必填 图片列表
     * @param {string} [opts.content] 正文
     * @param {object} [opts.visibility] 可见范围：mode 为 public / private / allow / deny，后两种要给 wxids 或 tag_ids
     */
    postImages(accountId, opts = {}) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/moments/images`, { body: pick(opts, ['content', 'images', 'visibility']) })
    },
    /**
     * 发视频动态 —— 发一条视频朋友圈。
     *
     * POST /v1/accounts/{account_id}/moments/video
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {object} [opts.video] 必填 视频，给一个公网可下载的地址
     * @param {string} [opts.content] 正文
     * @param {object} [opts.cover] 封面图
     * @param {number} [opts.duration] 时长（秒）
     * @param {object} [opts.visibility] 可见范围：mode 为 public / private / allow / deny，后两种要给 wxids 或 tag_ids
     */
    postVideo(accountId, opts = {}) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/moments/video`, { body: pick(opts, ['content', 'video', 'cover', 'duration', 'visibility']) })
    },
    /**
     * 转发动态 —— 把看到的一条动态原样再发一遍。
     *
     * POST /v1/accounts/{account_id}/moments/forward
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {string} [opts.moment_id] 必填 要转发的动态 ID
     * @param {object} [opts.visibility] 可见范围：mode 为 public / private / allow / deny，后两种要给 wxids 或 tag_ids
     */
    repost(accountId, opts = {}) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/moments/forward`, { body: pick(opts, ['moment_id', 'visibility']) })
    },
    /**
     * 点赞 —— 给一条动态点赞。动态要先读过一次，24 小时内有效。
     *
     * POST /v1/accounts/{account_id}/moments/{moment_id}/like
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {string} momentId 动态 ID
     */
    like(accountId, momentId) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/moments/${encodeURIComponent(momentId)}/like`)
    },
    /**
     * 取消赞 —— 取消对一条动态的赞。
     *
     * DELETE /v1/accounts/{account_id}/moments/{moment_id}/like
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {string} momentId 动态 ID
     */
    unlike(accountId, momentId) {
      return self.call('DELETE', `/accounts/${encodeURIComponent(accountId)}/moments/${encodeURIComponent(momentId)}/like`)
    },
    /**
     * 评论 —— 评论一条动态，或回复别人的评论。
     *
     * POST /v1/accounts/{account_id}/moments/{moment_id}/comments
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {string} momentId 动态 ID
     * @param {object} opts
     * @param {string} [opts.content] 必填 评论内容，最多 500 字
     * @param {number} [opts.reply_to] 要回复的评论 ID，留空为一级评论
     */
    comment(accountId, momentId, opts = {}) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/moments/${encodeURIComponent(momentId)}/comments`, { body: pick(opts, ['content', 'reply_to']) })
    },
    /**
     * 删除评论 —— 删掉自己发的一条评论。
     *
     * DELETE /v1/accounts/{account_id}/moments/{moment_id}/comments/{comment_id}
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {string} momentId 动态 ID
     * @param {string} commentId 评论 ID
     */
    deleteComment(accountId, momentId, commentId) {
      return self.call('DELETE', `/accounts/${encodeURIComponent(accountId)}/moments/${encodeURIComponent(momentId)}/comments/${encodeURIComponent(commentId)}`)
    },
    /**
     * 删除动态 —— 删掉自己发的一条朋友圈。
     *
     * DELETE /v1/accounts/{account_id}/moments/{moment_id}
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {string} momentId 动态 ID
     */
    delete(accountId, momentId) {
      return self.call('DELETE', `/accounts/${encodeURIComponent(accountId)}/moments/${encodeURIComponent(momentId)}`)
    },
    /**
     * 设为私密 / 公开 —— 把自己的一条动态设为仅自己可见，或改回公开。
     *
     * PUT /v1/accounts/{account_id}/moments/{moment_id}/privacy
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {string} momentId 动态 ID
     * @param {object} opts
     * @param {boolean} [opts.private] 必填 true 为仅自己可见
     */
    privacy(accountId, momentId, opts = {}) {
      return self.call('PUT', `/accounts/${encodeURIComponent(accountId)}/moments/${encodeURIComponent(momentId)}/privacy`, { body: pick(opts, ['private']) })
    },
  }

  self.platform = {
    /**
     * 当前用户 —— 读这个 Key 属于谁，以及实例数量。
     *
     * GET /v1/me
     */
    me() {
      return self.call('GET', '/me')
    },
    /**
     * 事件列表 —— 读平台记录的事件，可按实例、类型、消息类型与时间筛选。没配 Webhook 时可以轮询这里。
     *
     * GET /v1/events
     * @param {object} opts
     * @param {string} [opts.account_id] 只看某个实例
     * @param {string} [opts.cursor] 上一页返回的 next_cursor，首页留空
     * @param {string} [opts.keyword] 按事件内容搜索。需要同时给时间范围，且不超过 1 小时
     * @param {number} [opts.limit] 每页条数，最多 200，超过按 200 处理
     * @param {string} [opts.message_type] 只看某种消息，如 text、image、file，可重复；非消息事件不会命中
     * @param {string} [opts.order] oldest 从头逐条读（默认），newest 先看最近发生的（oldest / newest）
     * @param {string} [opts.since] 只看这个时间之后的，RFC3339 或 Unix 秒
     * @param {string} [opts.type] 只看某种事件，可重复
     * @param {string} [opts.until] 只看这个时间之前的，RFC3339 或 Unix 秒
     */
    events(opts = {}) {
      return self.call('GET', '/events', { query: pick(opts, ['account_id', 'type', 'message_type', 'since', 'until', 'keyword', 'order', 'cursor', 'limit']) })
    },
    /**
     * 事件流 —— 以 SSE 长连接实时接收该实例的事件，内容与 Webhook 相同。
     *
     * GET /v1/accounts/{account_id}/stream
     * @param {string} accountId 实例 ID，形如 acc_xxx
     */
    stream(accountId) {
      return self.call('GET', `/accounts/${encodeURIComponent(accountId)}/stream`)
    },
  }

}
