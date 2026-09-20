// Package welink 把微信的能力做成 HTTP 接口。
//
//	wx := welink.New("key_xxx", "https://你的服务地址")
//	data, err := wx.MessageText(ctx, "acc_xxx", welink.M{
//		"to":      "filehelper",
//		"content": "你好",
//	})
//
// 每个方法对应一个接口，返回的是响应里 data 字段的原始 JSON，
// 用 json.Unmarshal 解成你自己的结构体即可。
//
// 调用失败返回 *Error，上面带平台错误码和 RequestID。
//
// 本文件由接口清单生成，不要手改。
package welink

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// M 是一次调用要带的参数。
type M map[string]any

// Error 是一次失败的调用。
type Error struct {
	Code      int
	Message   string
	RequestID string
	Status    int
}

func (e *Error) Error() string { return fmt.Sprintf("[%d] %s", e.Code, e.Message) }

// VerifyWebhook 校验事件回调的签名。
//
// 必须拿原始请求体来算 —— 先反序列化再重新序列化，字段顺序和空格都会变，
// 算出来的签名就对不上了。
func VerifyWebhook(secret string, body []byte, signature string) bool {
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write(body)
	want := "sha256=" + hex.EncodeToString(mac.Sum(nil))
	return hmac.Equal([]byte(want), []byte(signature))
}

// Client 一个 API Key 一个实例。并发安全，可以长期持有。
type Client struct {
	APIKey  string
	BaseURL string
	HTTP    *http.Client
}

// New 建一个客户端。baseURL 填你拿到的服务地址。
func New(apiKey, baseURL string) *Client {
	return &Client{
		APIKey:  apiKey,
		BaseURL: strings.TrimRight(baseURL, "/"),
		HTTP:    &http.Client{Timeout: 30 * time.Second},
	}
}

// Call 直接调用一个接口。清单里还没有的新接口可以用它。
// Upload 以 multipart/form-data 上传一个文件。
//
// name 是文件名，会影响对端认出来的类型；fields 是同时要带的普通表单字段。
func (c *Client) Upload(ctx context.Context, path, name string, content []byte, fields M) (json.RawMessage, error) {
	if c.APIKey == "" {
		return nil, &Error{Message: "api key 不能为空"}
	}
	if len(content) == 0 {
		return nil, &Error{Message: "文件是空的"}
	}
	if name == "" {
		name = "file"
	}

	var buf bytes.Buffer
	form := multipart.NewWriter(&buf)
	for k, v := range fields {
		if v == nil || v == "" {
			continue
		}
		if err := form.WriteField(k, fmt.Sprint(v)); err != nil {
			return nil, &Error{Message: err.Error()}
		}
	}
	part, err := form.CreateFormFile("file", name)
	if err != nil {
		return nil, &Error{Message: err.Error()}
	}
	if _, err := part.Write(content); err != nil {
		return nil, &Error{Message: err.Error()}
	}
	if err := form.Close(); err != nil {
		return nil, &Error{Message: err.Error()}
	}

	req, err := http.NewRequestWithContext(ctx, "POST", c.BaseURL+"/v1"+path, &buf)
	if err != nil {
		return nil, &Error{Message: err.Error()}
	}
	req.Header.Set("Authorization", "Bearer "+c.APIKey)
	req.Header.Set("Accept", "application/json")
	req.Header.Set("Content-Type", form.FormDataContentType())

	client := c.HTTP
	if client == nil {
		client = http.DefaultClient
	}
	resp, err := client.Do(req)
	if err != nil {
		return nil, &Error{Message: "连不上服务：" + err.Error()}
	}
	defer resp.Body.Close()

	raw, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, &Error{Message: "读响应失败：" + err.Error(), Status: resp.StatusCode}
	}
	var envelope struct {
		Code      int             `json:"code"`
		Message   string          `json:"message"`
		Data      json.RawMessage `json:"data"`
		RequestID string          `json:"request_id"`
	}
	if err := json.Unmarshal(raw, &envelope); err != nil {
		return nil, &Error{Message: fmt.Sprintf("服务返回的不是 JSON（HTTP %d）", resp.StatusCode),
			Status: resp.StatusCode}
	}
	if envelope.Code != 0 {
		return nil, &Error{Code: envelope.Code, Message: envelope.Message,
			RequestID: envelope.RequestID, Status: resp.StatusCode}
	}
	return envelope.Data, nil
}

func (c *Client) Call(ctx context.Context, method, path string, query, body M) (json.RawMessage, error) {
	if c.APIKey == "" {
		return nil, &Error{Message: "api key 不能为空"}
	}
	if c.BaseURL == "" {
		return nil, &Error{Message: "base url 不能为空，填你拿到的服务地址"}
	}

	target := c.BaseURL + "/v1" + path
	if len(query) > 0 {
		values := url.Values{}
		for k, v := range query {
			if v == nil || v == "" {
				continue
			}
			if list, ok := v.([]string); ok {
				for _, one := range list {
					values.Add(k, one)
				}
				continue
			}
			values.Add(k, fmt.Sprint(v))
		}
		if encoded := values.Encode(); encoded != "" {
			target += "?" + encoded
		}
	}

	var payload io.Reader
	if body != nil {
		// 没填的参数不发出去，免得把服务端的默认值覆盖掉。
		clean := M{}
		for k, v := range body {
			if v != nil {
				clean[k] = v
			}
		}
		raw, err := json.Marshal(clean)
		if err != nil {
			return nil, &Error{Message: "参数没法序列化：" + err.Error()}
		}
		payload = bytes.NewReader(raw)
	}

	req, err := http.NewRequestWithContext(ctx, method, target, payload)
	if err != nil {
		return nil, &Error{Message: err.Error()}
	}
	req.Header.Set("Authorization", "Bearer "+c.APIKey)
	req.Header.Set("Accept", "application/json")
	if payload != nil {
		req.Header.Set("Content-Type", "application/json")
	}

	client := c.HTTP
	if client == nil {
		client = http.DefaultClient
	}
	resp, err := client.Do(req)
	if err != nil {
		return nil, &Error{Message: "连不上服务：" + err.Error()}
	}
	defer resp.Body.Close()

	raw, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, &Error{Message: "读响应失败：" + err.Error(), Status: resp.StatusCode}
	}

	var envelope struct {
		Code      int             `json:"code"`
		Message   string          `json:"message"`
		Data      json.RawMessage `json:"data"`
		RequestID string          `json:"request_id"`
	}
	if err := json.Unmarshal(raw, &envelope); err != nil {
		return nil, &Error{Message: fmt.Sprintf("服务返回的不是 JSON（HTTP %d）", resp.StatusCode),
			Status: resp.StatusCode}
	}
	if envelope.Code != 0 {
		return nil, &Error{Code: envelope.Code, Message: envelope.Message,
			RequestID: envelope.RequestID, Status: resp.StatusCode}
	}
	return envelope.Data, nil
}

// --- 实例 ---

// AccountCreate 开一个实例。占用一个额度，删除后归还。创建后还要扫码才会上线。
//
// POST /v1/accounts
//
// args 里可以放：
//
//	platform         必填  登录方式（ipad / mac）
//	name             可选  备注名称，只给自己看
//	proxy            可选  代理网络，留空则直连。两种填法：socks5 代理地址（socks5://user:pass@host:port），或者网络助手的网络ID（把网络助手装到一台手机上，打开即可看到，这台手机的网络就是这个实例的出口）——…
//	webhook_url      可选  该实例的事件推送地址
func (c *Client) AccountCreate(ctx context.Context, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts", nil, take(args, "platform", "name", "proxy", "webhook_url"))
}

// AccountList 列出你的全部实例与它们的状态。
//
// GET /v1/accounts
func (c *Client) AccountList(ctx context.Context) (json.RawMessage, error) {
	return c.Call(ctx, "GET", "/accounts", nil, nil)
}

// AccountGet 读一个实例。不在线时 reason 会说明原因（manual 主动退出、kicked 被别处挤下线、relogin_required 需重新扫码、recover_timeout 恢复超时、expired 授权到期）；status 为 recovering 时 recovering 里带恢复方式与放弃时间。
//
// GET /v1/accounts/{account_id}
func (c *Client) AccountGet(ctx context.Context, accountId string) (json.RawMessage, error) {
	return c.Call(ctx, "GET", "/accounts/"+url.PathEscape(accountId), nil, nil)
}

// AccountQrcode 取一张登录二维码，用手机扫。expires_in 是这张码还剩多少秒，以返回值为准，不要写死；过期了再取一张即可。带上 proxy 可以顺便换代理网络 —— 它是开会话时定下的，换了要重开会话，所以只能在扫码这一刻换；不传则沿用原来的。
//
// POST /v1/accounts/{account_id}/login/qrcode
//
// args 里可以放：
//
//	proxy            可选  改用这个代理网络：socks5 代理地址或网络助手的网络ID；传空串改为直连，不传则不动
func (c *Client) AccountQrcode(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/login/qrcode", nil, take(args, "proxy"))
}

// AccountLoginStatus 轮询扫码进度：waiting（等待扫码）、scanned（已扫码待确认）、online（已上线）、cancelled、expired。等待扫码时还带 expires_in，是这张码此刻还剩多少秒，用它校准倒计时。
//
// GET /v1/accounts/{account_id}/login/status
func (c *Client) AccountLoginStatus(ctx context.Context, accountId string) (json.RawMessage, error) {
	return c.Call(ctx, "GET", "/accounts/"+url.PathEscape(accountId)+"/login/status", nil, nil)
}

// AccountLoginCancel 放弃这次扫码。已经发出去的码会连同它背后的会话一起作废，扫了也不会让这个实例上线；实例回到未登录，重新取码即可。关闭扫码页面时调用它，别把一张还能用的码留在外面。
//
// POST /v1/accounts/{account_id}/login/cancel
func (c *Client) AccountLoginCancel(ctx context.Context, accountId string) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/login/cancel", nil, nil)
}

// AccountCaptcha 登录过程中出现安全验证时，把验证结果提交回来。
//
// POST /v1/accounts/{account_id}/login/captcha
//
// args 里可以放：
//
//	fields           必填  验证所需的字段，按提示填写
func (c *Client) AccountCaptcha(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/login/captcha", nil, take(args, "fields"))
}

// AccountReconnect 掉线后尝试不重新扫码就恢复连接。恢复不了才需要重新扫码。
//
// POST /v1/accounts/{account_id}/reconnect
func (c *Client) AccountReconnect(ctx context.Context, accountId string) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/reconnect", nil, nil)
}

// AccountLogout 让实例下线。实例与额度保留，可以再扫码上线。
//
// POST /v1/accounts/{account_id}/logout
func (c *Client) AccountLogout(ctx context.Context, accountId string) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/logout", nil, nil)
}

// AccountDelete 删除槽位并归还额度。历史消息不会立刻清除。
//
// DELETE /v1/accounts/{account_id}
func (c *Client) AccountDelete(ctx context.Context, accountId string) (json.RawMessage, error) {
	return c.Call(ctx, "DELETE", "/accounts/"+url.PathEscape(accountId), nil, nil)
}

// AccountProfile 读这个实例自己的昵称、头像、地区等资料。
//
// GET /v1/accounts/{account_id}/profile
func (c *Client) AccountProfile(ctx context.Context, accountId string) (json.RawMessage, error) {
	return c.Call(ctx, "GET", "/accounts/"+url.PathEscape(accountId)+"/profile", nil, nil)
}

// AccountUpdateProfile 改昵称、签名、性别与地区。字段留空就是清空该项，请把要保留的一起传。
//
// PUT /v1/accounts/{account_id}/profile
//
// args 里可以放：
//
//	city             可选  市
//	country          可选  国家
//	nickname         可选  昵称
//	province         可选  省
//	sex              可选  1 男，2 女，0 不显示（0 / 1 / 2）
//	signature        可选  个性签名
func (c *Client) AccountUpdateProfile(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "PUT", "/accounts/"+url.PathEscape(accountId)+"/profile", nil, take(args, "nickname", "signature", "sex", "country", "province", "city"))
}

// AccountSetAlias 设置可被搜索的微信号。微信只允许设置一次，之后会拒绝。
//
// PUT /v1/accounts/{account_id}/profile/alias
//
// args 里可以放：
//
//	alias            必填  要设置的微信号
func (c *Client) AccountSetAlias(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "PUT", "/accounts/"+url.PathEscape(accountId)+"/profile/alias", nil, take(args, "alias"))
}

// AccountSetAvatar 换头像。
//
// PUT /v1/accounts/{account_id}/profile/avatar
//
// args 里可以放：
//
//	url              必填  公网可下载的图片地址
func (c *Client) AccountSetAvatar(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "PUT", "/accounts/"+url.PathEscape(accountId)+"/profile/avatar", nil, take(args, "url"))
}

// AccountQrcodeSelf 取这个实例自己的名片二维码，返回 data URL，可直接放进 img。
//
// GET /v1/accounts/{account_id}/profile/qrcode
func (c *Client) AccountQrcodeSelf(ctx context.Context, accountId string) (json.RawMessage, error) {
	return c.Call(ctx, "GET", "/accounts/"+url.PathEscape(accountId)+"/profile/qrcode", nil, nil)
}

// AccountPrivacy 开关一项隐私设置。
//
// PUT /v1/accounts/{account_id}/privacy
//
// args 里可以放：
//
//	enabled          必填  开或关
//	option           必填  need_confirm_to_add 加我需验证；findable_by_phone 手机号可搜；findable_by_alias 微信号可搜；recommend_contacts 向我推荐通讯录好友；strang…（need_confirm_to_add / findable_by_phone / findable_by_alias / recommend_contacts / strangers_see_ten / visible_days）
func (c *Client) AccountPrivacy(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "PUT", "/accounts/"+url.PathEscape(accountId)+"/privacy", nil, take(args, "option", "enabled"))
}

// AccountDevices 列出这个微信号登录过的设备，本平台也在其中。
//
// GET /v1/accounts/{account_id}/devices
func (c *Client) AccountDevices(ctx context.Context, accountId string) (json.RawMessage, error) {
	return c.Call(ctx, "GET", "/accounts/"+url.PathEscape(accountId)+"/devices", nil, nil)
}

// AccountDeviceSignout 把某个已登录设备踢下线。注意别把本平台自己踢了。
//
// DELETE /v1/accounts/{account_id}/devices/{device_id}
func (c *Client) AccountDeviceSignout(ctx context.Context, accountId string, deviceId string) (json.RawMessage, error) {
	return c.Call(ctx, "DELETE", "/accounts/"+url.PathEscape(accountId)+"/devices/"+url.PathEscape(deviceId), nil, nil)
}

// AccountWebhook 设置该实例事件的推送地址。每次投递都带签名，用 secret 校验。
//
// PUT /v1/accounts/{account_id}/webhook
//
// args 里可以放：
//
//	url              必填  接收事件的地址
//	events           可选  只推这些类型，留空推全部
//	secret           可选  签名密钥，留空则保持不变
func (c *Client) AccountWebhook(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "PUT", "/accounts/"+url.PathEscape(accountId)+"/webhook", nil, take(args, "url", "secret", "events"))
}

// --- 联系人 ---

// ContactIds 列出通讯录里都有谁，只给标识：好友的 wxid、群的 @chatroom、公众号的 gh_ 开头，一个不筛。要资料再用「联系人详情」按需取——一千个人里你可能只关心十个。直接向微信取，实例要在线；一页多大由微信定，翻页把 next_cursor 原样带回来，为空表示到底。
//
// GET /v1/accounts/{account_id}/contacts
//
// args 里可以放：
//
//	cursor           可选  上一页返回的 next_cursor，首页留空
func (c *Client) ContactIds(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "GET", "/accounts/"+url.PathEscape(accountId)+"/contacts", take(args, "cursor"), nil)
}

// ContactBatch 按 wxid 批量取联系人资料。与「联系人详情」走的是微信的两条不同路径，字段相同，这条更适合一次问很多人。
//
// POST /v1/accounts/{account_id}/contacts/batch
//
// args 里可以放：
//
//	wxids            必填  要查的 wxid 列表
func (c *Client) ContactBatch(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/contacts/batch", nil, take(args, "wxids"))
}

// ContactDetail 读联系人的完整资料：昵称、备注、微信号、头像、性别、地区、签名。
//
// POST /v1/accounts/{account_id}/contacts/detail
//
// args 里可以放：
//
//	wxids            必填  要查的 wxid，一次最多 50 个
func (c *Client) ContactDetail(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/contacts/detail", nil, take(args, "wxids"))
}

// ContactCheck 查这些人是否还是好友。注意：微信对这个操作盯得很紧，查得多或查得频繁会导致实例被限制，一次最多 20 个，请按需使用。
//
// POST /v1/accounts/{account_id}/contacts/check
//
// args 里可以放：
//
//	wxids            必填  要检测的 wxid，一次最多 20 个
func (c *Client) ContactCheck(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/contacts/check", nil, take(args, "wxids"))
}

// ContactExternal 读企业微信那边的外部联系人。这些人不在普通通讯录里，「通讯录标识」拉不到他们。读的是平台存下来的那一份，先调一次同步。
//
// GET /v1/accounts/{account_id}/contacts/external
func (c *Client) ContactExternal(ctx context.Context, accountId string) (json.RawMessage, error) {
	return c.Call(ctx, "GET", "/accounts/"+url.PathEscape(accountId)+"/contacts/external", nil, nil)
}

// ContactExternalSync 去微信那边重新拉一遍企微外部联系人并存下来，返回拉到多少个。没有头像的会逐个补拉，人多时会慢一些，不建议频繁调用。
//
// POST /v1/accounts/{account_id}/contacts/external/sync
func (c *Client) ContactExternalSync(ctx context.Context, accountId string) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/contacts/external/sync", nil, nil)
}

// ContactSearch 按微信号或手机号搜人，返回一个可用于加好友的 contact_token。
//
// POST /v1/accounts/{account_id}/contacts/search
//
// args 里可以放：
//
//	keyword          必填  微信号或手机号
func (c *Client) ContactSearch(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/contacts/search", nil, take(args, "keyword"))
}

// ContactAdd 用搜索得到的 contact_token 发起好友申请。**这一条慢**：微信自己要 5～20 秒才回，实测平均 9 秒、最慢 16 秒，客户端超时请留够 30 秒。超时了不要直接重发——请求多半已经送出去了，要重试就带上 Idempotency-Key。加得太频繁会被微信限制。
//
// POST /v1/accounts/{account_id}/contacts/add
//
// args 里可以放：
//
//	contact_token    必填  搜索结果里的 contact_token
//	greeting         可选  打招呼的话
//	scene            可选  申请来源，留空用默认值
func (c *Client) ContactAdd(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/contacts/add", nil, take(args, "contact_token", "greeting", "scene"))
}

// ContactAccept 同意别人的好友申请，用事件里给出的 friend_request_token。
//
// POST /v1/accounts/{account_id}/contacts/accept
//
// args 里可以放：
//
//	friend_request_token 必填  好友申请事件里的 token
func (c *Client) ContactAccept(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/contacts/accept", nil, take(args, "friend_request_token"))
}

// ContactRemark 给一个联系人改备注名。
//
// PUT /v1/accounts/{account_id}/contacts/{wxid}/remark
//
// args 里可以放：
//
//	remark           必填  新的备注名
func (c *Client) ContactRemark(ctx context.Context, accountId string, wxid string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "PUT", "/accounts/"+url.PathEscape(accountId)+"/contacts/"+url.PathEscape(wxid)+"/remark", nil, take(args, "remark"))
}

// ContactDelete 把人从通讯录里删掉。对方不会收到通知，但从此发不进来；要恢复得重新加。
//
// DELETE /v1/accounts/{account_id}/contacts/{wxid}
func (c *Client) ContactDelete(ctx context.Context, accountId string, wxid string) (json.RawMessage, error) {
	return c.Call(ctx, "DELETE", "/accounts/"+url.PathEscape(accountId)+"/contacts/"+url.PathEscape(wxid), nil, nil)
}

// LabelList 列出这个实例的联系人标签。标签只有自己看得见。
//
// GET /v1/accounts/{account_id}/labels
func (c *Client) LabelList(ctx context.Context, accountId string) (json.RawMessage, error) {
	return c.Call(ctx, "GET", "/accounts/"+url.PathEscape(accountId)+"/labels", nil, nil)
}

// LabelAdd 新建一个联系人标签，返回它的 label_id。
//
// POST /v1/accounts/{account_id}/labels
//
// args 里可以放：
//
//	name             必填  标签名
func (c *Client) LabelAdd(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/labels", nil, take(args, "name"))
}

// LabelRename 改一个标签的名字。
//
// PUT /v1/accounts/{account_id}/labels/{label_id}
//
// args 里可以放：
//
//	name             必填  新的标签名
func (c *Client) LabelRename(ctx context.Context, accountId string, labelId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "PUT", "/accounts/"+url.PathEscape(accountId)+"/labels/"+url.PathEscape(labelId), nil, take(args, "name"))
}

// LabelDelete 删掉一个标签。带这个标签的联系人不受影响，只是不再带它。
//
// DELETE /v1/accounts/{account_id}/labels/{label_id}
func (c *Client) LabelDelete(ctx context.Context, accountId string, labelId string) (json.RawMessage, error) {
	return c.Call(ctx, "DELETE", "/accounts/"+url.PathEscape(accountId)+"/labels/"+url.PathEscape(labelId), nil, nil)
}

// LabelMembers 设置哪些联系人带这个标签。是覆盖不是追加：没列进来的会被摘掉。
//
// PUT /v1/accounts/{account_id}/labels/{label_id}/members
//
// args 里可以放：
//
//	wxids            必填  带这个标签的 wxid 全集
func (c *Client) LabelMembers(ctx context.Context, accountId string, labelId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "PUT", "/accounts/"+url.PathEscape(accountId)+"/labels/"+url.PathEscape(labelId)+"/members", nil, take(args, "wxids"))
}

// --- 群 ---

// GroupCreate 拉几个好友建一个群。至少两个成员。
//
// POST /v1/accounts/{account_id}/groups
//
// args 里可以放：
//
//	members          必填  初始成员的 wxid
func (c *Client) GroupCreate(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/groups", nil, take(args, "members"))
}

// GroupGet 读群的名称、公告、群主等资料。
//
// GET /v1/accounts/{account_id}/groups/{group_id}
func (c *Client) GroupGet(ctx context.Context, accountId string, groupId string) (json.RawMessage, error) {
	return c.Call(ctx, "GET", "/accounts/"+url.PathEscape(accountId)+"/groups/"+url.PathEscape(groupId), nil, nil)
}

// GroupMembers 列出群成员。
//
// GET /v1/accounts/{account_id}/groups/{group_id}/members
func (c *Client) GroupMembers(ctx context.Context, accountId string, groupId string) (json.RawMessage, error) {
	return c.Call(ctx, "GET", "/accounts/"+url.PathEscape(accountId)+"/groups/"+url.PathEscape(groupId)+"/members", nil, nil)
}

// GroupMemberDetail 读指定几个群成员的完整资料，比群成员列表更全。
//
// POST /v1/accounts/{account_id}/groups/{group_id}/members/detail
//
// args 里可以放：
//
//	members          必填  要查的 wxid
func (c *Client) GroupMemberDetail(ctx context.Context, accountId string, groupId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/groups/"+url.PathEscape(groupId)+"/members/detail", nil, take(args, "members"))
}

// GroupInvite 邀请好友进群。群人数多时微信会改为发邀请链接。
//
// POST /v1/accounts/{account_id}/groups/{group_id}/invite
//
// args 里可以放：
//
//	members          必填  要邀请的 wxid
//	reason           可选  邀请说明
func (c *Client) GroupInvite(ctx context.Context, accountId string, groupId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/groups/"+url.PathEscape(groupId)+"/invite", nil, take(args, "members", "reason"))
}

// GroupRemove 把人移出群。只有群主和管理员能做。
//
// POST /v1/accounts/{account_id}/groups/{group_id}/members/remove
//
// args 里可以放：
//
//	members          必填  要移出的 wxid
func (c *Client) GroupRemove(ctx context.Context, accountId string, groupId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/groups/"+url.PathEscape(groupId)+"/members/remove", nil, take(args, "members"))
}

// GroupAdmins 设置或取消群管理员，也可以转让群主。只有群主能做。
//
// POST /v1/accounts/{account_id}/groups/{group_id}/admins
//
// args 里可以放：
//
//	action           必填  grant 设为管理员，revoke 取消，transfer 转让群主（只能一个人）（grant / revoke / transfer）
//	members          必填  目标 wxid
func (c *Client) GroupAdmins(ctx context.Context, accountId string, groupId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/groups/"+url.PathEscape(groupId)+"/admins", nil, take(args, "action", "members"))
}

// GroupRename 改群名称。需要有权限改。
//
// PUT /v1/accounts/{account_id}/groups/{group_id}/name
//
// args 里可以放：
//
//	name             必填  新的群名称
func (c *Client) GroupRename(ctx context.Context, accountId string, groupId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "PUT", "/accounts/"+url.PathEscape(accountId)+"/groups/"+url.PathEscape(groupId)+"/name", nil, take(args, "name"))
}

// GroupAnnouncement 改群公告。只有群主和管理员能做，会给全群发一条提示。
//
// PUT /v1/accounts/{account_id}/groups/{group_id}/announcement
//
// args 里可以放：
//
//	content          必填  公告正文，留空表示清除
func (c *Client) GroupAnnouncement(ctx context.Context, accountId string, groupId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "PUT", "/accounts/"+url.PathEscape(accountId)+"/groups/"+url.PathEscape(groupId)+"/announcement", nil, take(args, "content"))
}

// GroupRemark 给群起一个只有自己看得到的名字。
//
// PUT /v1/accounts/{account_id}/groups/{group_id}/remark
//
// args 里可以放：
//
//	remark           必填  备注名，留空表示清除
func (c *Client) GroupRemark(ctx context.Context, accountId string, groupId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "PUT", "/accounts/"+url.PathEscape(accountId)+"/groups/"+url.PathEscape(groupId)+"/remark", nil, take(args, "remark"))
}

// GroupNickname 改自己在这个群里显示的名字。
//
// PUT /v1/accounts/{account_id}/groups/{group_id}/nickname
//
// args 里可以放：
//
//	nickname         必填  群内昵称
func (c *Client) GroupNickname(ctx context.Context, accountId string, groupId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "PUT", "/accounts/"+url.PathEscape(accountId)+"/groups/"+url.PathEscape(groupId)+"/nickname", nil, take(args, "nickname"))
}

// GroupKept 把群保存到通讯录，或取消保存。不保存的群在会话删除后就找不回来了。
//
// PUT /v1/accounts/{account_id}/groups/{group_id}/kept
//
// args 里可以放：
//
//	enabled          必填  true 保存，false 取消
func (c *Client) GroupKept(ctx context.Context, accountId string, groupId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "PUT", "/accounts/"+url.PathEscape(accountId)+"/groups/"+url.PathEscape(groupId)+"/kept", nil, take(args, "enabled"))
}

// GroupQrcode 取群的邀请二维码，返回 data URL，可直接放进 img。
//
// GET /v1/accounts/{account_id}/groups/{group_id}/qrcode
func (c *Client) GroupQrcode(ctx context.Context, accountId string, groupId string) (json.RawMessage, error) {
	return c.Call(ctx, "GET", "/accounts/"+url.PathEscape(accountId)+"/groups/"+url.PathEscape(groupId)+"/qrcode", nil, nil)
}

// GroupJoin 用收到的群邀请链接进群。
//
// POST /v1/accounts/{account_id}/groups/join
//
// args 里可以放：
//
//	url              必填  邀请链接
func (c *Client) GroupJoin(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/groups/join", nil, take(args, "url"))
}

// GroupPreview 拿一个群邀请链接先看看是什么群，不进群。usable 为 false 时 notice 说明原因，比如链接已过期。
//
// POST /v1/accounts/{account_id}/groups/preview
//
// args 里可以放：
//
//	url              必填  邀请链接
func (c *Client) GroupPreview(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/groups/preview", nil, take(args, "url"))
}

// GroupApprove 群成员邀请了人进群，群主在这里放行。四个参数都来自那条邀请事件。
//
// POST /v1/accounts/{account_id}/groups/{group_id}/approve
//
// args 里可以放：
//
//	inviter          必填  邀请人的 wxid
//	members          必填  被邀请人的 wxid
//	message_id       必填  邀请事件里的消息 ID
//	ticket           必填  邀请事件里的凭据
func (c *Client) GroupApprove(ctx context.Context, accountId string, groupId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/groups/"+url.PathEscape(groupId)+"/approve", nil, take(args, "inviter", "message_id", "ticket", "members"))
}

// ChatMuted 对一个群或一个好友开关消息免打扰。
//
// PUT /v1/accounts/{account_id}/chats/{chat_id}/muted
//
// args 里可以放：
//
//	enabled          必填  true 免打扰，false 恢复提醒
func (c *Client) ChatMuted(ctx context.Context, accountId string, chatId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "PUT", "/accounts/"+url.PathEscape(accountId)+"/chats/"+url.PathEscape(chatId)+"/muted", nil, take(args, "enabled"))
}

// ChatPinned 把一个群或一个好友的会话置顶，或取消置顶。
//
// PUT /v1/accounts/{account_id}/chats/{chat_id}/pinned
//
// args 里可以放：
//
//	enabled          必填  true 置顶，false 取消
func (c *Client) ChatPinned(ctx context.Context, accountId string, chatId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "PUT", "/accounts/"+url.PathEscape(accountId)+"/chats/"+url.PathEscape(chatId)+"/pinned", nil, take(args, "enabled"))
}

// --- 消息 ---

// MessageText 发一条文字消息。群里可以 @人。
//
// POST /v1/accounts/{account_id}/messages/text
//
// args 里可以放：
//
//	content          必填  消息正文
//	to               必填  接收者：wxid、群 ID，或 filehelper（自己的文件传输助手）
//	mentions         可选  要 @ 的 wxid，只在群里有意义
//	to_list          可选  一次发给多个接收者，与 to 二选一
func (c *Client) MessageText(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/messages/text", nil, take(args, "to", "to_list", "content", "mentions"))
}

// MessageImage 发一张图片。url 与 media_id 二选一，media_id 可以复用平台已存的文件。
//
// POST /v1/accounts/{account_id}/messages/image
//
// args 里可以放：
//
//	to               必填  接收者：wxid、群 ID，或 filehelper（自己的文件传输助手）
//	media_id         可选  平台里已有的媒体 ID
//	url              可选  公网可下载的地址
//	use_cache        可选  同一个 url 之前发过就直接转发，不重新上传。默认 true；地址没变但内容换了才需要传 false
func (c *Client) MessageImage(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/messages/image", nil, take(args, "to", "url", "media_id", "use_cache"))
}

// MessageVideo 发一段视频。不填时长的话由平台估算，有些客户端会显示得不好看。
//
// POST /v1/accounts/{account_id}/messages/video
//
// args 里可以放：
//
//	to               必填  接收者：wxid、群 ID，或 filehelper（自己的文件传输助手）
//	duration         可选  时长（秒）
//	height           可选  画面高，不传则动态里不带尺寸
//	media_id         可选  平台里已有的媒体 ID
//	thumbnail_url    可选  封面图地址，公网可下载的一张图片
//	url              可选  公网可下载的地址
//	use_cache        可选  同一个 url 之前发过就直接转发，不重新上传。默认 true；地址没变但内容换了才需要传 false
//	width            可选  画面宽，不传则动态里不带尺寸
func (c *Client) MessageVideo(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/messages/video", nil, take(args, "to", "url", "media_id", "use_cache", "duration", "width", "height", "thumbnail_url"))
}

// MessageVoice 发一条语音。seconds 是时长，显示在气泡上。
//
// POST /v1/accounts/{account_id}/messages/voice
//
// args 里可以放：
//
//	to               必填  接收者：wxid、群 ID，或 filehelper（自己的文件传输助手）
//	url              必填  公网可下载的音频地址
//	seconds          可选  时长（秒）
func (c *Client) MessageVoice(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/messages/voice", nil, take(args, "to", "url", "seconds"))
}

// MessageFile 发一个文件。
//
// POST /v1/accounts/{account_id}/messages/file
//
// args 里可以放：
//
//	to               必填  接收者：wxid、群 ID，或 filehelper（自己的文件传输助手）
//	filename         可选  对方看到的文件名
//	media_id         可选  平台里已有的媒体 ID
//	url              可选  公网可下载的地址
//	use_cache        可选  同一个 url 之前发过就直接转发，不重新上传。默认 true；地址没变但内容换了才需要传 false
func (c *Client) MessageFile(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/messages/file", nil, take(args, "to", "url", "media_id", "use_cache", "filename"))
}

// MessageSticker 转发一个动图表情。表情是引用不是上传：checksum 与 length 来自收到的那条表情消息。
//
// POST /v1/accounts/{account_id}/messages/sticker
//
// args 里可以放：
//
//	checksum         必填  表情的校验值，来自收到的表情消息
//	length           必填  表情的字节数，来自同一条消息
//	to               必填  接收者：wxid、群 ID，或 filehelper（自己的文件传输助手）
func (c *Client) MessageSticker(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/messages/sticker", nil, take(args, "to", "checksum", "length"))
}

// MessageLink 发一张可点击的链接卡片。
//
// POST /v1/accounts/{account_id}/messages/link
//
// args 里可以放：
//
//	title            必填  卡片标题
//	to               必填  接收者：wxid、群 ID，或 filehelper（自己的文件传输助手）
//	url              必填  点击后打开的地址
//	description      可选  卡片摘要
//	source_name      可选  来源名称
//	thumb_url        可选  封面图地址
func (c *Client) MessageLink(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/messages/link", nil, take(args, "to", "title", "description", "url", "thumb_url", "source_name"))
}

// MessageMiniapp 发一张小程序卡片。需要小程序自己的标识。
//
// POST /v1/accounts/{account_id}/messages/miniapp
//
// args 里可以放：
//
//	app_id           必填  小程序的公开标识
//	title            必填  卡片标题
//	to               必填  接收者：wxid、群 ID，或 filehelper（自己的文件传输助手）
//	username         必填  小程序的原始 ID
//	description      可选  卡片摘要
//	path             可选  打开的页面路径
//	source_name      可选  来源名称
//	thumb_url        可选  封面图地址
func (c *Client) MessageMiniapp(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/messages/miniapp", nil, take(args, "to", "app_id", "username", "title", "description", "path", "thumb_url", "source_name"))
}

// MessageForward 把收到过的一条消息原样转给别人。
//
// POST /v1/accounts/{account_id}/messages/forward
//
// args 里可以放：
//
//	message_id       必填  要转发的消息 ID
//	to               必填  接收者：wxid、群 ID，或 filehelper（自己的文件传输助手）
func (c *Client) MessageForward(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/messages/forward", nil, take(args, "to", "message_id"))
}

// MessageRecall 撤回自己发出的一条消息。微信只允许发出后约两分钟内撤回，超时会被拒绝。
//
// POST /v1/accounts/{account_id}/messages/{message_id}/recall
func (c *Client) MessageRecall(ctx context.Context, accountId string, messageId string) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/messages/"+url.PathEscape(messageId)+"/recall", nil, nil)
}

// MessageHistory 读平台保存的消息记录，可按会话筛选。
//
// GET /v1/accounts/{account_id}/messages
//
// args 里可以放：
//
//	cursor           可选  上一页返回的 next_cursor，首页留空
//	limit            可选  每页条数，最多 200，超过按 200 处理
//	peer             可选  只看与某个 wxid 或群的会话
func (c *Client) MessageHistory(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "GET", "/accounts/"+url.PathEscape(accountId)+"/messages", take(args, "peer", "cursor", "limit"), nil)
}

// MessageSync 主动拉取这个实例收到的消息，内容和 Webhook 推的完全一样。没配 Webhook、Webhook 断过、或者服务重启过，用它把这段时间的消息补回来。cursor 留空从最早还留着的消息开始（大约一天），之后每次带上一次返回的 next_cursor；has_more 为 true 说明还没拉完，立刻再调一次。没有新消息时 next_cursor 原样返回，游标不动。拉到的消息不入库、不触发 Webhook，重复拉不会有副作用。
//
// POST /v1/accounts/{account_id}/messages/sync
//
// args 里可以放：
//
//	cursor           可选  上一次返回的 next_cursor，第一次留空
func (c *Client) MessageSync(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/messages/sync", nil, take(args, "cursor"))
}

// MessageGet 读一条消息。
//
// GET /v1/accounts/{account_id}/messages/{message_id}
func (c *Client) MessageGet(ctx context.Context, accountId string, messageId string) (json.RawMessage, error) {
	return c.Call(ctx, "GET", "/accounts/"+url.PathEscape(accountId)+"/messages/"+url.PathEscape(messageId), nil, nil)
}

// FavoriteList 列出这个实例收藏的内容。cursor 留空从头读，返回的 next_cursor 为空表示到底。
//
// GET /v1/accounts/{account_id}/favorites
//
// args 里可以放：
//
//	cursor           可选  上一页返回的 next_cursor，首页留空
func (c *Client) FavoriteList(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "GET", "/accounts/"+url.PathEscape(accountId)+"/favorites", take(args, "cursor"), nil)
}

// FavoriteGet 读一条收藏的完整内容。内容是微信自己的 XML，不同类型结构不同，原样返回。
//
// GET /v1/accounts/{account_id}/favorites/{fav_id}
func (c *Client) FavoriteGet(ctx context.Context, accountId string, favId string) (json.RawMessage, error) {
	return c.Call(ctx, "GET", "/accounts/"+url.PathEscape(accountId)+"/favorites/"+url.PathEscape(favId), nil, nil)
}

// FavoriteDelete 删掉一条收藏。
//
// DELETE /v1/accounts/{account_id}/favorites/{fav_id}
func (c *Client) FavoriteDelete(ctx context.Context, accountId string, favId string) (json.RawMessage, error) {
	return c.Call(ctx, "DELETE", "/accounts/"+url.PathEscape(accountId)+"/favorites/"+url.PathEscape(favId), nil, nil)
}

// --- 媒体 ---

// MediaUpload 把文件直接传上来，换一个 media_id，之后发图片、视频、语音、文件都可以只给这个 ID。适合文件在你自己机器上、没有公网地址可给的情况 —— 比如程序刚生成的一张图。用 multipart/form-data 提交，文件放在 file 字段里，最大 20 MB。第一次发送时这个文件才真正上传到微信，之后再用同一个 ID 发就不再重传了。没有发送过的上传保留 24 小时。
//
// POST /v1/accounts/{account_id}/media/upload
//
// args 里可以放：
//
//	file             必填  要上传的文件，multipart/form-data
//	kind             可选  这个文件打算当什么发，不填按类型自动判断（image / video / voice / file）
func (c *Client) MediaUpload(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	content, _ := args["file"].([]byte)
	name, _ := args["filename"].(string)
	return c.Upload(ctx, "/accounts/"+url.PathEscape(accountId)+"/media/upload", name, content, take(args, "kind"))
}

// MediaFromMessage 取一条消息里的图片、视频、文件或语音，返回一个限时下载地址。
//
// POST /v1/accounts/{account_id}/media/download
//
// args 里可以放：
//
//	message_id       必填  带附件的消息 ID
func (c *Client) MediaFromMessage(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/media/download", nil, take(args, "message_id"))
}

// MediaCached 问一个地址平台是否已经发过。发过就能直接转发，不用重新上传，也不算流量 —— 在你把文件准备好挂到公网之前先问一句，省的就是这一趟。
//
// POST /v1/accounts/{account_id}/media/cached
//
// args 里可以放：
//
//	kind             必填  image、video 或 file
//	url              必填  要发的那个地址，和发送时填的一模一样才算命中
func (c *Client) MediaCached(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/media/cached", nil, take(args, "url", "kind"))
}

// MediaGet 为已经下载过的文件换一个新的限时地址。
//
// GET /v1/accounts/{account_id}/media/{media_id}
func (c *Client) MediaGet(ctx context.Context, accountId string, mediaId string) (json.RawMessage, error) {
	return c.Call(ctx, "GET", "/accounts/"+url.PathEscape(accountId)+"/media/"+url.PathEscape(mediaId), nil, nil)
}

// MediaMoment 取一条朋友圈动态里的第 N 张图，或它的视频。
//
// POST /v1/accounts/{account_id}/moments/{moment_id}/media/download
//
// args 里可以放：
//
//	index            可选  第几张图，从 0 开始；视频动态忽略这个值
func (c *Client) MediaMoment(ctx context.Context, accountId string, momentId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/moments/"+url.PathEscape(momentId)+"/media/download", nil, take(args, "index"))
}

// --- 朋友圈 ---

// MomentTimeline 读自己看到的朋友圈时间线。
//
// GET /v1/accounts/{account_id}/moments
//
// args 里可以放：
//
//	cursor           可选  上一页返回的 next_cursor
func (c *Client) MomentTimeline(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "GET", "/accounts/"+url.PathEscape(accountId)+"/moments", take(args, "cursor"), nil)
}

// MomentGet 读一条朋友圈。列表会截断点赞与评论，这里是完整的。
//
// GET /v1/accounts/{account_id}/moments/{moment_id}
func (c *Client) MomentGet(ctx context.Context, accountId string, momentId string) (json.RawMessage, error) {
	return c.Call(ctx, "GET", "/accounts/"+url.PathEscape(accountId)+"/moments/"+url.PathEscape(momentId), nil, nil)
}

// MomentUser 读某个联系人的朋友圈主页。
//
// GET /v1/accounts/{account_id}/moments/user/{wxid}
//
// args 里可以放：
//
//	cursor           可选  上一页返回的 next_cursor
func (c *Client) MomentUser(ctx context.Context, accountId string, wxid string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "GET", "/accounts/"+url.PathEscape(accountId)+"/moments/user/"+url.PathEscape(wxid), take(args, "cursor"), nil)
}

// MomentPostText 发一条纯文字朋友圈。
//
// POST /v1/accounts/{account_id}/moments/text
//
// args 里可以放：
//
//	content          必填  正文
//	mentions         可选  要 @ 的 wxid
//	visibility       可选  可见范围：mode 为 public / private / allow / deny，后两种要给 wxids 或 tag_ids
func (c *Client) MomentPostText(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/moments/text", nil, take(args, "content", "mentions", "visibility"))
}

// MomentPostImages 发一条带图的朋友圈。
//
// POST /v1/accounts/{account_id}/moments/images
//
// args 里可以放：
//
//	images           必填  图片列表
//	content          可选  正文
//	visibility       可选  可见范围：mode 为 public / private / allow / deny，后两种要给 wxids 或 tag_ids
func (c *Client) MomentPostImages(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/moments/images", nil, take(args, "content", "images", "visibility"))
}

// MomentPostVideo 发一条视频朋友圈。
//
// POST /v1/accounts/{account_id}/moments/video
//
// args 里可以放：
//
//	video            必填  视频，给一个公网可下载的地址
//	content          可选  正文
//	cover            可选  封面图
//	duration         可选  时长（秒）
//	visibility       可选  可见范围：mode 为 public / private / allow / deny，后两种要给 wxids 或 tag_ids
func (c *Client) MomentPostVideo(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/moments/video", nil, take(args, "content", "video", "cover", "duration", "visibility"))
}

// MomentRepost 把看到的一条动态原样再发一遍。
//
// POST /v1/accounts/{account_id}/moments/forward
//
// args 里可以放：
//
//	moment_id        必填  要转发的动态 ID
//	visibility       可选  可见范围：mode 为 public / private / allow / deny，后两种要给 wxids 或 tag_ids
func (c *Client) MomentRepost(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/moments/forward", nil, take(args, "moment_id", "visibility"))
}

// MomentLike 给一条动态点赞。动态要先读过一次，24 小时内有效。
//
// POST /v1/accounts/{account_id}/moments/{moment_id}/like
func (c *Client) MomentLike(ctx context.Context, accountId string, momentId string) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/moments/"+url.PathEscape(momentId)+"/like", nil, nil)
}

// MomentUnlike 取消对一条动态的赞。
//
// DELETE /v1/accounts/{account_id}/moments/{moment_id}/like
func (c *Client) MomentUnlike(ctx context.Context, accountId string, momentId string) (json.RawMessage, error) {
	return c.Call(ctx, "DELETE", "/accounts/"+url.PathEscape(accountId)+"/moments/"+url.PathEscape(momentId)+"/like", nil, nil)
}

// MomentComment 评论一条动态，或回复别人的评论。
//
// POST /v1/accounts/{account_id}/moments/{moment_id}/comments
//
// args 里可以放：
//
//	content          必填  评论内容，最多 500 字
//	reply_to         可选  要回复的评论 ID，留空为一级评论
func (c *Client) MomentComment(ctx context.Context, accountId string, momentId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/moments/"+url.PathEscape(momentId)+"/comments", nil, take(args, "content", "reply_to"))
}

// MomentDeleteComment 删掉自己发的一条评论。
//
// DELETE /v1/accounts/{account_id}/moments/{moment_id}/comments/{comment_id}
func (c *Client) MomentDeleteComment(ctx context.Context, accountId string, momentId string, commentId string) (json.RawMessage, error) {
	return c.Call(ctx, "DELETE", "/accounts/"+url.PathEscape(accountId)+"/moments/"+url.PathEscape(momentId)+"/comments/"+url.PathEscape(commentId), nil, nil)
}

// MomentDelete 删掉自己发的一条朋友圈。
//
// DELETE /v1/accounts/{account_id}/moments/{moment_id}
func (c *Client) MomentDelete(ctx context.Context, accountId string, momentId string) (json.RawMessage, error) {
	return c.Call(ctx, "DELETE", "/accounts/"+url.PathEscape(accountId)+"/moments/"+url.PathEscape(momentId), nil, nil)
}

// MomentPrivacy 把自己的一条动态设为仅自己可见，或改回公开。
//
// PUT /v1/accounts/{account_id}/moments/{moment_id}/privacy
//
// args 里可以放：
//
//	private          必填  true 为仅自己可见
func (c *Client) MomentPrivacy(ctx context.Context, accountId string, momentId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "PUT", "/accounts/"+url.PathEscape(accountId)+"/moments/"+url.PathEscape(momentId)+"/privacy", nil, take(args, "private"))
}

// --- 平台 ---

// PlatformMe 读这个 Key 属于谁，以及实例数量。
//
// GET /v1/me
func (c *Client) PlatformMe(ctx context.Context) (json.RawMessage, error) {
	return c.Call(ctx, "GET", "/me", nil, nil)
}

// PlatformEvents 读平台记录的事件，可按实例、类型、消息类型与时间筛选。没配 Webhook 时可以轮询这里。
//
// GET /v1/events
//
// args 里可以放：
//
//	account_id       可选  只看某个实例
//	cursor           可选  上一页返回的 next_cursor，首页留空
//	keyword          可选  按事件内容搜索。需要同时给时间范围，且不超过 1 小时
//	limit            可选  每页条数，最多 200，超过按 200 处理
//	message_type     可选  只看某种消息，如 text、image、file，可重复；非消息事件不会命中
//	order            可选  oldest 从头逐条读（默认），newest 先看最近发生的（oldest / newest）
//	since            可选  只看这个时间之后的，RFC3339 或 Unix 秒
//	type             可选  只看某种事件，可重复
//	until            可选  只看这个时间之前的，RFC3339 或 Unix 秒
func (c *Client) PlatformEvents(ctx context.Context, args M) (json.RawMessage, error) {
	return c.Call(ctx, "GET", "/events", take(args, "account_id", "type", "message_type", "since", "until", "keyword", "order", "cursor", "limit"), nil)
}

// PlatformStream 以 SSE 长连接实时接收该实例的事件，内容与 Webhook 相同。
//
// GET /v1/accounts/{account_id}/stream
func (c *Client) PlatformStream(ctx context.Context, accountId string) (json.RawMessage, error) {
	return c.Call(ctx, "GET", "/accounts/"+url.PathEscape(accountId)+"/stream", nil, nil)
}

// take 挑出这个接口认识的参数，其余的忽略，免得把不相干的字段发出去。
func take(args M, keys ...string) M {
	if args == nil {
		return nil
	}
	out := M{}
	for _, k := range keys {
		if v, ok := args[k]; ok {
			out[k] = v
		}
	}
	return out
}
