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
 * 接口方法按接口清单整理；请求、验签和事件流逻辑在本文件维护。
 */

import { createHmac, timingSafeEqual } from 'node:crypto'

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

  /**
   * 以 multipart/form-data 上传一个文件。
   *
   * file 可以是 Buffer、Uint8Array、Blob，或者一个本地路径（字符串）。
   */
  async upload(path, file, { filename, contentType, fields } = {}) {
    let data = file
    if (typeof file === 'string') {
      const { readFile } = await import('node:fs/promises')
      const { basename } = await import('node:path')
      data = await readFile(file)
      filename = filename || basename(file)
    }
    const form = new FormData()
    for (const [k, v] of Object.entries(fields || {})) {
      if (v === undefined || v === null || v === '') continue
      form.append(k, String(v))
    }
    const blob = data instanceof Blob
      ? data
      : new Blob([data], { type: contentType || 'application/octet-stream' })
    form.append('file', blob, filename || 'file')

    const stop = AbortSignal.timeout(this.timeout)
    let res
    try {
      // No Content-Type here on purpose: fetch sets it with the boundary.
      res = await fetch(this.baseUrl + '/v1' + path, {
        method: 'POST',
        headers: { Authorization: 'Bearer ' + this.apiKey, Accept: 'application/json' },
        body: form,
        signal: stop,
      })
    } catch (err) {
      throw new WeLinkError(0, `连不上服务：${err.message}`)
    }
    let text
    try { text = await res.text() }
    catch (err) { throw new WeLinkError(0, `读响应失败：${err.message}`, '', res.status) }
    let envelope
    try {
      envelope = JSON.parse(text)
    } catch {
      throw new WeLinkError(0, `服务返回的不是 JSON（HTTP ${res.status}）`, '', res.status)
    }
    if (!envelope || typeof envelope !== 'object' || Array.isArray(envelope) || !Number.isInteger(envelope.code)) {
      throw new WeLinkError(0, '服务返回的不是预期的结构', '', res.status)
    }
    if (!res.ok || envelope.code !== 0) {
      throw new WeLinkError(envelope.code || 0, envelope.message || '上传失败',
        envelope.request_id || '', res.status)
    }
    return envelope.data
  }

  /** SSE 逐条读取；退出 for-await 或取消 signal 会关闭连接。 */
  async *streamEvents(path, { signal } = {}) {
    const controller = new AbortController()
    const abort = () => controller.abort(signal?.reason)
    if (signal?.aborted) abort()
    else signal?.addEventListener('abort', abort, { once: true })
    // 超时只限制建立连接，不终止正常运行的事件流。
    const timer = setTimeout(() => controller.abort(), this.timeout)
    let reader
    try {
      const res = await fetch(this.baseUrl + '/v1' + path, {
        headers: { Authorization: 'Bearer ' + this.apiKey, Accept: 'text/event-stream' },
        signal: controller.signal,
      })
      clearTimeout(timer)
      if (!res.ok) {
        let body = {}
        try { body = await res.json() } catch {}
        throw new WeLinkError(body?.code || 0, body?.message || '无法打开事件流', body?.request_id || '', res.status)
      }
      if (!res.headers.get('content-type')?.startsWith('text/event-stream') || !res.body) {
        throw new WeLinkError(0, '服务没有返回事件流', '', res.status)
      }
      reader = res.body.getReader()
      const decoder = new TextDecoder()
      let buffer = '', data = []
      while (true) {
        const { value, done } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        let end
        while ((end = buffer.indexOf('\n')) >= 0) {
          const line = buffer.slice(0, end).replace(/\r$/, '')
          buffer = buffer.slice(end + 1)
          if (!line) {
            if (data.length) {
              let event
              try { event = JSON.parse(data.join('\n')) }
              catch { throw new WeLinkError(0, '事件内容不是 JSON') }
              data = []
              yield event
            }
          } else if (line.startsWith('data:')) {
            data.push(line.slice(5).replace(/^ /, ''))
          }
        }
      }
    } catch (err) {
      if (err instanceof WeLinkError) throw err
      throw new WeLinkError(0, `事件流连接中断：${err.message}`)
    } finally {
      clearTimeout(timer)
      signal?.removeEventListener('abort', abort)
      if (reader) { await reader.cancel().catch(() => {}); reader.releaseLock() }
      controller.abort()
    }
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

    let text
    try { text = await res.text() }
    catch (err) { throw new WeLinkError(0, `读响应失败：${err.message}`, '', res.status) }
    let envelope
    try {
      envelope = JSON.parse(text)
    } catch {
      throw new WeLinkError(0, `服务返回的不是 JSON（HTTP ${res.status}）`, '', res.status)
    }
    if (!envelope || typeof envelope !== 'object' || Array.isArray(envelope) || !Number.isInteger(envelope.code)) {
      throw new WeLinkError(0, '服务返回的不是预期的结构', '', res.status)
    }
    if (!res.ok || envelope.code !== 0) {
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
     * 创建实例 —— 创建一个实例。每个实例占用一个额度，删除实例后额度归还。实例创建后需要扫码登录才会上线。
     *
     * POST /v1/accounts
     * @param {object} opts
     * @param {string} [opts.proxy] 必填 代理网络，必填，不能直连。有两种填法：socks5 代理地址，如 socks5://user:pass@host:port；网络助手的网络ID，在一台手机上安装并打开网络助手即可看到，实例将通过这台手机的网络连接微信。…
     * @param {boolean} [opts.keep_history] 是否保存收发的消息和推送记录，默认 true。设为 false 时，消息不写入数据库，推送记录在投递结束后立即删除。图片等文件仍可下载，撤回功能仍可使用；但无法查询历史消息，也无法转发文字和卡片消息
     * @param {string} [opts.name] 备注名称，仅自己可见
     * @param {string} [opts.platform] 登录方式，留空时使用默认方式。并非每个部署都同时开通了两种方式。选择未开通的方式会直接报错，错误信息中会列出可选的方式（ipad / mac）
     * @param {string} [opts.webhook_url] 接收该实例事件的 Webhook 地址
     */
    create(opts = {}) {
      return self.call('POST', '/accounts', { body: pick(opts, ['platform', 'name', 'proxy', 'webhook_url', 'keep_history']) })
    },
    /**
     * 实例列表 —— 列出你的全部实例及其状态。
     *
     * GET /v1/accounts
     */
    list() {
      return self.call('GET', '/accounts')
    },
    /**
     * 实例详情 —— 查询一个实例的详情。实例不在线时，reason 字段说明原因：manual 表示主动退出，kicked 表示因其他设备登录而被挤下线，relogin_required 表示需要重新扫码登录，recover_timeout 表示自动恢复超时，expired 表示授权到期。status 为 recovering 时，recovering 字段给出恢复方式和放弃恢复的时间。
     *
     * GET /v1/accounts/{account_id}
     * @param {string} accountId 实例 ID，形如 acc_xxx
     */
    get(accountId) {
      return self.call('GET', `/accounts/${encodeURIComponent(accountId)}`)
    },
    /**
     * 获取登录二维码 —— 获取一张登录二维码，用手机微信扫码登录。expires_in 是二维码剩余的有效秒数，请以返回值为准，不要写死。二维码过期后重新获取即可。请求时可以带上 proxy 来更换代理网络。代理网络在建立登录会话时确定，更换后需要重新建立会话，所以只能在获取二维码时更换。不传 proxy 则沿用原来的代理网络。
     *
     * POST /v1/accounts/{account_id}/login/qrcode
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {string} [opts.proxy] 要改用的代理网络，可以是socks5 地址或网络助手的网络ID。不传则沿用实例现有的代理网络。不能传空字符串，因为实例必须配置代理网络，不允许直连
     */
    qrcode(accountId, opts = {}) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/login/qrcode`, { body: pick(opts, ['proxy']) })
    },
    /**
     * 登录状态 —— 查询扫码登录的进度，供轮询使用。状态取值：waiting（等待扫码）、scanned（已扫码，等待确认）、verify（等待验证）、online（已上线）、cancelled（已取消）、expired（已过期）。状态为 waiting 时还会返回 expires_in，表示二维码此刻剩余的有效秒数，可用于校准倒计时。返回 notice 时，请把它原样展示给用户。Mac 端扫码后需要通过一次新设备验证，平台会自动完成这一步，这期间状态会一直保持为 scanned。请提示用户耐心等待，避免用户误以为登录卡住而取消登录。
     *
     * GET /v1/accounts/{account_id}/login/status
     * @param {string} accountId 实例 ID，形如 acc_xxx
     */
    loginStatus(accountId) {
      return self.call('GET', `/accounts/${encodeURIComponent(accountId)}/login/status`)
    },
    /**
     * 重新连接 —— 实例掉线后，尝试在不重新扫码的情况下恢复连接。无法恢复时，才需要重新扫码登录。
     *
     * POST /v1/accounts/{account_id}/reconnect
     * @param {string} accountId 实例 ID，形如 acc_xxx
     */
    reconnect(accountId) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/reconnect`)
    },
    /**
     * 退出登录 —— 让实例下线。实例和额度都会保留，之后可以重新扫码上线。
     *
     * POST /v1/accounts/{account_id}/logout
     * @param {string} accountId 实例 ID，形如 acc_xxx
     */
    logout(accountId) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/logout`)
    },
    /**
     * 删除实例 —— 删除实例并归还额度。历史消息不会立即清除。在线的实例不能直接删除，请先调用「退出登录」。如果直接删除，微信端的登录会话会继续保持，而平台已经无法再关闭它。
     *
     * DELETE /v1/accounts/{account_id}
     * @param {string} accountId 实例 ID，形如 acc_xxx
     */
    delete(accountId) {
      return self.call('DELETE', `/accounts/${encodeURIComponent(accountId)}`)
    },
    /**
     * 实例资料 —— 查询这个实例自己的昵称、头像、地区等资料。
     *
     * 建议缓存：资料很少变化，登录成功后获取一次并保存即可。平时需要 wxid、昵称、头像时，请读取「实例详情」中的 profile 字段。该字段来自平台已保存的数据，不会向微信发起请求。
     *
     * GET /v1/accounts/{account_id}/profile
     * @param {string} accountId 实例 ID，形如 acc_xxx
     */
    profile(accountId) {
      return self.call('GET', `/accounts/${encodeURIComponent(accountId)}/profile`)
    },
    /**
     * 修改个人资料 —— 修改昵称、签名、性别和地区。留空的字段会被清空，请把需要保留的字段一并传入。
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
     * 修改头像 —— 修改头像。
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
     * 我的二维码 —— 获取这个实例自己的名片二维码。返回 data URL，可直接用作 img 标签的 src。
     *
     * 建议缓存：名片二维码基本不会变化。获取一次后保存为图片重复使用，不要每次展示时都重新获取。
     *
     * GET /v1/accounts/{account_id}/profile/qrcode
     * @param {string} accountId 实例 ID，形如 acc_xxx
     */
    qrcodeSelf(accountId) {
      return self.call('GET', `/accounts/${encodeURIComponent(accountId)}/profile/qrcode`)
    },
    /**
     * 隐私设置 —— 开启或关闭一项隐私设置。
     *
     * PUT /v1/accounts/{account_id}/privacy
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {boolean} [opts.enabled] 必填 true 开启，false 关闭
     * @param {string} [opts.option] 必填 need_confirm_to_add：加我为好友时需要验证；findable_by_phone：可以通过手机号搜到我；findable_by_alias：可以通过微信号搜到我；recommend_contacts：向…（need_confirm_to_add / findable_by_phone / findable_by_alias / recommend_contacts / strangers_see_ten / visible_days）
     */
    privacy(accountId, opts = {}) {
      return self.call('PUT', `/accounts/${encodeURIComponent(accountId)}/privacy`, { body: pick(opts, ['option', 'enabled']) })
    },
    /**
     * 已登录设备 —— 列出这个微信号登录过的设备，其中包括本平台。
     *
     * GET /v1/accounts/{account_id}/devices
     * @param {string} accountId 实例 ID，形如 acc_xxx
     */
    devices(accountId) {
      return self.call('GET', `/accounts/${encodeURIComponent(accountId)}/devices`)
    },
    /**
     * 下线某个设备 —— 让某个已登录的设备强制下线。注意不要把本平台自己也下线。
     *
     * DELETE /v1/accounts/{account_id}/devices/{device_id}
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {string} deviceId 设备 ID，从「已登录设备」接口获取
     */
    deviceSignout(accountId, deviceId) {
      return self.call('DELETE', `/accounts/${encodeURIComponent(accountId)}/devices/${encodeURIComponent(deviceId)}`)
    },
    /**
     * 消息保存设置 —— 设置这个实例是否保存收发的消息和推送记录。关闭后，新收发的消息不写入数据库，Webhook 推送记录在投递成功或放弃重试后立即删除。图片、语音、视频、文件仍然可以下载，自己发的消息仍然可以撤回，重复的推送仍然会去重。但无法查询历史消息，也无法转发文字和卡片消息。事件仍然会保存。关闭前已保存的消息不会立即删除，会按原来的保存期限自动清理。
     *
     * PUT /v1/accounts/{account_id}/history
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {boolean} [opts.keep] 必填 true 保存，false 不保存
     */
    history(accountId, opts = {}) {
      return self.call('PUT', `/accounts/${encodeURIComponent(accountId)}/history`, { body: pick(opts, ['keep']) })
    },
    /**
     * 设置 Webhook —— 设置接收该实例事件的 Webhook 地址。每次推送都带有签名，可以用 secret 校验。
     *
     * PUT /v1/accounts/{account_id}/webhook
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {string} [opts.url] 必填 接收事件的地址
     * @param {Array} [opts.events] 只推送这些类型的事件，留空则推送全部事件
     * @param {string} [opts.secret] 签名密钥，留空则保持不变
     */
    webhook(accountId, opts = {}) {
      return self.call('PUT', `/accounts/${encodeURIComponent(accountId)}/webhook`, { body: pick(opts, ['url', 'secret', 'events']) })
    },
  }

  self.contact = {
    /**
     * 通讯录列表 —— 列出通讯录中的全部条目，不做任何筛选，只返回标识：好友为 wxid，群 ID 以 @chatroom 结尾，公众号以 gh_ 开头。需要资料时，再用「联系人详情」按需查询。数据直接从微信获取，实例需要在线。每页条数由微信决定。翻页时把 next_cursor 原样传回，next_cursor 为空表示已经到最后一页。
     *
     * 建议缓存：登录成功后拉一次完整列表，保存在你自己的系统中，之后根据事件更新：收到 friend.added 时添加新好友，收到 contact.updated 时更新联系人资料，收到 contact.deleted 时移除联系人。不要定时整份重拉：每次调用都会从微信拉取完整列表，联系人多时耗时长、开销大，频繁拉取还会增加被微信风控的概率。
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
     * 批量取详情 —— 按 wxid 批量查询联系人资料。它和「联系人详情」调用的是微信的两个不同接口，一次查询很多人时，更适合用这个接口。 对个人好友，还会返回加好友的时间和方式：added_at、added_ts 是添加时间；add_source 是微信记录的添加方式编号，add_source_text 是它的中文说明，比如「扫一扫」「群聊」「搜索手机号」「名片分享」。含义还没有确认的编号只返回 add_source，不返回中文说明。通过群聊加的好友，add_source_group 是来源群的 ID。微信没有记录的项不返回。群和公众号不返回这几个字段。 所有联系人还会返回：avatar_large 高清头像（…
     *
     * 建议缓存：按 wxid 保存查到的资料，收到 contact.updated 事件时再更新对应联系人的资料。不要每收到一条消息就查询一次发送人的资料。
     *
     * POST /v1/accounts/{account_id}/contacts/batch
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {Array} [opts.wxids] 必填 要查询的 wxid 列表
     */
    batch(accountId, opts = {}) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/contacts/batch`, { body: pick(opts, ['wxids']) })
    },
    /**
     * 联系人详情 —— 查询联系人的完整资料，包括昵称、备注、微信号、头像、性别、地区、签名，以及该联系人的标签。标签在 label_ids 字段中，对应「标签列表」里的 ID；联系人没有标签时不返回这个字段。个人好友还会返回加好友的方式 add_source 和 add_source_text，通过群聊加的好友还有来源群 add_source_group；加好友的时间只有「批量取详情」能查到，这里不返回。高清头像 avatar_large、拼音 pinyin、备注电话 phones 和「批量取详情」一样返回。
     *
     * 建议缓存：按 wxid 保存查到的资料，收到 contact.updated 事件时再更新对应联系人的资料。不要每收到一条消息就查询一次发送人的资料。
     *
     * POST /v1/accounts/{account_id}/contacts/detail
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {Array} [opts.wxids] 必填 要查询的 wxid，一次最多 50 个
     */
    detail(accountId, opts = {}) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/contacts/detail`, { body: pick(opts, ['wxids']) })
    },
    /**
     * 检测好友关系 —— 检测这些人是否仍是你的好友。注意：微信对这个操作限制很严，一次检测的人数多或检测频繁，都可能导致实例被限制。一次最多检测 20 个，请按需使用。
     *
     * 建议缓存：保存检测结果，同一个人在短时间内不要重复检测。
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
     * 企微联系人 —— 查询企业微信的外部联系人。这些联系人不在普通通讯录中，「通讯录列表」接口查不到他们。本接口返回平台已保存的数据，使用前请先调用一次「同步企微联系人」。
     *
     * GET /v1/accounts/{account_id}/contacts/external
     * @param {string} accountId 实例 ID，形如 acc_xxx
     */
    external(accountId) {
      return self.call('GET', `/accounts/${encodeURIComponent(accountId)}/contacts/external`)
    },
    /**
     * 同步企微联系人 —— 从微信重新拉取企业微信的外部联系人并保存到平台，返回拉取到的人数。没有头像的联系人会逐个补充获取头像，人数多时耗时较长，不建议频繁调用。
     *
     * POST /v1/accounts/{account_id}/contacts/external/sync
     * @param {string} accountId 实例 ID，形如 acc_xxx
     */
    externalSync(accountId) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/contacts/external/sync`)
    },
    /**
     * 搜索用户 —— 按微信号或手机号搜索用户，返回可用于添加好友的 contact_token。
     *
     * 建议缓存：保存搜索到的 wxid 和昵称，不要反复搜索同一个号。搜索过于频繁时，微信会提示操作过于频繁，之后一段时间内都无法搜索。contact_token 会过期，真正要添加好友时，再搜索一次获取新的 contact_token。
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
     * 添加好友 —— 用搜索得到的 contact_token 发起好友申请。**这个接口响应较慢**：微信需要 5～20 秒才返回结果，实测平均 9 秒，最慢 16 秒。客户端超时时间请至少设为 30 秒。请求超时后不要直接重发，因为请求很可能已经发送成功。需要重试时，请带上 Idempotency-Key。
     *
     * 注意（易封号）：敏感接口，调用不当容易被微信限制甚至封号。添加好友是微信风控最严格的操作之一。不要在短时间内连续添加，不要批量自动加人，每次添加之间要留出间隔。新注册的号、刚换设备或刚登录的号风险更高，建议先正常使用几天再添加好友。
     *
     * POST /v1/accounts/{account_id}/contacts/add
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {string} [opts.contact_token] 必填 搜索结果中的 contact_token
     * @param {string} [opts.greeting] 发给对方的验证消息
     * @param {string} [opts.scene] 申请来源，留空则使用默认值
     */
    add(accountId, opts = {}) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/contacts/add`, { body: pick(opts, ['contact_token', 'greeting', 'scene']) })
    },
    /**
     * 通过好友申请 —— 通过他人的好友申请。需要传入好友申请事件中的 friend_request_token。
     *
     * 注意（易封号）：敏感接口，调用不当容易被微信限制甚至封号。短时间内大量通过好友申请同样会触发风控。不要在收到申请后立即批量自动通过，每次通过之间要留出间隔；申请数量多时，请分散到不同时间段处理。
     *
     * POST /v1/accounts/{account_id}/contacts/accept
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {string} [opts.friend_request_token] 必填 好友申请事件中的 friend_request_token
     */
    accept(accountId, opts = {}) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/contacts/accept`, { body: pick(opts, ['friend_request_token']) })
    },
    /**
     * 设置备注 —— 修改一个联系人的备注名。
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
     * 删除好友 —— 将联系人从通讯录中删除。对方不会收到通知，但之后无法再给你发消息。如需恢复，需要重新添加好友。
     *
     * DELETE /v1/accounts/{account_id}/contacts/{wxid}
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {string} wxid 要删除的 wxid
     */
    delete(accountId, wxid) {
      return self.call('DELETE', `/accounts/${encodeURIComponent(accountId)}/contacts/${encodeURIComponent(wxid)}`)
    },
    /**
     * 设置联系人的标签 —— 为指定的联系人设置标签。设置采用覆盖方式：这些联系人原有的标签会全部替换为本次传入的标签。label_ids 传空数组表示移除他们的全部标签。不在 wxids 中的联系人不受影响。给某些联系人设置一个标签，不会把这个标签从其他联系人身上移除。
     *
     * PUT /v1/accounts/{account_id}/contacts/labels
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {Array} [opts.label_ids] 必填 设置后这些联系人拥有的全部标签 ID，对应「标签列表」里的 ID。传空数组表示不带任何标签
     * @param {Array} [opts.wxids] 必填 要设置标签的联系人 wxid，一次最多 50 个
     */
    labels(accountId, opts = {}) {
      return self.call('PUT', `/accounts/${encodeURIComponent(accountId)}/contacts/labels`, { body: pick(opts, ['wxids', 'label_ids']) })
    },
  }

  self.label = {
    /**
     * 标签列表 —— 列出这个实例的联系人标签。标签仅自己可见。
     *
     * 建议缓存：标签只有在你自己修改时才会变化。获取一次并保存，之后在新建、改名或删除标签后，再更新你保存的数据。
     *
     * GET /v1/accounts/{account_id}/labels
     * @param {string} accountId 实例 ID，形如 acc_xxx
     */
    list(accountId) {
      return self.call('GET', `/accounts/${encodeURIComponent(accountId)}/labels`)
    },
    /**
     * 新建标签 —— 新建一个联系人标签，返回该标签的 label_id。
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
     * 改标签名 —— 修改一个标签的名称。
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
     * 删除标签 —— 删除一个标签。带有这个标签的联系人本身不受影响，只是不再带有该标签。
     *
     * DELETE /v1/accounts/{account_id}/labels/{label_id}
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {string} labelId 标签 ID
     */
    delete(accountId, labelId) {
      return self.call('DELETE', `/accounts/${encodeURIComponent(accountId)}/labels/${encodeURIComponent(labelId)}`)
    },
  }

  self.group = {
    /**
     * 创建群聊 —— 邀请几位好友创建一个群聊，至少需要两个成员。
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
     * 群详情 —— 查询群的名称、公告、群主等资料。
     *
     * 建议缓存：保存群资料，收到 group.renamed 事件时再重新获取。公告和群主很少变化，不要每收到一条群消息就查询一次。
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
     * 建议缓存：保存成员列表，之后根据 group.member_joined 和 group.member_left 事件增减成员。每次调用都会实时从微信拉取，大群耗时长、开销大，不要定时重新拉取整个列表。
     *
     * GET /v1/accounts/{account_id}/groups/{group_id}/members
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {string} groupId 群 ID
     */
    members(accountId, groupId) {
      return self.call('GET', `/accounts/${encodeURIComponent(accountId)}/groups/${encodeURIComponent(groupId)}/members`)
    },
    /**
     * 群成员详情 —— 查询指定群成员的完整资料，字段比「群成员」接口更全。
     *
     * 建议缓存：按 wxid 保存成员资料，不要每收到一条群消息就查询一次发言人的资料。
     *
     * POST /v1/accounts/{account_id}/groups/{group_id}/members/detail
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {string} groupId 群 ID
     * @param {object} opts
     * @param {Array} [opts.members] 必填 要查询的 wxid
     */
    memberDetail(accountId, groupId, opts = {}) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/groups/${encodeURIComponent(groupId)}/members/detail`, { body: pick(opts, ['members']) })
    },
    /**
     * 邀请入群 —— 邀请好友入群。群人数较多时，微信会改为发送邀请链接。
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
     * 移出群成员 —— 将成员移出群聊。只有群主和管理员可以操作。
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
     * 群管理员 —— 设置或取消群管理员，也可以转让群主。只有群主可以操作。
     *
     * POST /v1/accounts/{account_id}/groups/{group_id}/admins
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {string} groupId 群 ID
     * @param {object} opts
     * @param {string} [opts.action] 必填 grant 设为管理员，revoke 取消管理员，transfer 转让群主（转让群主时 members 只能填一个人）（grant / revoke / transfer）
     * @param {Array} [opts.members] 必填 目标成员的 wxid
     */
    admins(accountId, groupId, opts = {}) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/groups/${encodeURIComponent(groupId)}/admins`, { body: pick(opts, ['action', 'members']) })
    },
    /**
     * 修改群名 —— 修改群名称。需要有修改群名称的权限。
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
     * 设置群公告 —— 修改群公告。只有群主和管理员可以操作，修改后会向全群发送一条提示。
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
     * 设置群备注 —— 为群设置一个仅自己可见的备注名。
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
     * 设置我的群昵称 —— 修改自己在这个群里显示的昵称。
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
     * 保存到通讯录 —— 将群保存到通讯录，或取消保存。没有保存到通讯录的群，在聊天会话被删除后将无法再找到。
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
     * 群二维码 —— 获取群的邀请二维码。返回 data URL，可直接用作 img 标签的 src。
     *
     * 建议缓存：群二维码 7 天内有效。获取一次后保存为图片，快过期时再重新获取。
     *
     * GET /v1/accounts/{account_id}/groups/{group_id}/qrcode
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {string} groupId 群 ID
     */
    qrcode(accountId, groupId) {
      return self.call('GET', `/accounts/${encodeURIComponent(accountId)}/groups/${encodeURIComponent(groupId)}/qrcode`)
    },
    /**
     * 通过链接进群 —— 通过收到的群邀请链接加入群聊。
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
     * 查看群邀请 —— 查看群邀请链接对应的群信息，不会加入该群。usable 为 false 时，notice 字段说明原因，比如链接已过期。
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
     * 同意入群邀请 —— 群成员邀请他人入群后，群主用这个接口同意邀请。inviter、message_id、ticket、members 四个参数都来自这条邀请事件。
     *
     * POST /v1/accounts/{account_id}/groups/{group_id}/approve
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {string} groupId 群 ID
     * @param {object} opts
     * @param {string} [opts.inviter] 必填 邀请人的 wxid
     * @param {Array} [opts.members] 必填 被邀请人的 wxid
     * @param {string} [opts.message_id] 必填 邀请事件中的消息 ID
     * @param {string} [opts.ticket] 必填 邀请事件中的凭据
     */
    approve(accountId, groupId, opts = {}) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/groups/${encodeURIComponent(groupId)}/approve`, { body: pick(opts, ['inviter', 'message_id', 'ticket', 'members']) })
    },
  }

  self.chat = {
    /**
     * 消息免打扰 —— 为一个群或一个好友开启或关闭消息免打扰。
     *
     * PUT /v1/accounts/{account_id}/chats/{chat_id}/muted
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {string} chatId 群 ID 或好友 wxid
     * @param {object} opts
     * @param {boolean} [opts.enabled] 必填 true 开启免打扰，false 恢复消息提醒
     */
    muted(accountId, chatId, opts = {}) {
      return self.call('PUT', `/accounts/${encodeURIComponent(accountId)}/chats/${encodeURIComponent(chatId)}/muted`, { body: pick(opts, ['enabled']) })
    },
    /**
     * 聊天置顶 —— 将一个群或一个好友的会话置顶，或取消置顶。
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
     * 发文字 —— 发送一条文字消息。在群里发送时可以 @ 群成员。
     *
     * POST /v1/accounts/{account_id}/messages/text
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {string} [opts.content] 必填 消息正文
     * @param {string} [opts.to] 必填 接收方：好友的 wxid、群 ID，或 filehelper（自己的文件传输助手）
     * @param {Array} [opts.mentions] 要 @ 的成员 wxid，仅在群聊中有效
     */
    text(accountId, opts = {}) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/messages/text`, { body: pick(opts, ['to', 'content', 'mentions']) })
    },
    /**
     * 发图片 —— 发送一张图片。url 和 media_id 二选一，使用 media_id 可以复用平台已保存的文件。
     *
     * POST /v1/accounts/{account_id}/messages/image
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {string} [opts.to] 必填 接收方：好友的 wxid、群 ID，或 filehelper（自己的文件传输助手）
     * @param {string} [opts.media_id] 平台中已有文件的媒体 ID
     * @param {string} [opts.url] 可从公网下载的文件地址
     * @param {boolean} [opts.use_cache] 同一个 url 之前发送过时，直接复用已上传的文件，不再重新上传。默认 true。只有地址不变但文件内容已更换时，才需要传 false
     */
    image(accountId, opts = {}) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/messages/image`, { body: pick(opts, ['to', 'url', 'media_id', 'use_cache']) })
    },
    /**
     * 发视频 —— 发送一段视频。不填时长时由平台估算，部分客户端可能会显示异常。
     *
     * POST /v1/accounts/{account_id}/messages/video
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {string} [opts.to] 必填 接收方：好友的 wxid、群 ID，或 filehelper（自己的文件传输助手）
     * @param {number} [opts.duration] 时长（秒）
     * @param {string} [opts.media_id] 平台中已有文件的媒体 ID
     * @param {string} [opts.thumbnail_url] 封面图地址，需要是可从公网下载的图片
     * @param {string} [opts.url] 可从公网下载的文件地址
     * @param {boolean} [opts.use_cache] 同一个 url 之前发送过时，直接复用已上传的文件，不再重新上传。默认 true。只有地址不变但文件内容已更换时，才需要传 false
     */
    video(accountId, opts = {}) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/messages/video`, { body: pick(opts, ['to', 'url', 'media_id', 'use_cache', 'duration', 'thumbnail_url']) })
    },
    /**
     * 发语音 —— 发送一条语音。seconds 是语音时长，会显示在聊天中的语音消息上。
     *
     * POST /v1/accounts/{account_id}/messages/voice
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {string} [opts.to] 必填 接收方：好友的 wxid、群 ID，或 filehelper（自己的文件传输助手）
     * @param {string} [opts.url] 必填 可从公网下载的音频地址
     * @param {number} [opts.seconds] 时长（秒）
     */
    voice(accountId, opts = {}) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/messages/voice`, { body: pick(opts, ['to', 'url', 'seconds']) })
    },
    /**
     * 发文件 —— 发送一个文件。
     *
     * POST /v1/accounts/{account_id}/messages/file
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {string} [opts.to] 必填 接收方：好友的 wxid、群 ID，或 filehelper（自己的文件传输助手）
     * @param {string} [opts.filename] 对方看到的文件名
     * @param {string} [opts.media_id] 平台中已有文件的媒体 ID
     * @param {string} [opts.url] 可从公网下载的文件地址
     * @param {boolean} [opts.use_cache] 同一个 url 之前发送过时，直接复用已上传的文件，不再重新上传。默认 true。只有地址不变但文件内容已更换时，才需要传 false
     */
    file(accountId, opts = {}) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/messages/file`, { body: pick(opts, ['to', 'url', 'media_id', 'use_cache', 'filename']) })
    },
    /**
     * 发动图表情 —— 转发一个动图表情。表情通过引用发送，无需上传文件。checksum 和 length 取自收到的表情消息。
     *
     * POST /v1/accounts/{account_id}/messages/sticker
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {string} [opts.checksum] 必填 表情的校验值，取自收到的表情消息
     * @param {number} [opts.length] 必填 表情的字节数，取自同一条表情消息
     * @param {string} [opts.to] 必填 接收方：好友的 wxid、群 ID，或 filehelper（自己的文件传输助手）
     */
    sticker(accountId, opts = {}) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/messages/sticker`, { body: pick(opts, ['to', 'checksum', 'length']) })
    },
    /**
     * 发链接卡片 —— 发送一张可点击的链接卡片。
     *
     * POST /v1/accounts/{account_id}/messages/link
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {string} [opts.title] 必填 卡片标题
     * @param {string} [opts.to] 必填 接收方：好友的 wxid、群 ID，或 filehelper（自己的文件传输助手）
     * @param {string} [opts.url] 必填 点击后打开的地址
     * @param {string} [opts.description] 卡片摘要
     * @param {string} [opts.source_name] 来源名称
     * @param {string} [opts.thumb_url] 封面图地址
     */
    link(accountId, opts = {}) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/messages/link`, { body: pick(opts, ['to', 'title', 'description', 'url', 'thumb_url', 'source_name']) })
    },
    /**
     * 发小程序卡片 —— 发送一张小程序卡片。需要提供小程序的标识。
     *
     * POST /v1/accounts/{account_id}/messages/miniapp
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {string} [opts.app_id] 必填 小程序的公开标识
     * @param {string} [opts.title] 必填 卡片标题
     * @param {string} [opts.to] 必填 接收方：好友的 wxid、群 ID，或 filehelper（自己的文件传输助手）
     * @param {string} [opts.username] 必填 小程序的原始 ID
     * @param {string} [opts.description] 卡片摘要
     * @param {string} [opts.path] 点击后打开的小程序页面路径
     * @param {string} [opts.source_name] 来源名称
     * @param {string} [opts.thumb_url] 封面图地址
     */
    miniapp(accountId, opts = {}) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/messages/miniapp`, { body: pick(opts, ['to', 'app_id', 'username', 'title', 'description', 'path', 'thumb_url', 'source_name']) })
    },
    /**
     * 转发消息 —— 将收到过的一条消息原样转发给其他人。
     *
     * POST /v1/accounts/{account_id}/messages/forward
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {string} [opts.message_id] 必填 要转发的消息 ID
     * @param {string} [opts.to] 必填 接收方：好友的 wxid、群 ID，或 filehelper（自己的文件传输助手）
     */
    forward(accountId, opts = {}) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/messages/forward`, { body: pick(opts, ['to', 'message_id']) })
    },
    /**
     * 撤回消息 —— 撤回自己发出的一条消息。微信只允许在发出后约两分钟内撤回，超过时间会被拒绝。
     *
     * POST /v1/accounts/{account_id}/messages/{message_id}/recall
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {string} messageId 发送时返回的 message_id
     */
    recall(accountId, messageId) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/messages/${encodeURIComponent(messageId)}/recall`)
    },
    /**
     * 消息记录 —— 查询平台保存的消息记录，可以按会话筛选。
     *
     * GET /v1/accounts/{account_id}/messages
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {string} [opts.cursor] 上一页返回的 next_cursor，首页留空
     * @param {number} [opts.limit] 每页条数，最多 200，超过按 200 处理
     * @param {string} [opts.peer] 只返回与某个 wxid 或群的会话中的消息
     */
    history(accountId, opts = {}) {
      return self.call('GET', `/accounts/${encodeURIComponent(accountId)}/messages`, { query: pick(opts, ['peer', 'cursor', 'limit']) })
    },
    /**
     * 同步消息 —— 主动拉取这个实例收到的消息，内容与 Webhook 推送的完全相同。如果没有配置 Webhook、Webhook 中断过，或者服务重启过，可以用它补回这段时间的消息。cursor 留空时，从目前仍保留的最早一条消息开始返回（大约可追溯一天）。之后每次调用都传入上一次返回的 next_cursor。has_more 为 true 表示还没有拉取完，请立即再调用一次。没有新消息时，返回的 next_cursor 与传入的相同，游标不会前进。拉取到的消息不会写入数据库，也不会触发 Webhook，重复拉取没有副作用。
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
     * 消息详情 —— 查询一条消息。
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
     * 收藏列表 —— 列出这个实例收藏的内容。cursor 留空时从第一页开始，返回的 next_cursor 为空表示已经到最后一页。
     *
     * 建议缓存：收藏只在你自己新增或删除收藏时才会变化。获取一次并保存，不要轮询；在你新增或删除收藏后再重新获取。
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
     * 收藏详情 —— 查询一条收藏的完整内容。内容为微信原始的 XML，平台原样返回，不同类型的收藏结构不同。
     *
     * 建议缓存：收藏的内容不会变化。按 fav_id 保存，获取过一次就不需要再获取。
     *
     * GET /v1/accounts/{account_id}/favorites/{fav_id}
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {string} favId 收藏 ID，从「收藏列表」获取
     */
    get(accountId, favId) {
      return self.call('GET', `/accounts/${encodeURIComponent(accountId)}/favorites/${encodeURIComponent(favId)}`)
    },
    /**
     * 删除收藏 —— 删除一条收藏。
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
     * 上传文件 —— 直接上传文件，获取一个 media_id。之后发送图片、视频、语音或文件时，只需传入这个 ID。适用于文件在你自己的机器上、没有公网地址的情况，比如程序刚生成的一张图片。请用 multipart/form-data 提交，文件放在 file 字段中，最大 20 MB。文件在第一次发送时才会真正上传到微信，之后用同一个 ID 发送不会重复上传。上传后一直没有发送过的文件保留 24 小时。
     *
     * POST /v1/accounts/{account_id}/media/upload
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {any} [opts.file] 必填 要上传的文件，multipart/form-data
     * @param {string} [opts.kind] 这个文件将作为哪种消息发送，不填则根据文件类型自动判断（image / video / voice / file）
     */
    upload(accountId, opts = {}) {
      const { file, filename, contentType } = opts
      return self.upload(`/accounts/${encodeURIComponent(accountId)}/media/upload`, file, {
        filename, contentType, fields: pick(opts, ['kind']) })
    },
    /**
     * 下载消息附件 —— 获取一条消息中的图片、视频、文件或语音，返回一个限时有效的下载地址。
     *
     * 建议缓存：下载地址有时效。拿到文件后请保存到你自己的存储中，不要每次展示时都重新下载。平台上已下载文件的总量超过上限时，会清除最早的一半，请不要把平台当作长期存储。
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
     * 查文件是否已缓存 —— 查询平台是否已经发送过某个地址的文件。发送过的文件可以直接复用，不需要重新上传，也不计流量。建议在把文件放到公网之前先调用这个接口。如果平台已经发送过，就不必再把文件放到公网。
     *
     * POST /v1/accounts/{account_id}/media/cached
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {string} [opts.kind] 必填 image、video 或 file
     * @param {string} [opts.url] 必填 要发送的文件地址，必须与发送时填写的地址完全一致才算命中
     */
    cached(accountId, opts = {}) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/media/cached`, { body: pick(opts, ['url', 'kind']) })
    },
    /**
     * 重新取下载地址 —— 为已经下载过的文件重新生成一个限时有效的下载地址。
     *
     * GET /v1/accounts/{account_id}/media/{media_id}
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {string} mediaId 媒体 ID
     */
    get(accountId, mediaId) {
      return self.call('GET', `/accounts/${encodeURIComponent(accountId)}/media/${encodeURIComponent(mediaId)}`)
    },
    /**
     * 下载动态媒体 —— 获取一条朋友圈动态中的第 N 张图片，或动态中的视频。
     *
     * POST /v1/accounts/{account_id}/moments/{moment_id}/media/download
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {string} momentId 动态 ID
     * @param {object} opts
     * @param {number} [opts.index] 图片序号，从 0 开始。视频动态会忽略这个值
     */
    moment(accountId, momentId, opts = {}) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/moments/${encodeURIComponent(momentId)}/media/download`, { body: pick(opts, ['index']) })
    },
  }

  self.moment = {
    /**
     * 我的朋友圈 —— 查询这个实例能看到的朋友圈时间线。
     *
     * 建议缓存：每次调用都会实时向微信请求，请不要定时刷新，刷新过于频繁会被视为异常行为。需要时再获取，并把获取到的动态保存在你自己的系统中。
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
     * 朋友圈详情 —— 查询一条朋友圈的完整内容。列表接口中的点赞和评论会被截断，这个接口返回完整的点赞和评论。
     *
     * 建议缓存：动态的正文和图片不会变化，获取后请保存。只有需要查看最新的点赞和评论时，才需要重新获取。
     *
     * GET /v1/accounts/{account_id}/moments/{moment_id}
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {string} momentId 朋友圈 ID
     */
    get(accountId, momentId) {
      return self.call('GET', `/accounts/${encodeURIComponent(accountId)}/moments/${encodeURIComponent(momentId)}`)
    },
    /**
     * 某人的朋友圈 —— 查询某个联系人的朋友圈主页。
     *
     * 建议缓存：每次调用都会实时向微信请求，请不要定时刷新，刷新过于频繁会被视为异常行为。需要时再获取，并把获取到的动态保存在你自己的系统中。
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
     * 发文字动态 —— 发布一条纯文字朋友圈。
     *
     * POST /v1/accounts/{account_id}/moments/text
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {string} [opts.content] 必填 正文
     * @param {Array} [opts.mentions] 要 @ 的 wxid
     * @param {object} [opts.visibility] 可见范围。mode 可选 public、private、allow、deny；选择 allow 或 deny 时，需要同时提供 wxids 或 tag_ids
     */
    postText(accountId, opts = {}) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/moments/text`, { body: pick(opts, ['content', 'mentions', 'visibility']) })
    },
    /**
     * 发图片动态 —— 发布一条带图片的朋友圈。
     *
     * POST /v1/accounts/{account_id}/moments/images
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {Array} [opts.images] 必填 图片列表
     * @param {string} [opts.content] 正文
     * @param {object} [opts.visibility] 可见范围。mode 可选 public、private、allow、deny；选择 allow 或 deny 时，需要同时提供 wxids 或 tag_ids
     */
    postImages(accountId, opts = {}) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/moments/images`, { body: pick(opts, ['content', 'images', 'visibility']) })
    },
    /**
     * 发视频动态 —— 发布一条视频朋友圈。
     *
     * POST /v1/accounts/{account_id}/moments/video
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {object} [opts.video] 必填 视频，需提供可从公网下载的地址
     * @param {string} [opts.content] 正文
     * @param {object} [opts.cover] 封面图
     * @param {number} [opts.duration] 时长（秒）
     * @param {object} [opts.visibility] 可见范围。mode 可选 public、private、allow、deny；选择 allow 或 deny 时，需要同时提供 wxids 或 tag_ids
     */
    postVideo(accountId, opts = {}) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/moments/video`, { body: pick(opts, ['content', 'video', 'cover', 'duration', 'visibility']) })
    },
    /**
     * 转发动态 —— 将看到的一条动态原样重新发布一次。
     *
     * POST /v1/accounts/{account_id}/moments/forward
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {object} opts
     * @param {string} [opts.moment_id] 必填 要转发的动态 ID
     * @param {object} [opts.visibility] 可见范围。mode 可选 public、private、allow、deny；选择 allow 或 deny 时，需要同时提供 wxids 或 tag_ids
     */
    repost(accountId, opts = {}) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/moments/forward`, { body: pick(opts, ['moment_id', 'visibility']) })
    },
    /**
     * 点赞 —— 给一条动态点赞。点赞前需要先通过朋友圈列表或详情接口读取过这条动态，读取后 24 小时内可以点赞。
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
     * @param {number} [opts.reply_to] 要回复的评论 ID，留空表示直接评论这条动态
     */
    comment(accountId, momentId, opts = {}) {
      return self.call('POST', `/accounts/${encodeURIComponent(accountId)}/moments/${encodeURIComponent(momentId)}/comments`, { body: pick(opts, ['content', 'reply_to']) })
    },
    /**
     * 删除评论 —— 删除自己发表的一条评论。
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
     * 删除动态 —— 删除自己发布的一条朋友圈。
     *
     * DELETE /v1/accounts/{account_id}/moments/{moment_id}
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {string} momentId 动态 ID
     */
    delete(accountId, momentId) {
      return self.call('DELETE', `/accounts/${encodeURIComponent(accountId)}/moments/${encodeURIComponent(momentId)}`)
    },
    /**
     * 设为私密 / 公开 —— 将自己的一条动态设为仅自己可见，或恢复为公开。
     *
     * PUT /v1/accounts/{account_id}/moments/{moment_id}/privacy
     * @param {string} accountId 实例 ID，形如 acc_xxx
     * @param {string} momentId 动态 ID
     * @param {object} opts
     * @param {boolean} [opts.private] 必填 true 表示仅自己可见，false 表示公开
     */
    privacy(accountId, momentId, opts = {}) {
      return self.call('PUT', `/accounts/${encodeURIComponent(accountId)}/moments/${encodeURIComponent(momentId)}/privacy`, { body: pick(opts, ['private']) })
    },
  }

  self.platform = {
    /**
     * 当前用户 —— 查询当前 API Key 所属的用户，以及该用户的实例数量。
     *
     * GET /v1/me
     */
    me() {
      return self.call('GET', '/me')
    },
    /**
     * 事件列表 —— 查询平台记录的事件，可以按实例、事件类型、消息类型和时间筛选。没有配置 Webhook 时，可以轮询这个接口获取事件。 轮询方法：第一次调用可以用 since 指定起始时间。之后每次调用都传入上一次返回的 next_cursor，从该位置之后继续读取。只要本页有事件，就一定会返回 next_cursor。没有新事件时 next_cursor 为空，此时请继续使用你已保存的上一个 next_cursor。has_more 为 true 表示后面还有事件，请立即继续读取；否则请等待几秒后再轮询。如果处理过程中程序重启，而最新的游标还没来得及保存，重新读取时会再次拿到相同的几条事件，因此建议按 e…
     *
     * GET /v1/events
     * @param {object} opts
     * @param {string} [opts.account_id] 只返回某个实例的事件
     * @param {string} [opts.cursor] 上一次返回的 next_cursor，从该位置之后继续读取。第一次调用时留空。如果返回的 next_cursor 为空，请继续使用上一次的值
     * @param {string} [opts.keyword] 按事件内容搜索。需要同时指定时间范围，且范围不超过 1 小时
     * @param {number} [opts.limit] 每页条数，最多 200，超过按 200 处理
     * @param {string} [opts.message_type] 只返回某种消息类型的事件，如 text、image、file，参数可重复传入。设置后，非消息类事件不会出现在结果中
     * @param {string} [opts.order] oldest 按时间从早到晚返回（默认），newest 从最近发生的事件开始返回（oldest / newest）
     * @param {string} [opts.since] 只返回这个时间之后的事件，格式为 RFC3339 或 Unix 秒级时间戳
     * @param {string} [opts.type] 只返回某种类型的事件，参数可重复传入
     * @param {string} [opts.until] 只返回这个时间之前的事件，格式为 RFC3339 或 Unix 秒级时间戳
     */
    events(opts = {}) {
      return self.call('GET', '/events', { query: pick(opts, ['account_id', 'type', 'message_type', 'since', 'until', 'keyword', 'order', 'cursor', 'limit']) })
    },
    /**
     * 事件流 —— 通过 SSE 长连接实时接收该实例的事件，事件内容与 Webhook 推送的相同。
     *
     * GET /v1/accounts/{account_id}/stream
     * @param {string} accountId 实例 ID，形如 acc_xxx
     */
    stream(accountId, options = {}) {
      return self.streamEvents(`/accounts/${encodeURIComponent(accountId)}/stream`, options)
    },
  }

}
