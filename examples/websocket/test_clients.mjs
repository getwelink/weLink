// Local integration smoke test for all four downloadable clients.
// npm ci --prefix examples/websocket/node, compile Java (see README), then run
// node examples/websocket/test_clients.mjs. No production calls are made.
import { createRequire } from 'node:module'
import { spawn } from 'node:child_process'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import assert from 'node:assert/strict'
const here = dirname(fileURLToPath(import.meta.url))
const require = createRequire(join(here, 'node', 'package.json'))
const { WebSocketServer } = require('ws')
const fixtureKey = 'local-websocket-fixture-key'
const account = 'acc_websocket_fixture'
const selected = (process.env.WELINK_TEST_LANGUAGES || 'python,node,go,java').split(',')
const clients = {
  python: [process.env.WELINK_TEST_PYTHON || 'python', [join(here, 'python', 'client.py')], here],
  node: [process.execPath, [join(here, 'node', 'client.mjs')], here],
  go: ['go', ['run', '.'], join(here, 'go')],
  java: ['java', ['-cp', join(here, 'java', 'out'), 'welink.WebSocketClient'], here],
}
for (const language of selected) {
  const dir = await mkdtemp(join(tmpdir(), 'welink-ws-test-'))
  const state = join(dir, 'client.cursor')
  const server = new WebSocketServer({ host: '127.0.0.1', port: 0 })
  await new Promise((yes, no) => { server.once('listening', yes); server.once('error', no) })
  let connections = 0
  let pongs = 0
  let timer
  let fixtureError
  const waitCursor = async expected => {
    const deadline = Date.now() + 10000
    while (Date.now() < deadline) {
      try { if ((await readFile(state, 'utf8')).trim() === expected) return } catch {}
      await new Promise(done => setTimeout(done, 20))
    }
    throw new Error(language + ': cursor not persisted: ' + expected)
  }
  server.on('connection', (socket, request) => {
    const phase = ++connections
    socket.on('error', () => {})
    socket.on('pong', () => pongs++)
    ;(async () => {
      assert.equal(request.headers.authorization, 'Bearer ' + fixtureKey)
      const url = new URL(request.url, 'http://127.0.0.1')
      assert.equal(url.pathname, '/v1/accounts/' + account + '/ws')
      assert.equal(url.searchParams.get('cursor'), phase === 1 ? null : phase === 2 ? 'evt_fixture_one' : 'evt_fixture_two')
      socket.send(JSON.stringify({ type: 'connected', account_id: account }))
      socket.ping('heartbeat')
      if (phase <= 2) {
        // Repeat the last event once; clients should not process it again.
        if (phase === 2) socket.send(JSON.stringify({ type: 'message.received', event_id: 'evt_fixture_one', account_id: account, data: { text: 'duplicate' } }))
        const id = phase === 1 ? 'evt_fixture_one' : 'evt_fixture_two'
        socket.send(JSON.stringify({ type: 'message.received', event_id: id, account_id: account, data: { text: 'local fixture' } }))
        await waitCursor(id)
        socket.close(1012, 'restart_fixture')
      } else {
        assert.equal(phase, 3, 'client retried after gap')
        socket.send(JSON.stringify({ type: 'gap', reason: 'cursor_expired', action: 'pull_sync' }))
        socket.close(1013, 'pull_sync')
      }
    })().catch(err => { fixtureError = err; socket.terminate() })
  })
  const [command, args, cwd] = clients[language] || []
  assert.ok(command, 'Unknown test language')
  const child = spawn(command, args, { cwd, env: { ...process.env, NO_PROXY: '127.0.0.1,localhost', WELINK_API_KEY: fixtureKey, WELINK_ACCOUNT_ID: account, WELINK_BASE_URL: `http://127.0.0.1:${server.address().port}`, WELINK_CURSOR_FILE: state }, windowsHide: true })
  let output = ''
  child.stdout.on('data', data => output += data)
  child.stderr.on('data', data => output += data)
  try {
    const code = await new Promise((yes, no) => {
      child.once('error', no)
      child.once('exit', yes)
      timer = setTimeout(() => { child.kill(); no(new Error(language + ': timed out\n' + output)) }, 45000)
    })
    if (fixtureError) throw fixtureError
    assert.notEqual(code, 0, 'gap should require intervention')
    assert.equal(connections, 3, output)
    assert.ok(pongs >= 2, 'protocol heartbeat was not answered')
    assert.equal((await readFile(state, 'utf8')).trim(), 'evt_fixture_two')
    assert.equal((output.match(/"event_id"\s*:\s*"evt_fixture_one"/g) || []).length, 1, output)
    assert.equal((output.match(/"event_id"\s*:\s*"evt_fixture_two"/g) || []).length, 1, output)
    assert.ok(!output.includes(fixtureKey), 'client leaked its key')
    console.log(language + ': events, Ping/Pong, reconnect, persisted cursor, deduplication and gap stop passed')
  } finally {
    clearTimeout(timer)
    child.kill()
    for (const client of server.clients) client.terminate()
    await new Promise(done => server.close(done))
    await rm(dir, { recursive: true, force: true })
  }
}
