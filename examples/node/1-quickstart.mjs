/**
 * 从零到发出第一条消息。
 *
 *   WELINK_API_KEY=key_xxx WELINK_BASE_URL=https://你的地址 WELINK_PROXY=socks5://… node 1-quickstart.mjs
 *
 * SDK 只用 Node 内置的 fetch，需要 Node 18 以上。
 */
import { writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { WeLink, WeLinkError } from '../../sdk/node/welink.js'

const wx = new WeLink({
  apiKey: process.env.WELINK_API_KEY,
  baseUrl: process.env.WELINK_BASE_URL,
})

// 1. 开一个实例。已经有了就跳过这步，直接用它的 account_id。
//    proxy 必填，不能直连：socks5 代理地址（socks5://user:pass@host:port），或者网络助手的网络ID。
//    这里从环境变量 WELINK_PROXY 读。
if (!process.env.WELINK_PROXY) throw new Error('先设置环境变量 WELINK_PROXY')
const account = await wx.account.create({ proxy: process.env.WELINK_PROXY, name: '我的第一个实例' })
const accountId = account.account_id
console.log('实例已创建：', accountId)

async function saveQRCode(code) {
  await writeFile('welink-login.png', Buffer.from(code.qrcode.split(',')[1], 'base64'))
  console.log('用微信扫描二维码文件：', resolve('welink-login.png'))
}

// 2. 取登录二维码，用微信扫它。
let code = await wx.account.qrcode(accountId)
await saveQRCode(code)

// 3. 等扫码。Mac 登录扫完还要过一次新设备验证，平台自动做，
//    会在 scanned 停一会儿，别急着取消。
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
while (true) {
  const status = await wx.account.loginStatus(accountId)
  console.log('  当前状态：', status.status, status.notice ?? '')
  if (status.status === 'online') break
  if (['expired', 'cancelled', 'offline'].includes(status.status)) {
    console.log('  这张码用不了了，重新取一张')
    code = await wx.account.qrcode(accountId)
    await saveQRCode(code)
  }
  await sleep(3000)
}

// 4. 上线了，给自己的文件传输助手发一条。
try {
  const sent = await wx.message.text(accountId, { to: 'filehelper', content: 'Hello from WeLink' })
  console.log('发出去了：', sent)
} catch (e) {
  if (e instanceof WeLinkError) console.log('发失败了：', e.code, e.message, 'requestId:', e.requestId)
  else throw e
}
