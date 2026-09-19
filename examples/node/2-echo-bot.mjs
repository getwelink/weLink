/**
 * 一个复读机：收到什么就回什么。
 *
 * 演示事件回调怎么收、怎么验签、怎么回消息。用的是内置 http，
 * 生产上换成 express / koa 都一样，关键是别先把 body 解析掉。
 *
 *   node 2-echo-bot.mjs        # 监听 0.0.0.0:9000
 * 然后在控制台把实例的 Webhook 地址填成 http://你的地址:9000/hook
 */
import { createServer } from 'node:http'

import { WeLink, verifyWebhook } from '../../sdk/node/welink.js'

const wx = new WeLink({
  apiKey: process.env.WELINK_API_KEY,
  baseUrl: process.env.WELINK_BASE_URL,
})
const SECRET = process.env.WELINK_WEBHOOK_SECRET || ''

createServer(async (req, res) => {
  if (req.method !== 'POST') {
    res.writeHead(405).end()
    return
  }

  // 原始字节，验签要用。不能先 JSON.parse 再 stringify。
  const chunks = []
  for await (const chunk of req) chunks.push(chunk)
  const raw = Buffer.concat(chunks)

  if (SECRET && !verifyWebhook(SECRET, raw, req.headers['x-orbit-signature'])) {
    res.writeHead(403).end()
    return
  }

  // 先回 2xx，再处理。平台只等 5 秒。
  res.writeHead(200).end('ok')

  try {
    await handle(JSON.parse(raw.toString('utf8')))
  } catch (e) {
    console.error('处理事件出错：', e.message)
  }
}).listen(9000, () => console.log('等事件中，Webhook 地址填 http://<你的地址>:9000/hook'))

async function handle(event) {
  if (event.type !== 'message.received') return
  const msg = event.data || {}

  // 自己发的不要回，不然两边会一直聊下去。
  if (msg.self || msg.type !== 'text') return

  // 群消息里 from 是群，说话的人是 sender。回到 chat_id 就对了。
  console.log('收到：', msg.sender, '->', msg.text)
  await wx.message.text(event.account_id, { to: msg.chat_id, content: '你刚才说：' + (msg.text || '') })
}
