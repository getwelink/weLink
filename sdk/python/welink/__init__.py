"""WeLink —— 把微信的能力做成 HTTP 接口。

    from welink import WeLink

    wx = WeLink(api_key="key_xxx", base_url="https://你的服务地址")
    wx.message_text("acc_xxx", to="filehelper", content="你好")

每个方法对应一个接口，返回的是响应里 data 字段的内容。
调用失败抛 WeLinkError，上面带平台错误码和 request_id。

本文件由接口清单生成，不要手改。
"""
from __future__ import annotations

import json as _json
import uuid as _uuid
import os as _os
import mimetypes as _mimetypes
import urllib.error
import urllib.parse
import urllib.request
from typing import Any, Optional

__all__ = ["WeLink", "WeLinkError", "verify_webhook"]
__version__ = "1.0.0"


class WeLinkError(Exception):
    """一次失败的调用。code 是平台错误码，request_id 便于排查。"""

    def __init__(self, code: int, message: str, request_id: str = "", status: int = 0):
        super().__init__("[%s] %s" % (code, message))
        self.code = code
        self.message = message
        self.request_id = request_id
        self.status = status


def verify_webhook(secret: str, body: bytes, signature: str) -> bool:
    """校验事件回调的签名。

    必须拿原始请求体来算 —— 先反序列化再重新序列化，字段顺序和空格都会变，
    算出来的签名就对不上了。
    """
    import hashlib
    import hmac

    expected = "sha256=" + hmac.new(secret.encode(), body, hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, signature or "")


class WeLink:
    """一个 API Key 一个实例。无状态，可以长期持有、多线程共用。"""

    def __init__(self, api_key: str, base_url: str, timeout: float = 30.0):
        if not api_key:
            raise ValueError("api_key 不能为空")
        if not base_url:
            raise ValueError("base_url 不能为空，填你拿到的服务地址")
        self.api_key = api_key
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout

    # --- 底层 ----------------------------------------------------------------

    def call(self, method: str, path: str,
             query: Optional[dict] = None, body: Optional[dict] = None) -> Any:
        """直接调用一个接口。清单里还没有的新接口可以用它。"""
        url = self.base_url + "/v1" + path
        if query:
            pairs = []
            for key, value in query.items():
                if value is None or value == "":
                    continue
                if isinstance(value, (list, tuple)):
                    pairs.extend((key, str(v)) for v in value)
                else:
                    pairs.append((key, str(value)))
            if pairs:
                url += "?" + urllib.parse.urlencode(pairs)

        data = None
        headers = {"Authorization": "Bearer " + self.api_key,
                   "Accept": "application/json"}
        if body is not None:
            data = _json.dumps(_clean(body), ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"

        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                raw, status = resp.read(), resp.status
        except urllib.error.HTTPError as e:
            raw, status = e.read(), e.code
        except urllib.error.URLError as e:
            raise WeLinkError(0, "连不上服务：%s" % e.reason) from e

        try:
            envelope = _json.loads(raw.decode("utf-8"))
        except ValueError:
            raise WeLinkError(0, "服务返回的不是 JSON（HTTP %s）" % status, status=status)

        code = envelope.get("code")
        if code != 0:
            raise WeLinkError(code or 0, envelope.get("message") or "调用失败",
                              envelope.get("request_id") or "", status)
        return envelope.get("data")

    def upload(self, path: str, file: Any, *, filename: str = "",
               content_type: str = "", fields: Optional[dict] = None) -> Any:
        """以 multipart/form-data 上传一个文件。

        file 可以是一个路径（str）、二进制内容（bytes），或者任何有 read() 的对象。
        """
        if isinstance(file, str):
            filename = filename or _os.path.basename(file)
            with open(file, "rb") as fh:
                content = fh.read()
        elif isinstance(file, (bytes, bytearray)):
            content = bytes(file)
        elif hasattr(file, "read"):
            content = file.read()
            filename = filename or getattr(file, "name", "")
        else:
            raise ValueError("file 只能是路径、bytes，或者有 read() 的对象")

        filename = _os.path.basename(filename) or "file"
        content_type = content_type or (
            _mimetypes.guess_type(filename)[0] or "application/octet-stream")

        boundary = "----welink" + _uuid.uuid4().hex
        crlf = b"\r\n"
        chunks = []
        for key, value in (fields or {}).items():
            if value is None or value == "":
                continue
            chunks.append(b"--" + boundary.encode() + crlf)
            chunks.append(('Content-Disposition: form-data; name="%s"' % key
                           ).encode() + crlf + crlf)
            chunks.append(str(value).encode("utf-8") + crlf)
        chunks.append(b"--" + boundary.encode() + crlf)
        chunks.append((
            'Content-Disposition: form-data; name="file"; filename="%s"'
            % filename).encode("utf-8") + crlf)
        chunks.append(("Content-Type: %s" % content_type).encode() + crlf + crlf)
        chunks.append(content + crlf)
        chunks.append(b"--" + boundary.encode() + b"--" + crlf)
        data = b"".join(chunks)

        req = urllib.request.Request(
            self.base_url + "/v1" + path, data=data, method="POST",
            headers={"Authorization": "Bearer " + self.api_key,
                     "Accept": "application/json",
                     "Content-Type": "multipart/form-data; boundary=" + boundary})
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                raw, status = resp.read(), resp.status
        except urllib.error.HTTPError as e:
            raw, status = e.read(), e.code
        except urllib.error.URLError as e:
            raise WeLinkError(0, "连不上服务：%s" % e.reason) from e

        try:
            envelope = _json.loads(raw.decode("utf-8"))
        except ValueError:
            raise WeLinkError(0, "服务返回的不是 JSON（HTTP %s）" % status, status=status)
        code = envelope.get("code")
        if code != 0:
            raise WeLinkError(code or 0, envelope.get("message") or "上传失败",
                              envelope.get("request_id") or "", status)
        return envelope.get("data")


    # --- 实例 ----------------------------------------------------------------

    def account_create(self, *, proxy: str, keep_history: Optional[bool] = None, name: Optional[str] = None, platform: Optional[str] = None, webhook_url: Optional[str] = None) -> Any:
        """创建实例

        创建一个实例。每个实例占用一个额度，删除实例后额度归还。实例创建后需要扫码登录才会上线。

        POST /v1/accounts

        参数：
          proxy            必填  代理网络，必填，不能直连。有两种填法：socks5 代理地址，如 socks5://user:pass@host:port；网络助手的网络ID，在一台手机上安装并打开网络助手即可看到，实例将通过这台手机的网络连接微信。填网络ID时，代理地址由平台自动获取。网络助手不在线时，请求会被拒绝。网络助手的…
          keep_history     可选  是否保存收发的消息和推送记录，默认 true。设为 false 时，消息不写入数据库，推送记录在投递结束后立即删除。图片等文件仍可下载，撤回功能仍可使用；但无法查询历史消息，也无法转发文字和卡片消息
          name             可选  备注名称，仅自己可见
          platform         可选  登录方式，留空时使用默认方式。并非每个部署都同时开通了两种方式。选择未开通的方式会直接报错，错误信息中会列出可选的方式（ipad / mac）
          webhook_url      可选  接收该实例事件的 Webhook 地址
        """
        return self.call("POST", "/accounts",
            body={"platform": platform, "name": name, "proxy": proxy, "webhook_url": webhook_url, "keep_history": keep_history},
        )

    def account_list(self) -> Any:
        """实例列表

        列出你的全部实例及其状态。

        GET /v1/accounts
        """
        return self.call("GET", "/accounts")

    def account_get(self, account_id: str) -> Any:
        """实例详情

        查询一个实例的详情。实例不在线时，reason 字段说明原因：manual 表示主动退出，kicked 表示因其他设备登录而被挤下线，relogin_required 表示需要重新扫码登录，recover_timeout 表示自动恢复超时，expired 表示授权到期。status 为 recovering 时，recovering 字段给出恢复方式和放弃恢复的时间。

        GET /v1/accounts/{account_id}
        """
        return self.call("GET", f"/accounts/{account_id}")

    def account_qrcode(self, account_id: str, *, proxy: Optional[str] = None) -> Any:
        """获取登录二维码

        获取一张登录二维码，用手机微信扫码登录。expires_in 是二维码剩余的有效秒数，请以返回值为准，不要写死。二维码过期后重新获取即可。请求时可以带上 proxy 来更换代理网络。代理网络在建立登录会话时确定，更换后需要重新建立会话，所以只能在获取二维码时更换。不传 proxy 则沿用原来的代理网络。

        POST /v1/accounts/{account_id}/login/qrcode

        参数：
          proxy            可选  要改用的代理网络，可以是socks5 地址或网络助手的网络ID。不传则沿用实例现有的代理网络。不能传空字符串，因为实例必须配置代理网络，不允许直连
        """
        return self.call("POST", f"/accounts/{account_id}/login/qrcode",
            body={"proxy": proxy},
        )

    def account_login_status(self, account_id: str) -> Any:
        """登录状态

        查询扫码登录的进度，供轮询使用。状态取值：waiting（等待扫码）、scanned（已扫码，等待确认）、verify（等待验证）、online（已上线）、cancelled（已取消）、expired（已过期）。状态为 waiting 时还会返回 expires_in，表示二维码此刻剩余的有效秒数，可用于校准倒计时。返回 notice 时，请把它原样展示给用户。Mac 端扫码后需要通过一次新设备验证，平台会自动完成这一步，这期间状态会一直保持为 scanned。请提示用户耐心等待，避免用户误以为登录卡住而取消登录。

        GET /v1/accounts/{account_id}/login/status
        """
        return self.call("GET", f"/accounts/{account_id}/login/status")

    def account_reconnect(self, account_id: str) -> Any:
        """重新连接

        实例掉线后，尝试在不重新扫码的情况下恢复连接。无法恢复时，才需要重新扫码登录。

        POST /v1/accounts/{account_id}/reconnect
        """
        return self.call("POST", f"/accounts/{account_id}/reconnect")

    def account_logout(self, account_id: str) -> Any:
        """退出登录

        让实例下线。实例和额度都会保留，之后可以重新扫码上线。

        POST /v1/accounts/{account_id}/logout
        """
        return self.call("POST", f"/accounts/{account_id}/logout")

    def account_delete(self, account_id: str) -> Any:
        """删除实例

        删除实例并归还额度。历史消息不会立即清除。在线的实例不能直接删除，请先调用「退出登录」。如果直接删除，微信端的登录会话会继续保持，而平台已经无法再关闭它。

        DELETE /v1/accounts/{account_id}
        """
        return self.call("DELETE", f"/accounts/{account_id}")

    def account_profile(self, account_id: str) -> Any:
        """实例资料

        查询这个实例自己的昵称、头像、地区等资料。

        建议缓存：资料很少变化，登录成功后获取一次并保存即可。平时需要 wxid、昵称、头像时，请读取「实例详情」中的 profile 字段。该字段来自平台已保存的数据，不会向微信发起请求。

        GET /v1/accounts/{account_id}/profile
        """
        return self.call("GET", f"/accounts/{account_id}/profile")

    def account_update_profile(self, account_id: str, *, city: Optional[str] = None, country: Optional[str] = None, nickname: Optional[str] = None, province: Optional[str] = None, sex: Optional[str] = None, signature: Optional[str] = None) -> Any:
        """修改个人资料

        修改昵称、签名、性别和地区。留空的字段会被清空，请把需要保留的字段一并传入。

        PUT /v1/accounts/{account_id}/profile

        参数：
          city             可选  市
          country          可选  国家
          nickname         可选  昵称
          province         可选  省
          sex              可选  1 男，2 女，0 不显示（0 / 1 / 2）
          signature        可选  个性签名
        """
        return self.call("PUT", f"/accounts/{account_id}/profile",
            body={"nickname": nickname, "signature": signature, "sex": sex, "country": country, "province": province, "city": city},
        )

    def account_set_avatar(self, account_id: str, *, url: str) -> Any:
        """修改头像

        修改头像。

        PUT /v1/accounts/{account_id}/profile/avatar

        参数：
          url              必填  公网可下载的图片地址
        """
        return self.call("PUT", f"/accounts/{account_id}/profile/avatar",
            body={"url": url},
        )

    def account_qrcode_self(self, account_id: str) -> Any:
        """我的二维码

        获取这个实例自己的名片二维码。返回 data URL，可直接用作 img 标签的 src。

        建议缓存：名片二维码基本不会变化。获取一次后保存为图片重复使用，不要每次展示时都重新获取。

        GET /v1/accounts/{account_id}/profile/qrcode
        """
        return self.call("GET", f"/accounts/{account_id}/profile/qrcode")

    def account_privacy(self, account_id: str, *, enabled: bool, option: str) -> Any:
        """隐私设置

        开启或关闭一项隐私设置。

        PUT /v1/accounts/{account_id}/privacy

        参数：
          enabled          必填  true 开启，false 关闭
          option           必填  need_confirm_to_add：加我为好友时需要验证；findable_by_phone：可以通过手机号搜到我；findable_by_alias：可以通过微信号搜到我；recommend_contacts：向我推荐通讯录好友；strangers_see_ten：允许陌生人查看十条朋友圈；…（need_confirm_to_add / findable_by_phone / findable_by_alias / recommend_contacts / strangers_see_ten / visible_days）
        """
        return self.call("PUT", f"/accounts/{account_id}/privacy",
            body={"option": option, "enabled": enabled},
        )

    def account_devices(self, account_id: str) -> Any:
        """已登录设备

        列出这个微信号登录过的设备，其中包括本平台。

        GET /v1/accounts/{account_id}/devices
        """
        return self.call("GET", f"/accounts/{account_id}/devices")

    def account_device_signout(self, account_id: str, device_id: str) -> Any:
        """下线某个设备

        让某个已登录的设备强制下线。注意不要把本平台自己也下线。

        DELETE /v1/accounts/{account_id}/devices/{device_id}
        """
        return self.call("DELETE", f"/accounts/{account_id}/devices/{device_id}")

    def account_history(self, account_id: str, *, keep: bool) -> Any:
        """消息保存设置

        设置这个实例是否保存收发的消息和推送记录。关闭后，新收发的消息不写入数据库，Webhook 推送记录在投递成功或放弃重试后立即删除。图片、语音、视频、文件仍然可以下载，自己发的消息仍然可以撤回，重复的推送仍然会去重。但无法查询历史消息，也无法转发文字和卡片消息。事件仍然会保存。关闭前已保存的消息不会立即删除，会按原来的保存期限自动清理。

        PUT /v1/accounts/{account_id}/history

        参数：
          keep             必填  true 保存，false 不保存
        """
        return self.call("PUT", f"/accounts/{account_id}/history",
            body={"keep": keep},
        )

    def account_webhook(self, account_id: str, *, url: str, events: Optional[list] = None, secret: Optional[str] = None) -> Any:
        """设置 Webhook

        设置接收该实例事件的 Webhook 地址。每次推送都带有签名，可以用 secret 校验。

        PUT /v1/accounts/{account_id}/webhook

        参数：
          url              必填  接收事件的地址
          events           可选  只推送这些类型的事件，留空则推送全部事件
          secret           可选  签名密钥，留空则保持不变
        """
        return self.call("PUT", f"/accounts/{account_id}/webhook",
            body={"url": url, "secret": secret, "events": events},
        )


    # --- 联系人 --------------------------------------------------------------

    def contact_ids(self, account_id: str, *, cursor: Optional[str] = None) -> Any:
        """通讯录列表

        列出通讯录中的全部条目，不做任何筛选，只返回标识：好友为 wxid，群 ID 以 @chatroom 结尾，公众号以 gh_ 开头。需要资料时，再用「联系人详情」按需查询。数据直接从微信获取，实例需要在线。每页条数由微信决定。翻页时把 next_cursor 原样传回，next_cursor 为空表示已经到最后一页。

        建议缓存：登录成功后拉一次完整列表，保存在你自己的系统中，之后根据事件更新：收到 friend.added 时添加新好友，收到 contact.updated 时更新联系人资料，收到 contact.deleted 时移除联系人。不要定时整份重拉：每次调用都会从微信拉取完整列表，联系人多时耗时长、开销大，频繁拉取还会增加被微信风控的概率。

        GET /v1/accounts/{account_id}/contacts

        参数：
          cursor           可选  上一页返回的 next_cursor，首页留空
        """
        return self.call("GET", f"/accounts/{account_id}/contacts",
            query={"cursor": cursor},
        )

    def contact_batch(self, account_id: str, *, wxids: list) -> Any:
        """批量取详情

        按 wxid 批量查询联系人资料。它和「联系人详情」调用的是微信的两个不同接口，一次查询很多人时，更适合用这个接口。 对个人好友，还会返回加好友的时间和方式：added_at、added_ts 是添加时间；add_source 是微信记录的添加方式编号，add_source_text 是它的中文说明，比如「扫一扫」「群聊」「搜索手机号」「名片分享」。含义还没有确认的编号只返回 add_source，不返回中文说明。通过群聊加的好友，add_source_group 是来源群的 ID。微信没有记录的项不返回。群和公众号不返回这几个字段。 所有联系人还会返回：avatar_large 高清头像（avatar 是小图）；pinyin 昵称和备注的拼音，全拼小写、首字母大写，可以用来排序和搜索；phones 是在微信备注里给这个人填写的电话号码。没有的项不返回。

        建议缓存：按 wxid 保存查到的资料，收到 contact.updated 事件时再更新对应联系人的资料。不要每收到一条消息就查询一次发送人的资料。

        POST /v1/accounts/{account_id}/contacts/batch

        参数：
          wxids            必填  要查询的 wxid 列表
        """
        return self.call("POST", f"/accounts/{account_id}/contacts/batch",
            body={"wxids": wxids},
        )

    def contact_detail(self, account_id: str, *, wxids: list) -> Any:
        """联系人详情

        查询联系人的完整资料，包括昵称、备注、微信号、头像、性别、地区、签名，以及该联系人的标签。标签在 label_ids 字段中，对应「标签列表」里的 ID；联系人没有标签时不返回这个字段。个人好友还会返回加好友的方式 add_source 和 add_source_text，通过群聊加的好友还有来源群 add_source_group；加好友的时间只有「批量取详情」能查到，这里不返回。高清头像 avatar_large、拼音 pinyin、备注电话 phones 和「批量取详情」一样返回。

        建议缓存：按 wxid 保存查到的资料，收到 contact.updated 事件时再更新对应联系人的资料。不要每收到一条消息就查询一次发送人的资料。

        POST /v1/accounts/{account_id}/contacts/detail

        参数：
          wxids            必填  要查询的 wxid，一次最多 50 个
        """
        return self.call("POST", f"/accounts/{account_id}/contacts/detail",
            body={"wxids": wxids},
        )

    def contact_check(self, account_id: str, *, wxids: list) -> Any:
        """检测好友关系

        检测这些人是否仍是你的好友。注意：微信对这个操作限制很严，一次检测的人数多或检测频繁，都可能导致实例被限制。一次最多检测 20 个，请按需使用。

        建议缓存：保存检测结果，同一个人在短时间内不要重复检测。

        POST /v1/accounts/{account_id}/contacts/check

        参数：
          wxids            必填  要检测的 wxid，一次最多 20 个
        """
        return self.call("POST", f"/accounts/{account_id}/contacts/check",
            body={"wxids": wxids},
        )

    def contact_external(self, account_id: str) -> Any:
        """企微联系人

        查询企业微信的外部联系人。这些联系人不在普通通讯录中，「通讯录列表」接口查不到他们。本接口返回平台已保存的数据，使用前请先调用一次「同步企微联系人」。

        GET /v1/accounts/{account_id}/contacts/external
        """
        return self.call("GET", f"/accounts/{account_id}/contacts/external")

    def contact_external_sync(self, account_id: str) -> Any:
        """同步企微联系人

        从微信重新拉取企业微信的外部联系人并保存到平台，返回拉取到的人数。没有头像的联系人会逐个补充获取头像，人数多时耗时较长，不建议频繁调用。

        POST /v1/accounts/{account_id}/contacts/external/sync
        """
        return self.call("POST", f"/accounts/{account_id}/contacts/external/sync")

    def contact_search(self, account_id: str, *, keyword: str) -> Any:
        """搜索用户

        按微信号或手机号搜索用户，返回可用于添加好友的 contact_token。

        建议缓存：保存搜索到的 wxid 和昵称，不要反复搜索同一个号。搜索过于频繁时，微信会提示操作过于频繁，之后一段时间内都无法搜索。contact_token 会过期，真正要添加好友时，再搜索一次获取新的 contact_token。

        POST /v1/accounts/{account_id}/contacts/search

        参数：
          keyword          必填  微信号或手机号
        """
        return self.call("POST", f"/accounts/{account_id}/contacts/search",
            body={"keyword": keyword},
        )

    def contact_add(self, account_id: str, *, contact_token: str, greeting: Optional[str] = None, scene: Optional[str] = None) -> Any:
        """添加好友

        用搜索得到的 contact_token 发起好友申请。**这个接口响应较慢**：微信需要 5～20 秒才返回结果，实测平均 9 秒，最慢 16 秒。客户端超时时间请至少设为 30 秒。请求超时后不要直接重发，因为请求很可能已经发送成功。需要重试时，请带上 Idempotency-Key。

        注意（易封号）：敏感接口，调用不当容易被微信限制甚至封号。添加好友是微信风控最严格的操作之一。不要在短时间内连续添加，不要批量自动加人，每次添加之间要留出间隔。新注册的号、刚换设备或刚登录的号风险更高，建议先正常使用几天再添加好友。

        POST /v1/accounts/{account_id}/contacts/add

        参数：
          contact_token    必填  搜索结果中的 contact_token
          greeting         可选  发给对方的验证消息
          scene            可选  申请来源，留空则使用默认值
        """
        return self.call("POST", f"/accounts/{account_id}/contacts/add",
            body={"contact_token": contact_token, "greeting": greeting, "scene": scene},
        )

    def contact_accept(self, account_id: str, *, friend_request_token: str) -> Any:
        """通过好友申请

        通过他人的好友申请。需要传入好友申请事件中的 friend_request_token。

        注意（易封号）：敏感接口，调用不当容易被微信限制甚至封号。短时间内大量通过好友申请同样会触发风控。不要在收到申请后立即批量自动通过，每次通过之间要留出间隔；申请数量多时，请分散到不同时间段处理。

        POST /v1/accounts/{account_id}/contacts/accept

        参数：
          friend_request_token 必填  好友申请事件中的 friend_request_token
        """
        return self.call("POST", f"/accounts/{account_id}/contacts/accept",
            body={"friend_request_token": friend_request_token},
        )

    def contact_remark(self, account_id: str, wxid: str, *, remark: str) -> Any:
        """设置备注

        修改一个联系人的备注名。

        PUT /v1/accounts/{account_id}/contacts/{wxid}/remark

        参数：
          remark           必填  新的备注名
        """
        return self.call("PUT", f"/accounts/{account_id}/contacts/{wxid}/remark",
            body={"remark": remark},
        )

    def contact_delete(self, account_id: str, wxid: str) -> Any:
        """删除好友

        将联系人从通讯录中删除。对方不会收到通知，但之后无法再给你发消息。如需恢复，需要重新添加好友。

        DELETE /v1/accounts/{account_id}/contacts/{wxid}
        """
        return self.call("DELETE", f"/accounts/{account_id}/contacts/{wxid}")

    def label_list(self, account_id: str) -> Any:
        """标签列表

        列出这个实例的联系人标签。标签仅自己可见。

        建议缓存：标签只有在你自己修改时才会变化。获取一次并保存，之后在新建、改名或删除标签后，再更新你保存的数据。

        GET /v1/accounts/{account_id}/labels
        """
        return self.call("GET", f"/accounts/{account_id}/labels")

    def label_add(self, account_id: str, *, name: str) -> Any:
        """新建标签

        新建一个联系人标签，返回该标签的 label_id。

        POST /v1/accounts/{account_id}/labels

        参数：
          name             必填  标签名
        """
        return self.call("POST", f"/accounts/{account_id}/labels",
            body={"name": name},
        )

    def label_rename(self, account_id: str, label_id: str, *, name: str) -> Any:
        """改标签名

        修改一个标签的名称。

        PUT /v1/accounts/{account_id}/labels/{label_id}

        参数：
          name             必填  新的标签名
        """
        return self.call("PUT", f"/accounts/{account_id}/labels/{label_id}",
            body={"name": name},
        )

    def label_delete(self, account_id: str, label_id: str) -> Any:
        """删除标签

        删除一个标签。带有这个标签的联系人本身不受影响，只是不再带有该标签。

        DELETE /v1/accounts/{account_id}/labels/{label_id}
        """
        return self.call("DELETE", f"/accounts/{account_id}/labels/{label_id}")

    def contact_labels(self, account_id: str, *, label_ids: list, wxids: list) -> Any:
        """设置联系人的标签

        为指定的联系人设置标签。设置采用覆盖方式：这些联系人原有的标签会全部替换为本次传入的标签。label_ids 传空数组表示移除他们的全部标签。不在 wxids 中的联系人不受影响。给某些联系人设置一个标签，不会把这个标签从其他联系人身上移除。

        PUT /v1/accounts/{account_id}/contacts/labels

        参数：
          label_ids        必填  设置后这些联系人拥有的全部标签 ID，对应「标签列表」里的 ID。传空数组表示不带任何标签
          wxids            必填  要设置标签的联系人 wxid，一次最多 50 个
        """
        return self.call("PUT", f"/accounts/{account_id}/contacts/labels",
            body={"wxids": wxids, "label_ids": label_ids},
        )


    # --- 群 ------------------------------------------------------------------

    def group_create(self, account_id: str, *, members: list) -> Any:
        """创建群聊

        邀请几位好友创建一个群聊，至少需要两个成员。

        POST /v1/accounts/{account_id}/groups

        参数：
          members          必填  初始成员的 wxid
        """
        return self.call("POST", f"/accounts/{account_id}/groups",
            body={"members": members},
        )

    def group_get(self, account_id: str, group_id: str) -> Any:
        """群详情

        查询群的名称、公告、群主等资料。

        建议缓存：保存群资料，收到 group.renamed 事件时再重新获取。公告和群主很少变化，不要每收到一条群消息就查询一次。

        GET /v1/accounts/{account_id}/groups/{group_id}
        """
        return self.call("GET", f"/accounts/{account_id}/groups/{group_id}")

    def group_members(self, account_id: str, group_id: str) -> Any:
        """群成员

        列出群成员。

        建议缓存：保存成员列表，之后根据 group.member_joined 和 group.member_left 事件增减成员。每次调用都会实时从微信拉取，大群耗时长、开销大，不要定时重新拉取整个列表。

        GET /v1/accounts/{account_id}/groups/{group_id}/members
        """
        return self.call("GET", f"/accounts/{account_id}/groups/{group_id}/members")

    def group_member_detail(self, account_id: str, group_id: str, *, members: list) -> Any:
        """群成员详情

        查询指定群成员的完整资料，字段比「群成员」接口更全。

        建议缓存：按 wxid 保存成员资料，不要每收到一条群消息就查询一次发言人的资料。

        POST /v1/accounts/{account_id}/groups/{group_id}/members/detail

        参数：
          members          必填  要查询的 wxid
        """
        return self.call("POST", f"/accounts/{account_id}/groups/{group_id}/members/detail",
            body={"members": members},
        )

    def group_invite(self, account_id: str, group_id: str, *, members: list, reason: Optional[str] = None) -> Any:
        """邀请入群

        邀请好友入群。群人数较多时，微信会改为发送邀请链接。

        POST /v1/accounts/{account_id}/groups/{group_id}/invite

        参数：
          members          必填  要邀请的 wxid
          reason           可选  邀请说明
        """
        return self.call("POST", f"/accounts/{account_id}/groups/{group_id}/invite",
            body={"members": members, "reason": reason},
        )

    def group_remove(self, account_id: str, group_id: str, *, members: list) -> Any:
        """移出群成员

        将成员移出群聊。只有群主和管理员可以操作。

        POST /v1/accounts/{account_id}/groups/{group_id}/members/remove

        参数：
          members          必填  要移出的 wxid
        """
        return self.call("POST", f"/accounts/{account_id}/groups/{group_id}/members/remove",
            body={"members": members},
        )

    def group_admins(self, account_id: str, group_id: str, *, action: str, members: list) -> Any:
        """群管理员

        设置或取消群管理员，也可以转让群主。只有群主可以操作。

        POST /v1/accounts/{account_id}/groups/{group_id}/admins

        参数：
          action           必填  grant 设为管理员，revoke 取消管理员，transfer 转让群主（转让群主时 members 只能填一个人）（grant / revoke / transfer）
          members          必填  目标成员的 wxid
        """
        return self.call("POST", f"/accounts/{account_id}/groups/{group_id}/admins",
            body={"action": action, "members": members},
        )

    def group_rename(self, account_id: str, group_id: str, *, name: str) -> Any:
        """修改群名

        修改群名称。需要有修改群名称的权限。

        PUT /v1/accounts/{account_id}/groups/{group_id}/name

        参数：
          name             必填  新的群名称
        """
        return self.call("PUT", f"/accounts/{account_id}/groups/{group_id}/name",
            body={"name": name},
        )

    def group_announcement(self, account_id: str, group_id: str, *, content: str) -> Any:
        """设置群公告

        修改群公告。只有群主和管理员可以操作，修改后会向全群发送一条提示。

        PUT /v1/accounts/{account_id}/groups/{group_id}/announcement

        参数：
          content          必填  公告正文，留空表示清除
        """
        return self.call("PUT", f"/accounts/{account_id}/groups/{group_id}/announcement",
            body={"content": content},
        )

    def group_remark(self, account_id: str, group_id: str, *, remark: str) -> Any:
        """设置群备注

        为群设置一个仅自己可见的备注名。

        PUT /v1/accounts/{account_id}/groups/{group_id}/remark

        参数：
          remark           必填  备注名，留空表示清除
        """
        return self.call("PUT", f"/accounts/{account_id}/groups/{group_id}/remark",
            body={"remark": remark},
        )

    def group_nickname(self, account_id: str, group_id: str, *, nickname: str) -> Any:
        """设置我的群昵称

        修改自己在这个群里显示的昵称。

        PUT /v1/accounts/{account_id}/groups/{group_id}/nickname

        参数：
          nickname         必填  群内昵称
        """
        return self.call("PUT", f"/accounts/{account_id}/groups/{group_id}/nickname",
            body={"nickname": nickname},
        )

    def group_kept(self, account_id: str, group_id: str, *, enabled: bool) -> Any:
        """保存到通讯录

        将群保存到通讯录，或取消保存。没有保存到通讯录的群，在聊天会话被删除后将无法再找到。

        PUT /v1/accounts/{account_id}/groups/{group_id}/kept

        参数：
          enabled          必填  true 保存，false 取消
        """
        return self.call("PUT", f"/accounts/{account_id}/groups/{group_id}/kept",
            body={"enabled": enabled},
        )

    def group_qrcode(self, account_id: str, group_id: str) -> Any:
        """群二维码

        获取群的邀请二维码。返回 data URL，可直接用作 img 标签的 src。

        建议缓存：群二维码 7 天内有效。获取一次后保存为图片，快过期时再重新获取。

        GET /v1/accounts/{account_id}/groups/{group_id}/qrcode
        """
        return self.call("GET", f"/accounts/{account_id}/groups/{group_id}/qrcode")

    def group_join(self, account_id: str, *, url: str) -> Any:
        """通过链接进群

        通过收到的群邀请链接加入群聊。

        POST /v1/accounts/{account_id}/groups/join

        参数：
          url              必填  邀请链接
        """
        return self.call("POST", f"/accounts/{account_id}/groups/join",
            body={"url": url},
        )

    def group_preview(self, account_id: str, *, url: str) -> Any:
        """查看群邀请

        查看群邀请链接对应的群信息，不会加入该群。usable 为 false 时，notice 字段说明原因，比如链接已过期。

        POST /v1/accounts/{account_id}/groups/preview

        参数：
          url              必填  邀请链接
        """
        return self.call("POST", f"/accounts/{account_id}/groups/preview",
            body={"url": url},
        )

    def group_approve(self, account_id: str, group_id: str, *, inviter: str, members: list, message_id: str, ticket: str) -> Any:
        """同意入群邀请

        群成员邀请他人入群后，群主用这个接口同意邀请。inviter、message_id、ticket、members 四个参数都来自这条邀请事件。

        POST /v1/accounts/{account_id}/groups/{group_id}/approve

        参数：
          inviter          必填  邀请人的 wxid
          members          必填  被邀请人的 wxid
          message_id       必填  邀请事件中的消息 ID
          ticket           必填  邀请事件中的凭据
        """
        return self.call("POST", f"/accounts/{account_id}/groups/{group_id}/approve",
            body={"inviter": inviter, "message_id": message_id, "ticket": ticket, "members": members},
        )

    def chat_muted(self, account_id: str, chat_id: str, *, enabled: bool) -> Any:
        """消息免打扰

        为一个群或一个好友开启或关闭消息免打扰。

        PUT /v1/accounts/{account_id}/chats/{chat_id}/muted

        参数：
          enabled          必填  true 开启免打扰，false 恢复消息提醒
        """
        return self.call("PUT", f"/accounts/{account_id}/chats/{chat_id}/muted",
            body={"enabled": enabled},
        )

    def chat_pinned(self, account_id: str, chat_id: str, *, enabled: bool) -> Any:
        """聊天置顶

        将一个群或一个好友的会话置顶，或取消置顶。

        PUT /v1/accounts/{account_id}/chats/{chat_id}/pinned

        参数：
          enabled          必填  true 置顶，false 取消
        """
        return self.call("PUT", f"/accounts/{account_id}/chats/{chat_id}/pinned",
            body={"enabled": enabled},
        )


    # --- 消息 ----------------------------------------------------------------

    def message_text(self, account_id: str, *, content: str, to: str, mentions: Optional[list] = None) -> Any:
        """发文字

        发送一条文字消息。在群里发送时可以 @ 群成员。

        POST /v1/accounts/{account_id}/messages/text

        参数：
          content          必填  消息正文
          to               必填  接收方：好友的 wxid、群 ID，或 filehelper（自己的文件传输助手）
          mentions         可选  要 @ 的成员 wxid，仅在群聊中有效
        """
        return self.call("POST", f"/accounts/{account_id}/messages/text",
            body={"to": to, "content": content, "mentions": mentions},
        )

    def message_image(self, account_id: str, *, to: str, media_id: Optional[str] = None, url: Optional[str] = None, use_cache: Optional[bool] = None) -> Any:
        """发图片

        发送一张图片。url 和 media_id 二选一，使用 media_id 可以复用平台已保存的文件。

        POST /v1/accounts/{account_id}/messages/image

        参数：
          to               必填  接收方：好友的 wxid、群 ID，或 filehelper（自己的文件传输助手）
          media_id         可选  平台中已有文件的媒体 ID
          url              可选  可从公网下载的文件地址
          use_cache        可选  同一个 url 之前发送过时，直接复用已上传的文件，不再重新上传。默认 true。只有地址不变但文件内容已更换时，才需要传 false
        """
        return self.call("POST", f"/accounts/{account_id}/messages/image",
            body={"to": to, "url": url, "media_id": media_id, "use_cache": use_cache},
        )

    def message_video(self, account_id: str, *, to: str, duration: Optional[int] = None, media_id: Optional[str] = None, thumbnail_url: Optional[str] = None, url: Optional[str] = None, use_cache: Optional[bool] = None) -> Any:
        """发视频

        发送一段视频。不填时长时由平台估算，部分客户端可能会显示异常。

        POST /v1/accounts/{account_id}/messages/video

        参数：
          to               必填  接收方：好友的 wxid、群 ID，或 filehelper（自己的文件传输助手）
          duration         可选  时长（秒）
          media_id         可选  平台中已有文件的媒体 ID
          thumbnail_url    可选  封面图地址，需要是可从公网下载的图片
          url              可选  可从公网下载的文件地址
          use_cache        可选  同一个 url 之前发送过时，直接复用已上传的文件，不再重新上传。默认 true。只有地址不变但文件内容已更换时，才需要传 false
        """
        return self.call("POST", f"/accounts/{account_id}/messages/video",
            body={"to": to, "url": url, "media_id": media_id, "use_cache": use_cache, "duration": duration, "thumbnail_url": thumbnail_url},
        )

    def message_voice(self, account_id: str, *, to: str, url: str, seconds: Optional[int] = None) -> Any:
        """发语音

        发送一条语音。seconds 是语音时长，会显示在聊天中的语音消息上。

        POST /v1/accounts/{account_id}/messages/voice

        参数：
          to               必填  接收方：好友的 wxid、群 ID，或 filehelper（自己的文件传输助手）
          url              必填  可从公网下载的音频地址
          seconds          可选  时长（秒）
        """
        return self.call("POST", f"/accounts/{account_id}/messages/voice",
            body={"to": to, "url": url, "seconds": seconds},
        )

    def message_file(self, account_id: str, *, to: str, filename: Optional[str] = None, media_id: Optional[str] = None, url: Optional[str] = None, use_cache: Optional[bool] = None) -> Any:
        """发文件

        发送一个文件。

        POST /v1/accounts/{account_id}/messages/file

        参数：
          to               必填  接收方：好友的 wxid、群 ID，或 filehelper（自己的文件传输助手）
          filename         可选  对方看到的文件名
          media_id         可选  平台中已有文件的媒体 ID
          url              可选  可从公网下载的文件地址
          use_cache        可选  同一个 url 之前发送过时，直接复用已上传的文件，不再重新上传。默认 true。只有地址不变但文件内容已更换时，才需要传 false
        """
        return self.call("POST", f"/accounts/{account_id}/messages/file",
            body={"to": to, "url": url, "media_id": media_id, "use_cache": use_cache, "filename": filename},
        )

    def message_sticker(self, account_id: str, *, checksum: str, length: int, to: str) -> Any:
        """发动图表情

        转发一个动图表情。表情通过引用发送，无需上传文件。checksum 和 length 取自收到的表情消息。

        POST /v1/accounts/{account_id}/messages/sticker

        参数：
          checksum         必填  表情的校验值，取自收到的表情消息
          length           必填  表情的字节数，取自同一条表情消息
          to               必填  接收方：好友的 wxid、群 ID，或 filehelper（自己的文件传输助手）
        """
        return self.call("POST", f"/accounts/{account_id}/messages/sticker",
            body={"to": to, "checksum": checksum, "length": length},
        )

    def message_link(self, account_id: str, *, title: str, to: str, url: str, description: Optional[str] = None, source_name: Optional[str] = None, thumb_url: Optional[str] = None) -> Any:
        """发链接卡片

        发送一张可点击的链接卡片。

        POST /v1/accounts/{account_id}/messages/link

        参数：
          title            必填  卡片标题
          to               必填  接收方：好友的 wxid、群 ID，或 filehelper（自己的文件传输助手）
          url              必填  点击后打开的地址
          description      可选  卡片摘要
          source_name      可选  来源名称
          thumb_url        可选  封面图地址
        """
        return self.call("POST", f"/accounts/{account_id}/messages/link",
            body={"to": to, "title": title, "description": description, "url": url, "thumb_url": thumb_url, "source_name": source_name},
        )

    def message_miniapp(self, account_id: str, *, app_id: str, title: str, to: str, username: str, description: Optional[str] = None, path: Optional[str] = None, source_name: Optional[str] = None, thumb_url: Optional[str] = None) -> Any:
        """发小程序卡片

        发送一张小程序卡片。需要提供小程序的标识。

        POST /v1/accounts/{account_id}/messages/miniapp

        参数：
          app_id           必填  小程序的公开标识
          title            必填  卡片标题
          to               必填  接收方：好友的 wxid、群 ID，或 filehelper（自己的文件传输助手）
          username         必填  小程序的原始 ID
          description      可选  卡片摘要
          path             可选  点击后打开的小程序页面路径
          source_name      可选  来源名称
          thumb_url        可选  封面图地址
        """
        return self.call("POST", f"/accounts/{account_id}/messages/miniapp",
            body={"to": to, "app_id": app_id, "username": username, "title": title, "description": description, "path": path, "thumb_url": thumb_url, "source_name": source_name},
        )

    def message_forward(self, account_id: str, *, message_id: str, to: str) -> Any:
        """转发消息

        将收到过的一条消息原样转发给其他人。

        POST /v1/accounts/{account_id}/messages/forward

        参数：
          message_id       必填  要转发的消息 ID
          to               必填  接收方：好友的 wxid、群 ID，或 filehelper（自己的文件传输助手）
        """
        return self.call("POST", f"/accounts/{account_id}/messages/forward",
            body={"to": to, "message_id": message_id},
        )

    def message_recall(self, account_id: str, message_id: str) -> Any:
        """撤回消息

        撤回自己发出的一条消息。微信只允许在发出后约两分钟内撤回，超过时间会被拒绝。

        POST /v1/accounts/{account_id}/messages/{message_id}/recall
        """
        return self.call("POST", f"/accounts/{account_id}/messages/{message_id}/recall")

    def message_history(self, account_id: str, *, cursor: Optional[str] = None, limit: Optional[int] = None, peer: Optional[str] = None) -> Any:
        """消息记录

        查询平台保存的消息记录，可以按会话筛选。

        GET /v1/accounts/{account_id}/messages

        参数：
          cursor           可选  上一页返回的 next_cursor，首页留空
          limit            可选  每页条数，最多 200，超过按 200 处理
          peer             可选  只返回与某个 wxid 或群的会话中的消息
        """
        return self.call("GET", f"/accounts/{account_id}/messages",
            query={"peer": peer, "cursor": cursor, "limit": limit},
        )

    def message_sync(self, account_id: str, *, cursor: Optional[str] = None) -> Any:
        """同步消息

        主动拉取这个实例收到的消息，内容与 Webhook 推送的完全相同。如果没有配置 Webhook、Webhook 中断过，或者服务重启过，可以用它补回这段时间的消息。cursor 留空时，从目前仍保留的最早一条消息开始返回（大约可追溯一天）。之后每次调用都传入上一次返回的 next_cursor。has_more 为 true 表示还没有拉取完，请立即再调用一次。没有新消息时，返回的 next_cursor 与传入的相同，游标不会前进。拉取到的消息不会写入数据库，也不会触发 Webhook，重复拉取没有副作用。

        POST /v1/accounts/{account_id}/messages/sync

        参数：
          cursor           可选  上一次返回的 next_cursor，第一次留空
        """
        return self.call("POST", f"/accounts/{account_id}/messages/sync",
            body={"cursor": cursor},
        )

    def message_get(self, account_id: str, message_id: str) -> Any:
        """消息详情

        查询一条消息。

        GET /v1/accounts/{account_id}/messages/{message_id}
        """
        return self.call("GET", f"/accounts/{account_id}/messages/{message_id}")

    def favorite_list(self, account_id: str, *, cursor: Optional[str] = None) -> Any:
        """收藏列表

        列出这个实例收藏的内容。cursor 留空时从第一页开始，返回的 next_cursor 为空表示已经到最后一页。

        建议缓存：收藏只在你自己新增或删除收藏时才会变化。获取一次并保存，不要轮询；在你新增或删除收藏后再重新获取。

        GET /v1/accounts/{account_id}/favorites

        参数：
          cursor           可选  上一页返回的 next_cursor，首页留空
        """
        return self.call("GET", f"/accounts/{account_id}/favorites",
            query={"cursor": cursor},
        )

    def favorite_get(self, account_id: str, fav_id: str) -> Any:
        """收藏详情

        查询一条收藏的完整内容。内容为微信原始的 XML，平台原样返回，不同类型的收藏结构不同。

        建议缓存：收藏的内容不会变化。按 fav_id 保存，获取过一次就不需要再获取。

        GET /v1/accounts/{account_id}/favorites/{fav_id}
        """
        return self.call("GET", f"/accounts/{account_id}/favorites/{fav_id}")

    def favorite_delete(self, account_id: str, fav_id: str) -> Any:
        """删除收藏

        删除一条收藏。

        DELETE /v1/accounts/{account_id}/favorites/{fav_id}
        """
        return self.call("DELETE", f"/accounts/{account_id}/favorites/{fav_id}")


    # --- 媒体 ----------------------------------------------------------------

    def media_upload(self, account_id: str, *, file: Any, kind: Optional[str] = None, filename: str = "", content_type: str = "") -> Any:
        """上传文件

        直接上传文件，获取一个 media_id。之后发送图片、视频、语音或文件时，只需传入这个 ID。适用于文件在你自己的机器上、没有公网地址的情况，比如程序刚生成的一张图片。请用 multipart/form-data 提交，文件放在 file 字段中，最大 20 MB。文件在第一次发送时才会真正上传到微信，之后用同一个 ID 发送不会重复上传。上传后一直没有发送过的文件保留 24 小时。

        POST /v1/accounts/{account_id}/media/upload

        参数：
          file             必填  要上传的文件，multipart/form-data
          kind             可选  这个文件将作为哪种消息发送，不填则根据文件类型自动判断（image / video / voice / file）
        """
        return self.upload(f"/accounts/{account_id}/media/upload", file,
            filename=filename, content_type=content_type,
            fields={"kind": kind},
        )

    def media_from_message(self, account_id: str, *, message_id: str) -> Any:
        """下载消息附件

        获取一条消息中的图片、视频、文件或语音，返回一个限时有效的下载地址。

        建议缓存：下载地址有时效。拿到文件后请保存到你自己的存储中，不要每次展示时都重新下载。平台上已下载文件的总量超过上限时，会清除最早的一半，请不要把平台当作长期存储。

        POST /v1/accounts/{account_id}/media/download

        参数：
          message_id       必填  带附件的消息 ID
        """
        return self.call("POST", f"/accounts/{account_id}/media/download",
            body={"message_id": message_id},
        )

    def media_cached(self, account_id: str, *, kind: str, url: str) -> Any:
        """查文件是否已缓存

        查询平台是否已经发送过某个地址的文件。发送过的文件可以直接复用，不需要重新上传，也不计流量。建议在把文件放到公网之前先调用这个接口。如果平台已经发送过，就不必再把文件放到公网。

        POST /v1/accounts/{account_id}/media/cached

        参数：
          kind             必填  image、video 或 file
          url              必填  要发送的文件地址，必须与发送时填写的地址完全一致才算命中
        """
        return self.call("POST", f"/accounts/{account_id}/media/cached",
            body={"url": url, "kind": kind},
        )

    def media_get(self, account_id: str, media_id: str) -> Any:
        """重新取下载地址

        为已经下载过的文件重新生成一个限时有效的下载地址。

        GET /v1/accounts/{account_id}/media/{media_id}
        """
        return self.call("GET", f"/accounts/{account_id}/media/{media_id}")

    def media_moment(self, account_id: str, moment_id: str, *, index: Optional[int] = None) -> Any:
        """下载动态媒体

        获取一条朋友圈动态中的第 N 张图片，或动态中的视频。

        POST /v1/accounts/{account_id}/moments/{moment_id}/media/download

        参数：
          index            可选  图片序号，从 0 开始。视频动态会忽略这个值
        """
        return self.call("POST", f"/accounts/{account_id}/moments/{moment_id}/media/download",
            body={"index": index},
        )


    # --- 朋友圈 --------------------------------------------------------------

    def moment_timeline(self, account_id: str, *, cursor: Optional[str] = None) -> Any:
        """我的朋友圈

        查询这个实例能看到的朋友圈时间线。

        建议缓存：每次调用都会实时向微信请求，请不要定时刷新，刷新过于频繁会被视为异常行为。需要时再获取，并把获取到的动态保存在你自己的系统中。

        GET /v1/accounts/{account_id}/moments

        参数：
          cursor           可选  上一页返回的 next_cursor
        """
        return self.call("GET", f"/accounts/{account_id}/moments",
            query={"cursor": cursor},
        )

    def moment_get(self, account_id: str, moment_id: str) -> Any:
        """朋友圈详情

        查询一条朋友圈的完整内容。列表接口中的点赞和评论会被截断，这个接口返回完整的点赞和评论。

        建议缓存：动态的正文和图片不会变化，获取后请保存。只有需要查看最新的点赞和评论时，才需要重新获取。

        GET /v1/accounts/{account_id}/moments/{moment_id}
        """
        return self.call("GET", f"/accounts/{account_id}/moments/{moment_id}")

    def moment_user(self, account_id: str, wxid: str, *, cursor: Optional[str] = None) -> Any:
        """某人的朋友圈

        查询某个联系人的朋友圈主页。

        建议缓存：每次调用都会实时向微信请求，请不要定时刷新，刷新过于频繁会被视为异常行为。需要时再获取，并把获取到的动态保存在你自己的系统中。

        GET /v1/accounts/{account_id}/moments/user/{wxid}

        参数：
          cursor           可选  上一页返回的 next_cursor
        """
        return self.call("GET", f"/accounts/{account_id}/moments/user/{wxid}",
            query={"cursor": cursor},
        )

    def moment_post_text(self, account_id: str, *, content: str, mentions: Optional[list] = None, visibility: Optional[dict] = None) -> Any:
        """发文字动态

        发布一条纯文字朋友圈。

        POST /v1/accounts/{account_id}/moments/text

        参数：
          content          必填  正文
          mentions         可选  要 @ 的 wxid
          visibility       可选  可见范围。mode 可选 public、private、allow、deny；选择 allow 或 deny 时，需要同时提供 wxids 或 tag_ids
        """
        return self.call("POST", f"/accounts/{account_id}/moments/text",
            body={"content": content, "mentions": mentions, "visibility": visibility},
        )

    def moment_post_images(self, account_id: str, *, images: list, content: Optional[str] = None, visibility: Optional[dict] = None) -> Any:
        """发图片动态

        发布一条带图片的朋友圈。

        POST /v1/accounts/{account_id}/moments/images

        参数：
          images           必填  图片列表
          content          可选  正文
          visibility       可选  可见范围。mode 可选 public、private、allow、deny；选择 allow 或 deny 时，需要同时提供 wxids 或 tag_ids
        """
        return self.call("POST", f"/accounts/{account_id}/moments/images",
            body={"content": content, "images": images, "visibility": visibility},
        )

    def moment_post_video(self, account_id: str, *, video: dict, content: Optional[str] = None, cover: Optional[dict] = None, duration: Optional[int] = None, visibility: Optional[dict] = None) -> Any:
        """发视频动态

        发布一条视频朋友圈。

        POST /v1/accounts/{account_id}/moments/video

        参数：
          video            必填  视频，需提供可从公网下载的地址
          content          可选  正文
          cover            可选  封面图
          duration         可选  时长（秒）
          visibility       可选  可见范围。mode 可选 public、private、allow、deny；选择 allow 或 deny 时，需要同时提供 wxids 或 tag_ids
        """
        return self.call("POST", f"/accounts/{account_id}/moments/video",
            body={"content": content, "video": video, "cover": cover, "duration": duration, "visibility": visibility},
        )

    def moment_repost(self, account_id: str, *, moment_id: str, visibility: Optional[dict] = None) -> Any:
        """转发动态

        将看到的一条动态原样重新发布一次。

        POST /v1/accounts/{account_id}/moments/forward

        参数：
          moment_id        必填  要转发的动态 ID
          visibility       可选  可见范围。mode 可选 public、private、allow、deny；选择 allow 或 deny 时，需要同时提供 wxids 或 tag_ids
        """
        return self.call("POST", f"/accounts/{account_id}/moments/forward",
            body={"moment_id": moment_id, "visibility": visibility},
        )

    def moment_like(self, account_id: str, moment_id: str) -> Any:
        """点赞

        给一条动态点赞。点赞前需要先通过朋友圈列表或详情接口读取过这条动态，读取后 24 小时内可以点赞。

        POST /v1/accounts/{account_id}/moments/{moment_id}/like
        """
        return self.call("POST", f"/accounts/{account_id}/moments/{moment_id}/like")

    def moment_unlike(self, account_id: str, moment_id: str) -> Any:
        """取消赞

        取消对一条动态的赞。

        DELETE /v1/accounts/{account_id}/moments/{moment_id}/like
        """
        return self.call("DELETE", f"/accounts/{account_id}/moments/{moment_id}/like")

    def moment_comment(self, account_id: str, moment_id: str, *, content: str, reply_to: Optional[int] = None) -> Any:
        """评论

        评论一条动态，或回复别人的评论。

        POST /v1/accounts/{account_id}/moments/{moment_id}/comments

        参数：
          content          必填  评论内容，最多 500 字
          reply_to         可选  要回复的评论 ID，留空表示直接评论这条动态
        """
        return self.call("POST", f"/accounts/{account_id}/moments/{moment_id}/comments",
            body={"content": content, "reply_to": reply_to},
        )

    def moment_delete_comment(self, account_id: str, moment_id: str, comment_id: str) -> Any:
        """删除评论

        删除自己发表的一条评论。

        DELETE /v1/accounts/{account_id}/moments/{moment_id}/comments/{comment_id}
        """
        return self.call("DELETE", f"/accounts/{account_id}/moments/{moment_id}/comments/{comment_id}")

    def moment_delete(self, account_id: str, moment_id: str) -> Any:
        """删除动态

        删除自己发布的一条朋友圈。

        DELETE /v1/accounts/{account_id}/moments/{moment_id}
        """
        return self.call("DELETE", f"/accounts/{account_id}/moments/{moment_id}")

    def moment_privacy(self, account_id: str, moment_id: str, *, private: bool) -> Any:
        """设为私密 / 公开

        将自己的一条动态设为仅自己可见，或恢复为公开。

        PUT /v1/accounts/{account_id}/moments/{moment_id}/privacy

        参数：
          private          必填  true 表示仅自己可见，false 表示公开
        """
        return self.call("PUT", f"/accounts/{account_id}/moments/{moment_id}/privacy",
            body={"private": private},
        )


    # --- 平台 ----------------------------------------------------------------

    def platform_me(self) -> Any:
        """当前用户

        查询当前 API Key 所属的用户，以及该用户的实例数量。

        GET /v1/me
        """
        return self.call("GET", "/me")

    def platform_events(self, *, account_id: Optional[str] = None, cursor: Optional[str] = None, keyword: Optional[str] = None, limit: Optional[int] = None, message_type: Optional[str] = None, order: Optional[str] = None, since: Optional[str] = None, type: Optional[str] = None, until: Optional[str] = None) -> Any:
        """事件列表

        查询平台记录的事件，可以按实例、事件类型、消息类型和时间筛选。没有配置 Webhook 时，可以轮询这个接口获取事件。 轮询方法：第一次调用可以用 since 指定起始时间。之后每次调用都传入上一次返回的 next_cursor，从该位置之后继续读取。只要本页有事件，就一定会返回 next_cursor。没有新事件时 next_cursor 为空，此时请继续使用你已保存的上一个 next_cursor。has_more 为 true 表示后面还有事件，请立即继续读取；否则请等待几秒后再轮询。如果处理过程中程序重启，而最新的游标还没来得及保存，重新读取时会再次拿到相同的几条事件，因此建议按 event_id 去重。

        GET /v1/events

        参数：
          account_id       可选  只返回某个实例的事件
          cursor           可选  上一次返回的 next_cursor，从该位置之后继续读取。第一次调用时留空。如果返回的 next_cursor 为空，请继续使用上一次的值
          keyword          可选  按事件内容搜索。需要同时指定时间范围，且范围不超过 1 小时
          limit            可选  每页条数，最多 200，超过按 200 处理
          message_type     可选  只返回某种消息类型的事件，如 text、image、file，参数可重复传入。设置后，非消息类事件不会出现在结果中
          order            可选  oldest 按时间从早到晚返回（默认），newest 从最近发生的事件开始返回（oldest / newest）
          since            可选  只返回这个时间之后的事件，格式为 RFC3339 或 Unix 秒级时间戳
          type             可选  只返回某种类型的事件，参数可重复传入
          until            可选  只返回这个时间之前的事件，格式为 RFC3339 或 Unix 秒级时间戳
        """
        return self.call("GET", "/events",
            query={"account_id": account_id, "type": type, "message_type": message_type, "since": since, "until": until, "keyword": keyword, "order": order, "cursor": cursor, "limit": limit},
        )

    def platform_stream(self, account_id: str) -> Any:
        """事件流

        通过 SSE 长连接实时接收该实例的事件，事件内容与 Webhook 推送的相同。

        GET /v1/accounts/{account_id}/stream
        """
        return self.call("GET", f"/accounts/{account_id}/stream")



def _clean(body: dict) -> dict:
    """没填的参数不发出去，免得把服务端的默认值覆盖掉。"""
    return {k: v for k, v in body.items() if v is not None}
