// Real local WebSocket + HTTP fixture. Never connects to production.
import { createServer } from 'node:http'
import { createRequire } from 'node:module'
import { spawn } from 'node:child_process'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import assert from 'node:assert/strict'
const here = dirname(fileURLToPath(import.meta.url))
const require = createRequire(join(here, '..', 'websocket', 'node', 'package.json'))
const { WebSocketServer } = require('ws')
const python = process.env.WELINK_TEST_PYTHON || 'python'
const key = 'local-echo-fixture-key'
const dir = await mkdtemp(join(tmpdir(), 'welink-echo-'))
const posts = []
let connections = 0, pongs = 0, fixtureError
const server = createServer(async (req, res) => {
  try {
    assert.equal(req.headers.authorization, 'Bearer ' + key)
    assert.equal(req.method, 'POST')
    assert.match(req.url, /^\/v1\/accounts\/acc_(first|second)\/messages\/text$/)
    const parts = []
    for await (const part of req) parts.push(part)
    posts.push({ url: req.url, body: JSON.parse(Buffer.concat(parts)) })
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify({ code: 0, data: { message_id: 'msg_reply_' + posts.length } }))
  } catch (err) { fixtureError = err; res.writeHead(500); res.end() }
})
const ws = new WebSocketServer({ server, path: '/v1/ws' })
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
const waitFor = async (predicate) => {
  const end = Date.now() + 10000
  while (!predicate()) {
    if (Date.now() > end) throw new Error('fixture timed out')
    await new Promise(resolve => setTimeout(resolve, 30))
  }
}
const event = (id, account = 'acc_first', data = {}) => ({
  type: 'message.received', event_id: id, account_id: account,
  data: { message_id: 'msg_' + id, chat_id: 'wxid_fixture', self: false, is_group: false, type: 'text', text: '/ping', ...data },
})
ws.on('connection', (socket, req) => {
  socket.on('error', () => {})
  socket.on('pong', () => pongs++)
  ;(async () => {
    assert.equal(req.headers.authorization, 'Bearer ' + key)
    const phase = ++connections
    const cursor = new URL(req.url, 'http://localhost').searchParams.get('cursor')
    assert.equal(cursor, phase === 1 ? null : 'evt_two')
    socket.send(JSON.stringify({ type: 'connected', scope: 'user' }))
    socket.ping('fixture')
    const send = value => socket.send(JSON.stringify(value))
    if (phase === 1) {
      send(event('evt_self', 'acc_first', { self: true }))
      send(event('evt_image', 'acc_first', { type: 'image' }))
      send(event('evt_disallowed', 'acc_other'))
      send(event('evt_chat', 'acc_first', { chat_id: 'wxid_other' }))
      send(event('evt_group', 'acc_first', { chat_id: 'group@chatroom', is_group: true }))
      send(event('evt_one'))
      send(event('evt_one'))
      send(event('evt_duplicate', 'acc_first', { message_id: 'msg_evt_one' }))
      send(event('evt_two', 'acc_second', { text: '/echo local fixture' }))
      await waitFor(() => posts.length === 2)
      await new Promise(resolve => setTimeout(resolve, 200))
      socket.close(1012, 'fixture restart')
    } else {
      assert.equal(phase, 2)
      send(event('evt_two', 'acc_second', { text: '/echo local fixture' }))
      send(event('evt_three', 'acc_first', { text: '/help' }))
      await waitFor(() => posts.length === 3)
      await new Promise(resolve => setTimeout(resolve, 200))
      send({ type: 'gap', reason: 'cursor_expired' })
    }
  })().catch(err => { fixtureError = err; socket.terminate() })
})
const child = spawn(python, [join(here, 'main.py')], {
  cwd: here, windowsHide: true,
  env: { ...process.env, NO_PROXY: '127.0.0.1,localhost', WELINK_BASE_URL: `http://127.0.0.1:${server.address().port}`,
    WELINK_API_KEY: key, WELINK_ACCOUNT_IDS: 'acc_first,acc_second', WELINK_CHAT_IDS: 'wxid_fixture,group@chatroom',
    WELINK_STATE_FILE: join(dir, 'state.sqlite3'), WELINK_ALLOW_GROUPS: '0' },
})
let output = '', timer
child.stdout.on('data', value => output += value)
child.stderr.on('data', value => output += value)
try {
  const code = await new Promise((yes, no) => {
    child.once('error', no); child.once('exit', yes)
    timer = setTimeout(() => { child.kill(); no(new Error('client timeout: ' + output)) }, 30000)
  })
  if (fixtureError) throw fixtureError
  assert.equal(code, 2, output)
  assert.equal(connections, 2)
  assert.ok(pongs >= 2)
  assert.equal(posts.length, 3)
  assert.equal(posts[0].body.content, 'pong')
  assert.equal(posts[1].body.content, 'local fixture')
  assert.ok(posts[1].url.includes('acc_second'))
  assert.ok(posts[2].body.content.includes('/help'))
  assert.ok(output.includes('gap/error'))
  assert.ok(!output.includes(key) && !output.includes('local fixture'))
  console.log('echo: allowed routing, real HTTP replies, filtering, deduplication, Ping/Pong, cursor reconnect and gap stop passed')
} finally {
  clearTimeout(timer); child.kill()
  for (const socket of ws.clients) socket.terminate()
  await new Promise(resolve => ws.close(resolve))
  await new Promise(resolve => server.close(resolve))
  await rm(dir, { recursive: true, force: true })
}
