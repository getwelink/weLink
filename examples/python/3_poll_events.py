# -*- coding: utf-8 -*-
"""不开公网地址，靠轮询拿事件。

内容和 Webhook 推的一模一样，只是换成你主动来取。
"""
import os
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', '..', 'sdk', 'python'))
from welink import WeLink

wx = WeLink(api_key=os.environ["WELINK_API_KEY"], base_url=os.environ["WELINK_BASE_URL"])

cursor = ""  # 第一次留空，之后一直带上回来的那个
while True:
    page = wx.platform_events(cursor=cursor or None, limit=50, order="oldest")
    for event in page.get("items") or []:
        print(event["created_at"], event["type"], (event.get("data") or {}).get("text", ""))
    cursor = page.get("next_cursor") or cursor
    if not page.get("items"):
        time.sleep(3)  # 没有新的就歇一会儿，别空转
