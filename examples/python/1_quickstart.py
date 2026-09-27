# -*- coding: utf-8 -*-
"""从零到发出第一条消息。

    pip install 不需要 —— SDK 只用标准库
    python 1_quickstart.py
"""
import os
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', '..', 'sdk', 'python'))
from welink import WeLink, WeLinkError

wx = WeLink(
    api_key=os.environ["WELINK_API_KEY"],
    base_url=os.environ["WELINK_BASE_URL"],
)

# 1. 开一个实例。已经有了就跳过这步，直接用它的 account_id。
#    proxy 必填，不能直连：socks5 代理地址（socks5://user:pass@host:port），或者网络助手的网络ID。
#    这里从环境变量 WELINK_PROXY 读。
account = wx.account_create(proxy=os.environ["WELINK_PROXY"], name="我的第一个实例")
account_id = account["account_id"]
print("实例已创建：", account_id)

# 2. 取登录二维码，用微信扫它。
code = wx.account_qrcode(account_id)
print("二维码（把这个 data URL 贴到浏览器地址栏就能看到）：")
print(code["qrcode"][:80], "...")

# 3. 等扫码。状态会依次走到 scanned、online。
#    Mac 登录扫完还要过一次新设备验证，平台自动做，会在 scanned 停一会儿，别急着取消。
while True:
    status = wx.account_login_status(account_id)
    print("  当前状态：", status.get("status"), status.get("notice", ""))
    if status.get("status") == "online":
        break
    if status.get("status") in ("expired", "cancelled", "offline"):
        print("  这张码用不了了，重新取一张")
        code = wx.account_qrcode(account_id)
    time.sleep(3)

# 4. 上线了，给自己的文件传输助手发一条。
try:
    sent = wx.message_text(account_id, to="filehelper", content="Hello from WeLink")
    print("发出去了：", sent)
except WeLinkError as e:
    print("发失败了：", e.code, e.message, "request_id:", e.request_id)
