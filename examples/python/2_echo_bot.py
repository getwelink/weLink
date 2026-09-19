# -*- coding: utf-8 -*-
"""一个复读机：收到什么就回什么。

演示事件回调怎么收、怎么验签、怎么回消息。
标准库起的服务，生产上请换成你自己的框架。

    python 2_echo_bot.py          # 监听 0.0.0.0:9000
然后在控制台把实例的 Webhook 地址填成 http://你的地址:9000/hook
"""
import json
import os
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', '..', 'sdk', 'python'))
from welink import WeLink, verify_webhook

wx = WeLink(api_key=os.environ["WELINK_API_KEY"], base_url=os.environ["WELINK_BASE_URL"])
SECRET = os.environ.get("WELINK_WEBHOOK_SECRET", "")


class Hook(BaseHTTPRequestHandler):
    def do_POST(self):
        raw = self.rfile.read(int(self.headers.get("Content-Length", 0)))

        # 先验签再干活。没设密钥就没有这个头，那就没得验。
        if SECRET and not verify_webhook(SECRET, raw, self.headers.get("X-Orbit-Signature", "")):
            self.send_response(403)
            self.end_headers()
            return

        # 先回 2xx，再处理。平台只等 5 秒，重活要扔进队列。
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b"ok")

        try:
            self.handle_event(json.loads(raw))
        except Exception as e:  # 处理失败也不能让回调挂掉
            print("处理事件出错：", e)

    def handle_event(self, event):
        if event.get("type") != "message.received":
            return
        msg = event.get("data") or {}

        # 自己发的不要回，不然两边会一直聊下去。
        if msg.get("self") or msg.get("type") != "text":
            return

        # 群消息里 from 是群，说话的人是 sender。回到 chat_id 就对了。
        print("收到：", msg.get("sender"), "->", msg.get("text"))
        wx.message_text(event["account_id"], to=msg["chat_id"],
                        content="你刚才说：" + (msg.get("text") or ""))

    def log_message(self, *args):
        pass  # 默认的访问日志太吵


if __name__ == "__main__":
    print("等事件中，Webhook 地址填 http://<你的地址>:9000/hook")
    HTTPServer(("0.0.0.0", 9000), Hook).serve_forever()
