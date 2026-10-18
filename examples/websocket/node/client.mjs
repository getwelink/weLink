// Node.js 18+; npm install && npm start. See ../README.md.
import WebSocket from 'ws'
import { readFile, writeFile, rename, mkdir } from 'node:fs/promises'
import { dirname } from 'node:path'

const key = process.env.WELINK_API_KEY
const account = process.env.WELINK_ACCOUNT_ID || ''
if (!key || !/^acc_[A-Za-z0-9_]{1,60}$/.test(account)) throw new Error('Set WELINK_API_KEY and WELINK_ACCOUNT_ID')
const base = new URL(process.env.WELINK_BASE_URL || '')
if (!['http:', 'https:', 'ws:', 'wss:'].includes(base.protocol) || base.username || base.password || base.search || base.hash) throw new Error('Invalid WELINK_BASE_URL')
base.protocol = base.protocol.replace('http', 'ws')
base.pathname = base.pathname.replace(/\/$/, '') + '/v1/accounts/' + account + '/ws'
const state = process.env.WELINK_CURSOR_FILE || '.welink-ws-' + account + '.cursor'
await mkdir(dirname(state), { recursive: true })
let cursor = ''
try { cursor = (await readFile(state, 'utf8')).trim() } catch (err) { if (err.code !== 'ENOENT') throw err }
if (cursor.length > 64) throw new Error('Invalid cursor file')
let wait = 1000
let stopped = false
let socket
let timer
async function handleEvent(event) {
  // Replace with your business handler and deduplicate event_id in your database.
  // Throw on failure. Do not advance the cursor until processing completes.
  console.log(JSON.stringify(event))
}
function connect() {
  const url = new URL(base)
  if (cursor) url.searchParams.set('cursor', cursor)
  const ws = new WebSocket(url, { headers: { Authorization: 'Bearer ' + key }, handshakeTimeout: 10000 })
  socket = ws
  let work = Promise.resolve()
  let broken = false
  // ws automatically replies to protocol Ping. Serialize asynchronous handlers.
  ws.on('message', raw => {
    work = work.then(async () => {
      if (broken || stopped) return
      const event = JSON.parse(raw.toString())
      if (['gap', 'error'].includes(event.type)) {
        stopped = true
        process.exitCode = 2
        console.error('Stopped:', event.type, event.reason, '; resolve access or pull missing messages before restarting')
        ws.close()
        return
      }
      if (!event.event_id || event.event_id === cursor) return
      await handleEvent(event)
      await writeFile(state + '.tmp', event.event_id + '\n', { mode: 0o600 })
      await rename(state + '.tmp', state)
      cursor = event.event_id
      wait = 1000
    }).catch(err => {
      broken = true
      console.error(err.name, '; reconnecting from saved cursor')
      ws.terminate()
    })
  })
  ws.on('unexpected-response', (_req, res) => {
    if ([400, 401, 403, 404].includes(res.statusCode)) { stopped = true; process.exitCode = 2 }
    console.error('Handshake HTTP', res.statusCode, '; check service version, key and account')
    res.resume()
    ws.terminate()
  })
  ws.on('error', err => console.error(err.name))
  ws.on('close', async code => {
    await work
    if (code === 1008) { stopped = true; process.exitCode = 2 }
    if (stopped) return
    timer = setTimeout(connect, wait + Math.random() * 1000)
    wait = Math.min(wait * 2, 30000)
  })
}
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => { stopped = true; clearTimeout(timer); socket?.close() })
connect()
