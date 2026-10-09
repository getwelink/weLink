import test from 'node:test'
import assert from 'node:assert/strict'
import { createHmac } from 'node:crypto'
import { createServer } from 'node:http'
import { once } from 'node:events'
import { WeLink, WeLinkError, verifyWebhook } from '../sdk/node/welink.js'

test('验签、请求参数、错误响应及 SSE', async () => {
  const body = Buffer.from('{"text":"你好"}')
  const signature = 'sha256=' + createHmac('sha256', 'secret').update(body).digest('hex')
  assert.equal(verifyWebhook('secret', body, signature), true)
  assert.equal(verifyWebhook('secret', Buffer.from('{}'), signature), false)
  let mode = 'ok', received
  const server = createServer(async (req, res) => {
    const chunks = []
    for await (const chunk of req) chunks.push(chunk)
    received = {url:req.url, headers:req.headers, body:Buffer.concat(chunks).toString()}
    if (mode === 'stream') {
      res.writeHead(200, {'content-type':'text/event-stream'});res.flushHeaders()
      res.write(': keep-alive\r\n\r\ndata: {"event_id":"evt_你好",\r\ndata: "type":"message.received"}\r\n\r\n')
      setTimeout(() => res.end('data: {"event_id":"evt_two"}\n\n'), 350)
    } else {
      res.writeHead(mode === 'http-error' ? 500 : mode === 'api-error' ? 403 : 200, {'content-type':'application/json'})
      res.end(mode === 'bad' ? 'null' : mode === 'missing' ? '{}' : mode === 'api-error' ? '{"code":40301,"message":"expired","request_id":"req_test"}' : '{"code":0,"data":{"ok":true}}')
    }
  })
  server.listen(0,'127.0.0.1');await once(server,'listening')
  try {
    const wx = new WeLink({apiKey:'key_test',baseUrl:`http://127.0.0.1:${server.address().port}`})
    assert.deepEqual(await wx.message.text('acc a/b',{to:'wxid',content:'你好'}),{ok:true})
    assert.equal(received.url,'/v1/accounts/acc%20a%2Fb/messages/text')
    assert.equal(received.headers.authorization,'Bearer key_test')
    await wx.platform.events({type:['message.received','account.online']})
    assert.equal(new URL(received.url,'http://localhost').searchParams.getAll('type').length,2)
    for (const m of ['bad','missing','http-error','api-error']) {
      mode=m
      await assert.rejects(wx.call('GET','/test'),e=>e instanceof WeLinkError && (m!=='api-error'||(e.code===40301&&e.requestId==='req_test')))
      await assert.rejects(wx.upload('/test',Buffer.from('abc')),WeLinkError)
    }
    mode='stream';wx.timeout=200
    const events=[]
    for await (const e of wx.platform.stream('acc_test')) events.push(e)
    assert.equal(events.length,2);assert.equal(events[0].event_id,'evt_你好')
    for await (const e of wx.platform.stream('acc_test')) { assert.equal(e.event_id,'evt_你好');break }
  } finally {server.closeAllConnections();await new Promise(resolve=>server.close(resolve))}
})


test('88 个接口的方法、路径及参数覆盖', async () => {
  const { readFile } = await import('node:fs/promises')
  const reference = JSON.parse(await readFile(new URL('../docs/reference.json', import.meta.url), 'utf8'))
  const wx = new WeLink({apiKey:'key_test',baseUrl:'http://localhost'})
  wx.call = async (method,path,opts={}) => ({method,path,...opts})
  wx.upload = async (path,file,opts={}) => ({method:'POST',path,body:{...opts.fields,file}})
  wx.streamEvents = path => ({method:'GET',path})
  for (const e of reference.endpoints) {
    const args=[],opts={};let path=e.path
    for (const p of e.params || []) {
      let value=p.type==='boolean'?false:p.type==='integer'?0:p.type==='array'?['one','two']:p.type==='object'?{}:'test'
      if (p.in==='path') {value='a/b ?';args.push(value);path=path.replace(`{${p.name}}`,encodeURIComponent(value))}
      else {if(p.name==='file')value=Buffer.from('test');opts[p.name]=value}
    }
    args.push(opts)
    const [group,name]=e.id.split('.')
    const method=name.replace(/_([a-z])/g,(_,c)=>c.toUpperCase())
    const result=await wx[group][method](...args)
    assert.equal(result.method,e.method,e.id);assert.equal(result.path,path,e.id)
    for (const p of e.params || []) {
      if (p.in!=='path') assert.deepEqual(result[p.in==='query'?'query':'body']?.[p.name],opts[p.name],e.id+':'+p.name)
    }
  }
})
