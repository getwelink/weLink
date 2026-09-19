/**
 * 不开公网地址，靠轮询拿事件。内容和 Webhook 推的一模一样。
 */
import { WeLink } from '../../sdk/node/welink.js'

const wx = new WeLink({
  apiKey: process.env.WELINK_API_KEY,
  baseUrl: process.env.WELINK_BASE_URL,
})

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
let cursor = '' // 第一次留空，之后一直带上回来的那个

while (true) {
  const page = await wx.platform.events({ cursor: cursor || undefined, limit: 50, order: 'oldest' })
  for (const event of page.items || []) {
    console.log(event.created_at, event.type, event.data?.text ?? '')
  }
  cursor = page.next_cursor || cursor
  if (!(page.items || []).length) await sleep(3000) // 没有新的就歇一会儿
}
