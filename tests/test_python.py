import sys, pathlib, unittest, threading, json, hmac, hashlib, time, urllib.parse
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[1]/'sdk/python'))
from welink import WeLink, WeLinkError, verify_webhook

class Handler(BaseHTTPRequestHandler):
    mode='ok'
    received=None
    def log_message(self,*args): pass
    def do_GET(self): self.reply()
    def do_POST(self): self.reply()
    def reply(self):
        body=self.rfile.read(int(self.headers.get('Content-Length','0')))
        Handler.received=(self.path,self.headers,body)
        mode=Handler.mode
        self.send_response(500 if mode=='http-error' else 403 if mode=='api-error' else 200)
        self.send_header('Content-Type','text/event-stream' if mode=='stream' else 'application/json');self.end_headers()
        payload={'bad':b'null','missing':b'{}','api-error':b'{"code":40301,"message":"expired","request_id":"req_test"}','stream':': keep-alive\r\n\r\ndata: {"event_id":"evt_你好",\r\ndata: "type":"message.received"}\r\n\r\ndata: {"event_id":"evt_two"}\n\n'.encode()}.get(mode,b'{"code":0,"data":{"ok":true}}')
        self.wfile.write(payload)

class SDKTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server=ThreadingHTTPServer(('127.0.0.1',0),Handler)
        threading.Thread(target=cls.server.serve_forever,daemon=True).start()
        cls.wx=WeLink('key_test','http://127.0.0.1:%s'%cls.server.server_port)
    @classmethod
    def tearDownClass(cls): cls.server.shutdown();cls.server.server_close()
    def test_requests_errors_stream_and_signature(self):
        raw='{"text":"你好"}'.encode()
        sig='sha256='+hmac.new(b'secret',raw,hashlib.sha256).hexdigest()
        self.assertTrue(verify_webhook('secret',raw,sig));self.assertFalse(verify_webhook('secret',b'{}',sig))
        Handler.mode='ok'
        self.assertEqual(self.wx.message_text('acc a/b',to='wxid',content='你好'),{'ok':True})
        self.assertEqual(Handler.received[0],'/v1/accounts/acc%20a%2Fb/messages/text')
        self.wx.platform_events(type=['message.received','account.online'])
        self.assertEqual(Handler.received[0].count('type='),2)
        for mode in ['bad','missing','http-error','api-error']:
            Handler.mode=mode
            for call in [lambda:self.wx.call('GET','/test'),lambda:self.wx.upload('/test',b'abc')]:
                with self.assertRaises(WeLinkError) as caught: call()
                if mode=='api-error': self.assertEqual(caught.exception.request_id,'req_test')
        Handler.mode='stream'
        events=list(self.wx.platform_stream('acc_test'))
        self.assertEqual(len(events),2);self.assertEqual(events[0]['event_id'],'evt_你好')
        stream=self.wx.platform_stream('acc_test');next(stream);stream.close()
class RouteTest(unittest.TestCase):
    def test_all_registered_methods(self):
        reference=json.loads((pathlib.Path(__file__).resolve().parents[1]/'docs/reference.json').read_text(encoding='utf-8'))
        wx=WeLink('key_test','http://localhost')
        wx.call=lambda method,path,query=None,body=None: (method,path,query or {},body or {})
        wx.upload=lambda path,file,**opts: ('POST',path,{},dict(opts.get('fields') or {},file=file))
        for endpoint in reference['endpoints']:
            if endpoint.get('streaming'): continue
            args={}
            path=endpoint['path']
            for p in endpoint.get('params',[]):
                value='test' if p['type']=='string' else False if p['type']=='boolean' else 0 if p['type']=='integer' else ['one','two'] if p['type']=='array' else {}
                if p['in']=='path':
                    value='a/b ?'
                    path=path.replace('{'+p['name']+'}',urllib.parse.quote(value,safe=''))
                if p['name']=='file': value=b'test'
                args[p['name']]=value
            result=getattr(wx,endpoint['id'].replace('.','_'))(**args)
            self.assertEqual(result[:2],(endpoint['method'],path),endpoint['id'])
            for p in endpoint.get('params',[]):
                if p['in']!='path': self.assertEqual(result[2 if p['in']=='query' else 3].get(p['name']),args[p['name']],endpoint['id']+':'+p['name'])
if __name__=='__main__': unittest.main()
