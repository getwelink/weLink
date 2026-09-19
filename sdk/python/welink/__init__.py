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


    # --- 实例 ----------------------------------------------------------------

    def account_create(self, *, platform: str, name: Optional[str] = None, proxy: Optional[str] = None, webhook_url: Optional[str] = None) -> Any:
        """创建实例

        开一个实例。占用一个额度，删除后归还。创建后还要扫码才会上线。

        POST /v1/accounts

        参数：
          platform         必填  登录方式（ipad / mac）
          name             可选  备注名称，只给自己看
          proxy            可选  代理网络，留空则直连。两种填法：socks5 代理地址（socks5://user:pass@host:port），或者网络助手的网络ID（把网络助手装到一台手机上，打开即可看到，这台手机的网络就是这个实例的出口）—— 填网络ID时地址由平台代取，网络助手离线会被拒绝，凭据轮换后重连前会自动重取
          webhook_url      可选  该实例的事件推送地址
        """
        return self.call("POST", "/accounts",
            body={"platform": platform, "name": name, "proxy": proxy, "webhook_url": webhook_url},
        )

    def account_list(self) -> Any:
        """实例列表

        列出你的全部实例与它们的状态。

        GET /v1/accounts
        """
        return self.call("GET", "/accounts")

    def account_get(self, account_id: str) -> Any:
        """实例详情

        读一个实例。不在线时 reason 会说明原因（manual 主动退出、kicked 被别处挤下线、relogin_required 需重新扫码、recover_timeout 恢复超时、expired 授权到期）；status 为 recovering 时 recovering 里带恢复方式与放弃时间。

        GET /v1/accounts/{account_id}
        """
        return self.call("GET", f"/accounts/{account_id}")

    def account_qrcode(self, account_id: str, *, proxy: Optional[str] = None) -> Any:
        """获取登录二维码

        取一张登录二维码，用手机扫。expires_in 是这张码还剩多少秒，以返回值为准，不要写死；过期了再取一张即可。带上 proxy 可以顺便换代理网络 —— 它是开会话时定下的，换了要重开会话，所以只能在扫码这一刻换；不传则沿用原来的。

        POST /v1/accounts/{account_id}/login/qrcode

        参数：
          proxy            可选  改用这个代理网络：socks5 代理地址或网络助手的网络ID；传空串改为直连，不传则不动
        """
        return self.call("POST", f"/accounts/{account_id}/login/qrcode",
            body={"proxy": proxy},
        )

    def account_login_status(self, account_id: str) -> Any:
        """登录状态

        轮询扫码进度：waiting（等待扫码）、scanned（已扫码待确认）、online（已上线）、cancelled、expired。等待扫码时还带 expires_in，是这张码此刻还剩多少秒，用它校准倒计时。

        GET /v1/accounts/{account_id}/login/status
        """
        return self.call("GET", f"/accounts/{account_id}/login/status")

    def account_login_cancel(self, account_id: str) -> Any:
        """取消扫码

        放弃这次扫码。已经发出去的码会连同它背后的会话一起作废，扫了也不会让这个实例上线；实例回到未登录，重新取码即可。关闭扫码页面时调用它，别把一张还能用的码留在外面。

        POST /v1/accounts/{account_id}/login/cancel
        """
        return self.call("POST", f"/accounts/{account_id}/login/cancel")

    def account_captcha(self, account_id: str, *, fields: dict) -> Any:
        """提交安全验证

        登录过程中出现安全验证时，把验证结果提交回来。

        POST /v1/accounts/{account_id}/login/captcha

        参数：
          fields           必填  验证所需的字段，按提示填写
        """
        return self.call("POST", f"/accounts/{account_id}/login/captcha",
            body={"fields": fields},
        )

    def account_reconnect(self, account_id: str) -> Any:
        """重新连接

        掉线后尝试不重新扫码就恢复连接。恢复不了才需要重新扫码。

        POST /v1/accounts/{account_id}/reconnect
        """
        return self.call("POST", f"/accounts/{account_id}/reconnect")

    def account_logout(self, account_id: str) -> Any:
        """退出登录

        让实例下线。实例与额度保留，可以再扫码上线。

        POST /v1/accounts/{account_id}/logout
        """
        return self.call("POST", f"/accounts/{account_id}/logout")

    def account_delete(self, account_id: str) -> Any:
        """删除实例

        删除槽位并归还额度。历史消息不会立刻清除。

        DELETE /v1/accounts/{account_id}
        """
        return self.call("DELETE", f"/accounts/{account_id}")

    def account_profile(self, account_id: str) -> Any:
        """实例资料

        读这个实例自己的昵称、头像、地区等资料。

        GET /v1/accounts/{account_id}/profile
        """
        return self.call("GET", f"/accounts/{account_id}/profile")

    def account_update_profile(self, account_id: str, *, city: Optional[str] = None, country: Optional[str] = None, nickname: Optional[str] = None, province: Optional[str] = None, sex: Optional[str] = None, signature: Optional[str] = None) -> Any:
        """修改个人资料

        改昵称、签名、性别与地区。字段留空就是清空该项，请把要保留的一起传。

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

    def account_set_alias(self, account_id: str, *, alias: str) -> Any:
        """设置微信号

        设置可被搜索的微信号。微信只允许设置一次，之后会拒绝。

        PUT /v1/accounts/{account_id}/profile/alias

        参数：
          alias            必填  要设置的微信号
        """
        return self.call("PUT", f"/accounts/{account_id}/profile/alias",
            body={"alias": alias},
        )

    def account_set_avatar(self, account_id: str, *, url: str) -> Any:
        """修改头像

        换头像。

        PUT /v1/accounts/{account_id}/profile/avatar

        参数：
          url              必填  公网可下载的图片地址
        """
        return self.call("PUT", f"/accounts/{account_id}/profile/avatar",
            body={"url": url},
        )

    def account_qrcode_self(self, account_id: str) -> Any:
        """我的二维码

        取这个实例自己的名片二维码，返回 data URL，可直接放进 img。

        GET /v1/accounts/{account_id}/profile/qrcode
        """
        return self.call("GET", f"/accounts/{account_id}/profile/qrcode")

    def account_privacy(self, account_id: str, *, enabled: bool, option: str) -> Any:
        """隐私设置

        开关一项隐私设置。

        PUT /v1/accounts/{account_id}/privacy

        参数：
          enabled          必填  开或关
          option           必填  need_confirm_to_add 加我需验证；findable_by_phone 手机号可搜；findable_by_alias 微信号可搜；recommend_contacts 向我推荐通讯录好友；strangers_see_ten 陌生人看十条朋友圈；visible_days 朋友圈仅展…（need_confirm_to_add / findable_by_phone / findable_by_alias / recommend_contacts / strangers_see_ten / visible_days）
        """
        return self.call("PUT", f"/accounts/{account_id}/privacy",
            body={"option": option, "enabled": enabled},
        )

    def account_devices(self, account_id: str) -> Any:
        """已登录设备

        列出这个微信号登录过的设备，本平台也在其中。

        GET /v1/accounts/{account_id}/devices
        """
        return self.call("GET", f"/accounts/{account_id}/devices")

    def account_device_signout(self, account_id: str, device_id: str) -> Any:
        """下线某个设备

        把某个已登录设备踢下线。注意别把本平台自己踢了。

        DELETE /v1/accounts/{account_id}/devices/{device_id}
        """
        return self.call("DELETE", f"/accounts/{account_id}/devices/{device_id}")

    def account_webhook(self, account_id: str, *, url: str, events: Optional[list] = None, secret: Optional[str] = None) -> Any:
        """设置 Webhook

        设置该实例事件的推送地址。每次投递都带签名，用 secret 校验。

        PUT /v1/accounts/{account_id}/webhook

        参数：
          url              必填  接收事件的地址
          events           可选  只推这些类型，留空推全部
          secret           可选  签名密钥，留空则保持不变
        """
        return self.call("PUT", f"/accounts/{account_id}/webhook",
            body={"url": url, "secret": secret, "events": events},
        )


    # --- 联系人 --------------------------------------------------------------

    def contact_ids(self, account_id: str, *, cursor: Optional[str] = None) -> Any:
        """通讯录标识

        列出通讯录里都有谁，只给标识：好友的 wxid、群的 @chatroom、公众号的 gh_ 开头，一个不筛。要资料再用「联系人详情」按需取——一千个人里你可能只关心十个。直接向微信取，实例要在线；一页多大由微信定，翻页把 next_cursor 原样带回来，为空表示到底。

        GET /v1/accounts/{account_id}/contacts

        参数：
          cursor           可选  上一页返回的 next_cursor，首页留空
        """
        return self.call("GET", f"/accounts/{account_id}/contacts",
            query={"cursor": cursor},
        )

    def contact_batch(self, account_id: str, *, wxids: list) -> Any:
        """批量取详情

        按 wxid 批量取联系人资料。与「联系人详情」走的是微信的两条不同路径，字段相同，这条更适合一次问很多人。

        POST /v1/accounts/{account_id}/contacts/batch

        参数：
          wxids            必填  要查的 wxid 列表
        """
        return self.call("POST", f"/accounts/{account_id}/contacts/batch",
            body={"wxids": wxids},
        )

    def contact_detail(self, account_id: str, *, wxids: list) -> Any:
        """联系人详情

        读联系人的完整资料：昵称、备注、微信号、头像、性别、地区、签名。

        POST /v1/accounts/{account_id}/contacts/detail

        参数：
          wxids            必填  要查的 wxid，一次最多 50 个
        """
        return self.call("POST", f"/accounts/{account_id}/contacts/detail",
            body={"wxids": wxids},
        )

    def contact_check(self, account_id: str, *, wxids: list) -> Any:
        """检测好友关系

        查这些人是否还是好友。注意：微信对这个操作盯得很紧，查得多或查得频繁会导致实例被限制，一次最多 20 个，请按需使用。

        POST /v1/accounts/{account_id}/contacts/check

        参数：
          wxids            必填  要检测的 wxid，一次最多 20 个
        """
        return self.call("POST", f"/accounts/{account_id}/contacts/check",
            body={"wxids": wxids},
        )

    def contact_external(self, account_id: str) -> Any:
        """企微联系人

        读企业微信那边的外部联系人。这些人不在普通通讯录里，「通讯录标识」拉不到他们。读的是平台存下来的那一份，先调一次同步。

        GET /v1/accounts/{account_id}/contacts/external
        """
        return self.call("GET", f"/accounts/{account_id}/contacts/external")

    def contact_external_sync(self, account_id: str) -> Any:
        """同步企微联系人

        去微信那边重新拉一遍企微外部联系人并存下来，返回拉到多少个。没有头像的会逐个补拉，人多时会慢一些，不建议频繁调用。

        POST /v1/accounts/{account_id}/contacts/external/sync
        """
        return self.call("POST", f"/accounts/{account_id}/contacts/external/sync")

    def contact_search(self, account_id: str, *, keyword: str) -> Any:
        """搜索用户

        按微信号或手机号搜人，返回一个可用于加好友的 contact_token。

        POST /v1/accounts/{account_id}/contacts/search

        参数：
          keyword          必填  微信号或手机号
        """
        return self.call("POST", f"/accounts/{account_id}/contacts/search",
            body={"keyword": keyword},
        )

    def contact_add(self, account_id: str, *, contact_token: str, greeting: Optional[str] = None, scene: Optional[str] = None) -> Any:
        """添加好友

        用搜索得到的 contact_token 发起好友申请。**这一条慢**：微信自己要 5～20 秒才回，实测平均 9 秒、最慢 16 秒，客户端超时请留够 30 秒。超时了不要直接重发——请求多半已经送出去了，要重试就带上 Idempotency-Key。加得太频繁会被微信限制。

        POST /v1/accounts/{account_id}/contacts/add

        参数：
          contact_token    必填  搜索结果里的 contact_token
          greeting         可选  打招呼的话
          scene            可选  申请来源，留空用默认值
        """
        return self.call("POST", f"/accounts/{account_id}/contacts/add",
            body={"contact_token": contact_token, "greeting": greeting, "scene": scene},
        )

    def contact_accept(self, account_id: str, *, friend_request_token: str) -> Any:
        """通过好友申请

        同意别人的好友申请，用事件里给出的 friend_request_token。

        POST /v1/accounts/{account_id}/contacts/accept

        参数：
          friend_request_token 必填  好友申请事件里的 token
        """
        return self.call("POST", f"/accounts/{account_id}/contacts/accept",
            body={"friend_request_token": friend_request_token},
        )

    def contact_remark(self, account_id: str, wxid: str, *, remark: str) -> Any:
        """设置备注

        给一个联系人改备注名。

        PUT /v1/accounts/{account_id}/contacts/{wxid}/remark

        参数：
          remark           必填  新的备注名
        """
        return self.call("PUT", f"/accounts/{account_id}/contacts/{wxid}/remark",
            body={"remark": remark},
        )

    def contact_delete(self, account_id: str, wxid: str) -> Any:
        """删除好友

        把人从通讯录里删掉。对方不会收到通知，但从此发不进来；要恢复得重新加。

        DELETE /v1/accounts/{account_id}/contacts/{wxid}
        """
        return self.call("DELETE", f"/accounts/{account_id}/contacts/{wxid}")

    def label_list(self, account_id: str) -> Any:
        """标签列表

        列出这个实例的联系人标签。标签只有自己看得见。

        GET /v1/accounts/{account_id}/labels
        """
        return self.call("GET", f"/accounts/{account_id}/labels")

    def label_add(self, account_id: str, *, name: str) -> Any:
        """新建标签

        新建一个联系人标签，返回它的 label_id。

        POST /v1/accounts/{account_id}/labels

        参数：
          name             必填  标签名
        """
        return self.call("POST", f"/accounts/{account_id}/labels",
            body={"name": name},
        )

    def label_rename(self, account_id: str, label_id: str, *, name: str) -> Any:
        """改标签名

        改一个标签的名字。

        PUT /v1/accounts/{account_id}/labels/{label_id}

        参数：
          name             必填  新的标签名
        """
        return self.call("PUT", f"/accounts/{account_id}/labels/{label_id}",
            body={"name": name},
        )

    def label_delete(self, account_id: str, label_id: str) -> Any:
        """删除标签

        删掉一个标签。带这个标签的联系人不受影响，只是不再带它。

        DELETE /v1/accounts/{account_id}/labels/{label_id}
        """
        return self.call("DELETE", f"/accounts/{account_id}/labels/{label_id}")

    def label_members(self, account_id: str, label_id: str, *, wxids: list) -> Any:
        """设置标签成员

        设置哪些联系人带这个标签。是覆盖不是追加：没列进来的会被摘掉。

        PUT /v1/accounts/{account_id}/labels/{label_id}/members

        参数：
          wxids            必填  带这个标签的 wxid 全集
        """
        return self.call("PUT", f"/accounts/{account_id}/labels/{label_id}/members",
            body={"wxids": wxids},
        )


    # --- 群 ------------------------------------------------------------------

    def group_create(self, account_id: str, *, members: list) -> Any:
        """创建群聊

        拉几个好友建一个群。至少两个成员。

        POST /v1/accounts/{account_id}/groups

        参数：
          members          必填  初始成员的 wxid
        """
        return self.call("POST", f"/accounts/{account_id}/groups",
            body={"members": members},
        )

    def group_get(self, account_id: str, group_id: str) -> Any:
        """群详情

        读群的名称、公告、群主等资料。

        GET /v1/accounts/{account_id}/groups/{group_id}
        """
        return self.call("GET", f"/accounts/{account_id}/groups/{group_id}")

    def group_members(self, account_id: str, group_id: str) -> Any:
        """群成员

        列出群成员。

        GET /v1/accounts/{account_id}/groups/{group_id}/members
        """
        return self.call("GET", f"/accounts/{account_id}/groups/{group_id}/members")

    def group_member_detail(self, account_id: str, group_id: str, *, members: list) -> Any:
        """群成员详情

        读指定几个群成员的完整资料，比群成员列表更全。

        POST /v1/accounts/{account_id}/groups/{group_id}/members/detail

        参数：
          members          必填  要查的 wxid
        """
        return self.call("POST", f"/accounts/{account_id}/groups/{group_id}/members/detail",
            body={"members": members},
        )

    def group_invite(self, account_id: str, group_id: str, *, members: list, reason: Optional[str] = None) -> Any:
        """邀请入群

        邀请好友进群。群人数多时微信会改为发邀请链接。

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

        把人移出群。只有群主和管理员能做。

        POST /v1/accounts/{account_id}/groups/{group_id}/members/remove

        参数：
          members          必填  要移出的 wxid
        """
        return self.call("POST", f"/accounts/{account_id}/groups/{group_id}/members/remove",
            body={"members": members},
        )

    def group_admins(self, account_id: str, group_id: str, *, action: str, members: list) -> Any:
        """群管理员

        设置或取消群管理员，也可以转让群主。只有群主能做。

        POST /v1/accounts/{account_id}/groups/{group_id}/admins

        参数：
          action           必填  grant 设为管理员，revoke 取消，transfer 转让群主（只能一个人）（grant / revoke / transfer）
          members          必填  目标 wxid
        """
        return self.call("POST", f"/accounts/{account_id}/groups/{group_id}/admins",
            body={"action": action, "members": members},
        )

    def group_rename(self, account_id: str, group_id: str, *, name: str) -> Any:
        """修改群名

        改群名称。需要有权限改。

        PUT /v1/accounts/{account_id}/groups/{group_id}/name

        参数：
          name             必填  新的群名称
        """
        return self.call("PUT", f"/accounts/{account_id}/groups/{group_id}/name",
            body={"name": name},
        )

    def group_announcement(self, account_id: str, group_id: str, *, content: str) -> Any:
        """设置群公告

        改群公告。只有群主和管理员能做，会给全群发一条提示。

        PUT /v1/accounts/{account_id}/groups/{group_id}/announcement

        参数：
          content          必填  公告正文，留空表示清除
        """
        return self.call("PUT", f"/accounts/{account_id}/groups/{group_id}/announcement",
            body={"content": content},
        )

    def group_remark(self, account_id: str, group_id: str, *, remark: str) -> Any:
        """设置群备注

        给群起一个只有自己看得到的名字。

        PUT /v1/accounts/{account_id}/groups/{group_id}/remark

        参数：
          remark           必填  备注名，留空表示清除
        """
        return self.call("PUT", f"/accounts/{account_id}/groups/{group_id}/remark",
            body={"remark": remark},
        )

    def group_nickname(self, account_id: str, group_id: str, *, nickname: str) -> Any:
        """设置我的群昵称

        改自己在这个群里显示的名字。

        PUT /v1/accounts/{account_id}/groups/{group_id}/nickname

        参数：
          nickname         必填  群内昵称
        """
        return self.call("PUT", f"/accounts/{account_id}/groups/{group_id}/nickname",
            body={"nickname": nickname},
        )

    def group_kept(self, account_id: str, group_id: str, *, enabled: bool) -> Any:
        """保存到通讯录

        把群保存到通讯录，或取消保存。不保存的群在会话删除后就找不回来了。

        PUT /v1/accounts/{account_id}/groups/{group_id}/kept

        参数：
          enabled          必填  true 保存，false 取消
        """
        return self.call("PUT", f"/accounts/{account_id}/groups/{group_id}/kept",
            body={"enabled": enabled},
        )

    def group_qrcode(self, account_id: str, group_id: str) -> Any:
        """群二维码

        取群的邀请二维码，返回 data URL，可直接放进 img。

        GET /v1/accounts/{account_id}/groups/{group_id}/qrcode
        """
        return self.call("GET", f"/accounts/{account_id}/groups/{group_id}/qrcode")

    def group_join(self, account_id: str, *, url: str) -> Any:
        """通过链接进群

        用收到的群邀请链接进群。

        POST /v1/accounts/{account_id}/groups/join

        参数：
          url              必填  邀请链接
        """
        return self.call("POST", f"/accounts/{account_id}/groups/join",
            body={"url": url},
        )

    def group_preview(self, account_id: str, *, url: str) -> Any:
        """查看群邀请

        拿一个群邀请链接先看看是什么群，不进群。usable 为 false 时 notice 说明原因，比如链接已过期。

        POST /v1/accounts/{account_id}/groups/preview

        参数：
          url              必填  邀请链接
        """
        return self.call("POST", f"/accounts/{account_id}/groups/preview",
            body={"url": url},
        )

    def group_approve(self, account_id: str, group_id: str, *, inviter: str, members: list, message_id: str, ticket: str) -> Any:
        """同意入群邀请

        群成员邀请了人进群，群主在这里放行。四个参数都来自那条邀请事件。

        POST /v1/accounts/{account_id}/groups/{group_id}/approve

        参数：
          inviter          必填  邀请人的 wxid
          members          必填  被邀请人的 wxid
          message_id       必填  邀请事件里的消息 ID
          ticket           必填  邀请事件里的凭据
        """
        return self.call("POST", f"/accounts/{account_id}/groups/{group_id}/approve",
            body={"inviter": inviter, "message_id": message_id, "ticket": ticket, "members": members},
        )

    def chat_muted(self, account_id: str, chat_id: str, *, enabled: bool) -> Any:
        """消息免打扰

        对一个群或一个好友开关消息免打扰。

        PUT /v1/accounts/{account_id}/chats/{chat_id}/muted

        参数：
          enabled          必填  true 免打扰，false 恢复提醒
        """
        return self.call("PUT", f"/accounts/{account_id}/chats/{chat_id}/muted",
            body={"enabled": enabled},
        )

    def chat_pinned(self, account_id: str, chat_id: str, *, enabled: bool) -> Any:
        """聊天置顶

        把一个群或一个好友的会话置顶，或取消置顶。

        PUT /v1/accounts/{account_id}/chats/{chat_id}/pinned

        参数：
          enabled          必填  true 置顶，false 取消
        """
        return self.call("PUT", f"/accounts/{account_id}/chats/{chat_id}/pinned",
            body={"enabled": enabled},
        )


    # --- 消息 ----------------------------------------------------------------

    def message_text(self, account_id: str, *, content: str, to: str, mentions: Optional[list] = None, to_list: Optional[list] = None) -> Any:
        """发文字

        发一条文字消息。群里可以 @人。

        POST /v1/accounts/{account_id}/messages/text

        参数：
          content          必填  消息正文
          to               必填  接收者：wxid、群 ID，或 filehelper（自己的文件传输助手）
          mentions         可选  要 @ 的 wxid，只在群里有意义
          to_list          可选  一次发给多个接收者，与 to 二选一
        """
        return self.call("POST", f"/accounts/{account_id}/messages/text",
            body={"to": to, "to_list": to_list, "content": content, "mentions": mentions},
        )

    def message_image(self, account_id: str, *, to: str, media_id: Optional[str] = None, url: Optional[str] = None, use_cache: Optional[bool] = None) -> Any:
        """发图片

        发一张图片。url 与 media_id 二选一，media_id 可以复用平台已存的文件。

        POST /v1/accounts/{account_id}/messages/image

        参数：
          to               必填  接收者：wxid、群 ID，或 filehelper（自己的文件传输助手）
          media_id         可选  平台里已有的媒体 ID
          url              可选  公网可下载的地址
          use_cache        可选  同一个 url 之前发过就直接转发，不重新上传。默认 true；地址没变但内容换了才需要传 false
        """
        return self.call("POST", f"/accounts/{account_id}/messages/image",
            body={"to": to, "url": url, "media_id": media_id, "use_cache": use_cache},
        )

    def message_video(self, account_id: str, *, to: str, duration: Optional[int] = None, height: Optional[int] = None, media_id: Optional[str] = None, thumbnail_url: Optional[str] = None, url: Optional[str] = None, use_cache: Optional[bool] = None, width: Optional[int] = None) -> Any:
        """发视频

        发一段视频。不填时长的话由平台估算，有些客户端会显示得不好看。

        POST /v1/accounts/{account_id}/messages/video

        参数：
          to               必填  接收者：wxid、群 ID，或 filehelper（自己的文件传输助手）
          duration         可选  时长（秒）
          height           可选  画面高，不传则动态里不带尺寸
          media_id         可选  平台里已有的媒体 ID
          thumbnail_url    可选  封面图地址，公网可下载的一张图片
          url              可选  公网可下载的地址
          use_cache        可选  同一个 url 之前发过就直接转发，不重新上传。默认 true；地址没变但内容换了才需要传 false
          width            可选  画面宽，不传则动态里不带尺寸
        """
        return self.call("POST", f"/accounts/{account_id}/messages/video",
            body={"to": to, "url": url, "media_id": media_id, "use_cache": use_cache, "duration": duration, "width": width, "height": height, "thumbnail_url": thumbnail_url},
        )

    def message_voice(self, account_id: str, *, to: str, url: str, seconds: Optional[int] = None) -> Any:
        """发语音

        发一条语音。seconds 是时长，显示在气泡上。

        POST /v1/accounts/{account_id}/messages/voice

        参数：
          to               必填  接收者：wxid、群 ID，或 filehelper（自己的文件传输助手）
          url              必填  公网可下载的音频地址
          seconds          可选  时长（秒）
        """
        return self.call("POST", f"/accounts/{account_id}/messages/voice",
            body={"to": to, "url": url, "seconds": seconds},
        )

    def message_file(self, account_id: str, *, to: str, filename: Optional[str] = None, media_id: Optional[str] = None, url: Optional[str] = None, use_cache: Optional[bool] = None) -> Any:
        """发文件

        发一个文件。

        POST /v1/accounts/{account_id}/messages/file

        参数：
          to               必填  接收者：wxid、群 ID，或 filehelper（自己的文件传输助手）
          filename         可选  对方看到的文件名
          media_id         可选  平台里已有的媒体 ID
          url              可选  公网可下载的地址
          use_cache        可选  同一个 url 之前发过就直接转发，不重新上传。默认 true；地址没变但内容换了才需要传 false
        """
        return self.call("POST", f"/accounts/{account_id}/messages/file",
            body={"to": to, "url": url, "media_id": media_id, "use_cache": use_cache, "filename": filename},
        )

    def message_sticker(self, account_id: str, *, checksum: str, length: int, to: str) -> Any:
        """发动图表情

        转发一个动图表情。表情是引用不是上传：checksum 与 length 来自收到的那条表情消息。

        POST /v1/accounts/{account_id}/messages/sticker

        参数：
          checksum         必填  表情的校验值，来自收到的表情消息
          length           必填  表情的字节数，来自同一条消息
          to               必填  接收者：wxid、群 ID，或 filehelper（自己的文件传输助手）
        """
        return self.call("POST", f"/accounts/{account_id}/messages/sticker",
            body={"to": to, "checksum": checksum, "length": length},
        )

    def message_link(self, account_id: str, *, title: str, to: str, url: str, description: Optional[str] = None, source_name: Optional[str] = None, thumb_url: Optional[str] = None) -> Any:
        """发链接卡片

        发一张可点击的链接卡片。

        POST /v1/accounts/{account_id}/messages/link

        参数：
          title            必填  卡片标题
          to               必填  接收者：wxid、群 ID，或 filehelper（自己的文件传输助手）
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

        发一张小程序卡片。需要小程序自己的标识。

        POST /v1/accounts/{account_id}/messages/miniapp

        参数：
          app_id           必填  小程序的公开标识
          title            必填  卡片标题
          to               必填  接收者：wxid、群 ID，或 filehelper（自己的文件传输助手）
          username         必填  小程序的原始 ID
          description      可选  卡片摘要
          path             可选  打开的页面路径
          source_name      可选  来源名称
          thumb_url        可选  封面图地址
        """
        return self.call("POST", f"/accounts/{account_id}/messages/miniapp",
            body={"to": to, "app_id": app_id, "username": username, "title": title, "description": description, "path": path, "thumb_url": thumb_url, "source_name": source_name},
        )

    def message_forward(self, account_id: str, *, message_id: str, to: str) -> Any:
        """转发消息

        把收到过的一条消息原样转给别人。

        POST /v1/accounts/{account_id}/messages/forward

        参数：
          message_id       必填  要转发的消息 ID
          to               必填  接收者：wxid、群 ID，或 filehelper（自己的文件传输助手）
        """
        return self.call("POST", f"/accounts/{account_id}/messages/forward",
            body={"to": to, "message_id": message_id},
        )

    def message_recall(self, account_id: str, message_id: str) -> Any:
        """撤回消息

        撤回自己发出的一条消息。微信只允许发出后约两分钟内撤回，超时会被拒绝。

        POST /v1/accounts/{account_id}/messages/{message_id}/recall
        """
        return self.call("POST", f"/accounts/{account_id}/messages/{message_id}/recall")

    def message_history(self, account_id: str, *, cursor: Optional[str] = None, limit: Optional[int] = None, peer: Optional[str] = None) -> Any:
        """消息记录

        读平台保存的消息记录，可按会话筛选。

        GET /v1/accounts/{account_id}/messages

        参数：
          cursor           可选  上一页返回的 next_cursor，首页留空
          limit            可选  每页条数，最多 200，超过按 200 处理
          peer             可选  只看与某个 wxid 或群的会话
        """
        return self.call("GET", f"/accounts/{account_id}/messages",
            query={"peer": peer, "cursor": cursor, "limit": limit},
        )

    def message_sync(self, account_id: str, *, cursor: Optional[str] = None) -> Any:
        """同步消息

        主动拉取这个实例收到的消息，内容和 Webhook 推的完全一样。没配 Webhook、Webhook 断过、或者服务重启过，用它把这段时间的消息补回来。cursor 留空从最早还留着的消息开始（大约一天），之后每次带上一次返回的 next_cursor；has_more 为 true 说明还没拉完，立刻再调一次。没有新消息时 next_cursor 原样返回，游标不动。拉到的消息不入库、不触发 Webhook，重复拉不会有副作用。

        POST /v1/accounts/{account_id}/messages/sync

        参数：
          cursor           可选  上一次返回的 next_cursor，第一次留空
        """
        return self.call("POST", f"/accounts/{account_id}/messages/sync",
            body={"cursor": cursor},
        )

    def message_get(self, account_id: str, message_id: str) -> Any:
        """消息详情

        读一条消息。

        GET /v1/accounts/{account_id}/messages/{message_id}
        """
        return self.call("GET", f"/accounts/{account_id}/messages/{message_id}")

    def favorite_list(self, account_id: str, *, cursor: Optional[str] = None) -> Any:
        """收藏列表

        列出这个实例收藏的内容。cursor 留空从头读，返回的 next_cursor 为空表示到底。

        GET /v1/accounts/{account_id}/favorites

        参数：
          cursor           可选  上一页返回的 next_cursor，首页留空
        """
        return self.call("GET", f"/accounts/{account_id}/favorites",
            query={"cursor": cursor},
        )

    def favorite_get(self, account_id: str, fav_id: str) -> Any:
        """收藏详情

        读一条收藏的完整内容。内容是微信自己的 XML，不同类型结构不同，原样返回。

        GET /v1/accounts/{account_id}/favorites/{fav_id}
        """
        return self.call("GET", f"/accounts/{account_id}/favorites/{fav_id}")

    def favorite_delete(self, account_id: str, fav_id: str) -> Any:
        """删除收藏

        删掉一条收藏。

        DELETE /v1/accounts/{account_id}/favorites/{fav_id}
        """
        return self.call("DELETE", f"/accounts/{account_id}/favorites/{fav_id}")


    # --- 媒体 ----------------------------------------------------------------

    def media_from_message(self, account_id: str, *, message_id: str) -> Any:
        """下载消息附件

        取一条消息里的图片、视频、文件或语音，返回一个限时下载地址。

        POST /v1/accounts/{account_id}/media/download

        参数：
          message_id       必填  带附件的消息 ID
        """
        return self.call("POST", f"/accounts/{account_id}/media/download",
            body={"message_id": message_id},
        )

    def media_cached(self, account_id: str, *, kind: str, url: str) -> Any:
        """查文件是否已缓存

        问一个地址平台是否已经发过。发过就能直接转发，不用重新上传，也不算流量 —— 在你把文件准备好挂到公网之前先问一句，省的就是这一趟。

        POST /v1/accounts/{account_id}/media/cached

        参数：
          kind             必填  image、video 或 file
          url              必填  要发的那个地址，和发送时填的一模一样才算命中
        """
        return self.call("POST", f"/accounts/{account_id}/media/cached",
            body={"url": url, "kind": kind},
        )

    def media_get(self, account_id: str, media_id: str) -> Any:
        """重新取下载地址

        为已经下载过的文件换一个新的限时地址。

        GET /v1/accounts/{account_id}/media/{media_id}
        """
        return self.call("GET", f"/accounts/{account_id}/media/{media_id}")

    def media_moment(self, account_id: str, moment_id: str, *, index: Optional[int] = None) -> Any:
        """下载动态媒体

        取一条朋友圈动态里的第 N 张图，或它的视频。

        POST /v1/accounts/{account_id}/moments/{moment_id}/media/download

        参数：
          index            可选  第几张图，从 0 开始；视频动态忽略这个值
        """
        return self.call("POST", f"/accounts/{account_id}/moments/{moment_id}/media/download",
            body={"index": index},
        )


    # --- 朋友圈 --------------------------------------------------------------

    def moment_timeline(self, account_id: str, *, cursor: Optional[str] = None) -> Any:
        """我的朋友圈

        读自己看到的朋友圈时间线。

        GET /v1/accounts/{account_id}/moments

        参数：
          cursor           可选  上一页返回的 next_cursor
        """
        return self.call("GET", f"/accounts/{account_id}/moments",
            query={"cursor": cursor},
        )

    def moment_get(self, account_id: str, moment_id: str) -> Any:
        """朋友圈详情

        读一条朋友圈。列表会截断点赞与评论，这里是完整的。

        GET /v1/accounts/{account_id}/moments/{moment_id}
        """
        return self.call("GET", f"/accounts/{account_id}/moments/{moment_id}")

    def moment_user(self, account_id: str, wxid: str, *, cursor: Optional[str] = None) -> Any:
        """某人的朋友圈

        读某个联系人的朋友圈主页。

        GET /v1/accounts/{account_id}/moments/user/{wxid}

        参数：
          cursor           可选  上一页返回的 next_cursor
        """
        return self.call("GET", f"/accounts/{account_id}/moments/user/{wxid}",
            query={"cursor": cursor},
        )

    def moment_post_text(self, account_id: str, *, content: str, mentions: Optional[list] = None, visibility: Optional[dict] = None) -> Any:
        """发文字动态

        发一条纯文字朋友圈。

        POST /v1/accounts/{account_id}/moments/text

        参数：
          content          必填  正文
          mentions         可选  要 @ 的 wxid
          visibility       可选  可见范围：mode 为 public / private / allow / deny，后两种要给 wxids 或 tag_ids
        """
        return self.call("POST", f"/accounts/{account_id}/moments/text",
            body={"content": content, "mentions": mentions, "visibility": visibility},
        )

    def moment_post_images(self, account_id: str, *, images: list, content: Optional[str] = None, visibility: Optional[dict] = None) -> Any:
        """发图片动态

        发一条带图的朋友圈。

        POST /v1/accounts/{account_id}/moments/images

        参数：
          images           必填  图片列表
          content          可选  正文
          visibility       可选  可见范围：mode 为 public / private / allow / deny，后两种要给 wxids 或 tag_ids
        """
        return self.call("POST", f"/accounts/{account_id}/moments/images",
            body={"content": content, "images": images, "visibility": visibility},
        )

    def moment_post_video(self, account_id: str, *, video: dict, content: Optional[str] = None, cover: Optional[dict] = None, duration: Optional[int] = None, visibility: Optional[dict] = None) -> Any:
        """发视频动态

        发一条视频朋友圈。

        POST /v1/accounts/{account_id}/moments/video

        参数：
          video            必填  视频，给一个公网可下载的地址
          content          可选  正文
          cover            可选  封面图
          duration         可选  时长（秒）
          visibility       可选  可见范围：mode 为 public / private / allow / deny，后两种要给 wxids 或 tag_ids
        """
        return self.call("POST", f"/accounts/{account_id}/moments/video",
            body={"content": content, "video": video, "cover": cover, "duration": duration, "visibility": visibility},
        )

    def moment_repost(self, account_id: str, *, moment_id: str, visibility: Optional[dict] = None) -> Any:
        """转发动态

        把看到的一条动态原样再发一遍。

        POST /v1/accounts/{account_id}/moments/forward

        参数：
          moment_id        必填  要转发的动态 ID
          visibility       可选  可见范围：mode 为 public / private / allow / deny，后两种要给 wxids 或 tag_ids
        """
        return self.call("POST", f"/accounts/{account_id}/moments/forward",
            body={"moment_id": moment_id, "visibility": visibility},
        )

    def moment_like(self, account_id: str, moment_id: str) -> Any:
        """点赞

        给一条动态点赞。动态要先读过一次，24 小时内有效。

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
          reply_to         可选  要回复的评论 ID，留空为一级评论
        """
        return self.call("POST", f"/accounts/{account_id}/moments/{moment_id}/comments",
            body={"content": content, "reply_to": reply_to},
        )

    def moment_delete_comment(self, account_id: str, moment_id: str, comment_id: str) -> Any:
        """删除评论

        删掉自己发的一条评论。

        DELETE /v1/accounts/{account_id}/moments/{moment_id}/comments/{comment_id}
        """
        return self.call("DELETE", f"/accounts/{account_id}/moments/{moment_id}/comments/{comment_id}")

    def moment_delete(self, account_id: str, moment_id: str) -> Any:
        """删除动态

        删掉自己发的一条朋友圈。

        DELETE /v1/accounts/{account_id}/moments/{moment_id}
        """
        return self.call("DELETE", f"/accounts/{account_id}/moments/{moment_id}")

    def moment_privacy(self, account_id: str, moment_id: str, *, private: bool) -> Any:
        """设为私密 / 公开

        把自己的一条动态设为仅自己可见，或改回公开。

        PUT /v1/accounts/{account_id}/moments/{moment_id}/privacy

        参数：
          private          必填  true 为仅自己可见
        """
        return self.call("PUT", f"/accounts/{account_id}/moments/{moment_id}/privacy",
            body={"private": private},
        )


    # --- 平台 ----------------------------------------------------------------

    def platform_me(self) -> Any:
        """当前用户

        读这个 Key 属于谁，以及实例数量。

        GET /v1/me
        """
        return self.call("GET", "/me")

    def platform_events(self, *, account_id: Optional[str] = None, cursor: Optional[str] = None, keyword: Optional[str] = None, limit: Optional[int] = None, message_type: Optional[str] = None, order: Optional[str] = None, since: Optional[str] = None, type: Optional[str] = None, until: Optional[str] = None) -> Any:
        """事件列表

        读平台记录的事件，可按实例、类型、消息类型与时间筛选。没配 Webhook 时可以轮询这里。

        GET /v1/events

        参数：
          account_id       可选  只看某个实例
          cursor           可选  上一页返回的 next_cursor，首页留空
          keyword          可选  按事件内容搜索。需要同时给时间范围，且不超过 1 小时
          limit            可选  每页条数，最多 200，超过按 200 处理
          message_type     可选  只看某种消息，如 text、image、file，可重复；非消息事件不会命中
          order            可选  oldest 从头逐条读（默认），newest 先看最近发生的（oldest / newest）
          since            可选  只看这个时间之后的，RFC3339 或 Unix 秒
          type             可选  只看某种事件，可重复
          until            可选  只看这个时间之前的，RFC3339 或 Unix 秒
        """
        return self.call("GET", "/events",
            query={"account_id": account_id, "type": type, "message_type": message_type, "since": since, "until": until, "keyword": keyword, "order": order, "cursor": cursor, "limit": limit},
        )

    def platform_stream(self, account_id: str) -> Any:
        """事件流

        以 SSE 长连接实时接收该实例的事件，内容与 Webhook 相同。

        GET /v1/accounts/{account_id}/stream
        """
        return self.call("GET", f"/accounts/{account_id}/stream")



def _clean(body: dict) -> dict:
    """没填的参数不发出去，免得把服务端的默认值覆盖掉。"""
    return {k: v for k, v in body.items() if v is not None}
