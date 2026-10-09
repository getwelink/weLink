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
// 接口方法按接口清单整理；请求、验签和事件流逻辑在本文件维护。
package welink

import (
	"bufio"
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

// Client 使用一个 API Key 管理多个实例。配置完成后可以并发调用。
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
		Code      *int            `json:"code"`
		Message   string          `json:"message"`
		Data      json.RawMessage `json:"data"`
		RequestID string          `json:"request_id"`
	}
	if err := json.Unmarshal(raw, &envelope); err != nil {
		return nil, &Error{Message: fmt.Sprintf("服务返回的不是 JSON（HTTP %d）", resp.StatusCode),
			Status: resp.StatusCode}
	}
	if envelope.Code == nil {
		return nil, &Error{Message: "服务返回的不是预期的结构", Status: resp.StatusCode}
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 || *envelope.Code != 0 {
		return nil, &Error{Code: *envelope.Code, Message: envelope.Message,
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
		Code      *int            `json:"code"`
		Message   string          `json:"message"`
		Data      json.RawMessage `json:"data"`
		RequestID string          `json:"request_id"`
	}
	if err := json.Unmarshal(raw, &envelope); err != nil {
		return nil, &Error{Message: fmt.Sprintf("服务返回的不是 JSON（HTTP %d）", resp.StatusCode),
			Status: resp.StatusCode}
	}
	if envelope.Code == nil {
		return nil, &Error{Message: "服务返回的不是预期的结构", Status: resp.StatusCode}
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 || *envelope.Code != 0 {
		return nil, &Error{Code: *envelope.Code, Message: envelope.Message,
			RequestID: envelope.RequestID, Status: resp.StatusCode}
	}
	return envelope.Data, nil
}

// --- 实例 ---

// AccountCreate 创建一个实例。每个实例占用一个额度，删除实例后额度归还。实例创建后需要扫码登录才会上线。
//
// POST /v1/accounts
//
// args 里可以放：
//
//	proxy            必填  代理网络，必填，不能直连。有两种填法：socks5 代理地址，如 socks5://user:pass@host:port；网络助手的网络ID，在一台手机上安装并打开网络助手即可看到，实例将通过这台手机的网络连接微信。…
//	keep_history     可选  是否保存收发的消息和推送记录，默认 true。设为 false 时，消息不写入数据库，推送记录在投递结束后立即删除。图片等文件仍可下载，撤回功能仍可使用；但无法查询历史消息，也无法转发文字和卡片消息
//	name             可选  备注名称，仅自己可见
//	platform         可选  登录方式，留空时使用默认方式。并非每个部署都同时开通了两种方式。选择未开通的方式会直接报错，错误信息中会列出可选的方式（ipad / mac）
//	webhook_url      可选  接收该实例事件的 Webhook 地址
func (c *Client) AccountCreate(ctx context.Context, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts", nil, take(args, "platform", "name", "proxy", "webhook_url", "keep_history"))
}

// AccountList 列出你的全部实例及其状态。
//
// GET /v1/accounts
func (c *Client) AccountList(ctx context.Context) (json.RawMessage, error) {
	return c.Call(ctx, "GET", "/accounts", nil, nil)
}

// AccountGet 查询一个实例的详情。实例不在线时，reason 字段说明原因：manual 表示主动退出，kicked 表示因其他设备登录而被挤下线，relogin_required 表示需要重新扫码登录，recover_timeout 表示自动恢复超时，expired 表示授权到期。status 为 recovering 时，recovering 字段给出恢复方式和放弃恢复的时间。
//
// GET /v1/accounts/{account_id}
func (c *Client) AccountGet(ctx context.Context, accountId string) (json.RawMessage, error) {
	return c.Call(ctx, "GET", "/accounts/"+url.PathEscape(accountId), nil, nil)
}

// AccountQrcode 获取一张登录二维码，用手机微信扫码登录。expires_in 是二维码剩余的有效秒数，请以返回值为准，不要写死。二维码过期后重新获取即可。请求时可以带上 proxy 来更换代理网络。代理网络在建立登录会话时确定，更换后需要重新建立会话，所以只能在获取二维码时更换。不传 proxy 则沿用原来的代理网络。
//
// POST /v1/accounts/{account_id}/login/qrcode
//
// args 里可以放：
//
//	proxy            可选  要改用的代理网络，可以是socks5 地址或网络助手的网络ID。不传则沿用实例现有的代理网络。不能传空字符串，因为实例必须配置代理网络，不允许直连
func (c *Client) AccountQrcode(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/login/qrcode", nil, take(args, "proxy"))
}

// AccountLoginStatus 查询扫码登录的进度，供轮询使用。状态取值：waiting（等待扫码）、scanned（已扫码，等待确认）、verify（等待验证）、online（已上线）、cancelled（已取消）、expired（已过期）。状态为 waiting 时还会返回 expires_in，表示二维码此刻剩余的有效秒数，可用于校准倒计时。返回 notice 时，请把它原样展示给用户。Mac 端扫码后需要通过一次新设备验证，平台会自动完成这一步，这期间状态会一直保持为 scanned。请提示用户耐心等待，避免用户误以为登录卡住而取消登录。
//
// GET /v1/accounts/{account_id}/login/status
func (c *Client) AccountLoginStatus(ctx context.Context, accountId string) (json.RawMessage, error) {
	return c.Call(ctx, "GET", "/accounts/"+url.PathEscape(accountId)+"/login/status", nil, nil)
}

// AccountReconnect 实例掉线后，尝试在不重新扫码的情况下恢复连接。无法恢复时，才需要重新扫码登录。
//
// POST /v1/accounts/{account_id}/reconnect
func (c *Client) AccountReconnect(ctx context.Context, accountId string) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/reconnect", nil, nil)
}

// AccountLogout 让实例下线。实例和额度都会保留，之后可以重新扫码上线。
//
// POST /v1/accounts/{account_id}/logout
func (c *Client) AccountLogout(ctx context.Context, accountId string) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/logout", nil, nil)
}

// AccountDelete 删除实例并归还额度。历史消息不会立即清除。在线的实例不能直接删除，请先调用「退出登录」。如果直接删除，微信端的登录会话会继续保持，而平台已经无法再关闭它。
//
// DELETE /v1/accounts/{account_id}
func (c *Client) AccountDelete(ctx context.Context, accountId string) (json.RawMessage, error) {
	return c.Call(ctx, "DELETE", "/accounts/"+url.PathEscape(accountId), nil, nil)
}

// AccountProfile 查询这个实例自己的昵称、头像、地区等资料。
//
// 建议缓存：资料很少变化，登录成功后获取一次并保存即可。平时需要 wxid、昵称、头像时，请读取「实例详情」中的 profile 字段。该字段来自平台已保存的数据，不会向微信发起请求。
//
// GET /v1/accounts/{account_id}/profile
func (c *Client) AccountProfile(ctx context.Context, accountId string) (json.RawMessage, error) {
	return c.Call(ctx, "GET", "/accounts/"+url.PathEscape(accountId)+"/profile", nil, nil)
}

// AccountUpdateProfile 修改昵称、签名、性别和地区。留空的字段会被清空，请把需要保留的字段一并传入。
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

// AccountSetAvatar 修改头像。
//
// PUT /v1/accounts/{account_id}/profile/avatar
//
// args 里可以放：
//
//	url              必填  公网可下载的图片地址
func (c *Client) AccountSetAvatar(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "PUT", "/accounts/"+url.PathEscape(accountId)+"/profile/avatar", nil, take(args, "url"))
}

// AccountQrcodeSelf 获取这个实例自己的名片二维码。返回 data URL，可直接用作 img 标签的 src。
//
// 建议缓存：名片二维码基本不会变化。获取一次后保存为图片重复使用，不要每次展示时都重新获取。
//
// GET /v1/accounts/{account_id}/profile/qrcode
func (c *Client) AccountQrcodeSelf(ctx context.Context, accountId string) (json.RawMessage, error) {
	return c.Call(ctx, "GET", "/accounts/"+url.PathEscape(accountId)+"/profile/qrcode", nil, nil)
}

// AccountPrivacy 开启或关闭一项隐私设置。
//
// PUT /v1/accounts/{account_id}/privacy
//
// args 里可以放：
//
//	enabled          必填  true 开启，false 关闭
//	option           必填  need_confirm_to_add：加我为好友时需要验证；findable_by_phone：可以通过手机号搜到我；findable_by_alias：可以通过微信号搜到我；recommend_contacts：向…（need_confirm_to_add / findable_by_phone / findable_by_alias / recommend_contacts / strangers_see_ten / visible_days）
func (c *Client) AccountPrivacy(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "PUT", "/accounts/"+url.PathEscape(accountId)+"/privacy", nil, take(args, "option", "enabled"))
}

// AccountDevices 列出这个微信号登录过的设备，其中包括本平台。
//
// GET /v1/accounts/{account_id}/devices
func (c *Client) AccountDevices(ctx context.Context, accountId string) (json.RawMessage, error) {
	return c.Call(ctx, "GET", "/accounts/"+url.PathEscape(accountId)+"/devices", nil, nil)
}

// AccountDeviceSignout 让某个已登录的设备强制下线。注意不要把本平台自己也下线。
//
// DELETE /v1/accounts/{account_id}/devices/{device_id}
func (c *Client) AccountDeviceSignout(ctx context.Context, accountId string, deviceId string) (json.RawMessage, error) {
	return c.Call(ctx, "DELETE", "/accounts/"+url.PathEscape(accountId)+"/devices/"+url.PathEscape(deviceId), nil, nil)
}

// AccountHistory 设置这个实例是否保存收发的消息和推送记录。关闭后，新收发的消息不写入数据库，Webhook 推送记录在投递成功或放弃重试后立即删除。图片、语音、视频、文件仍然可以下载，自己发的消息仍然可以撤回，重复的推送仍然会去重。但无法查询历史消息，也无法转发文字和卡片消息。事件仍然会保存。关闭前已保存的消息不会立即删除，会按原来的保存期限自动清理。
//
// PUT /v1/accounts/{account_id}/history
//
// args 里可以放：
//
//	keep             必填  true 保存，false 不保存
func (c *Client) AccountHistory(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "PUT", "/accounts/"+url.PathEscape(accountId)+"/history", nil, take(args, "keep"))
}

// AccountWebhook 设置接收该实例事件的 Webhook 地址。每次推送都带有签名，可以用 secret 校验。
//
// PUT /v1/accounts/{account_id}/webhook
//
// args 里可以放：
//
//	url              必填  接收事件的地址
//	events           可选  只推送这些类型的事件，留空则推送全部事件
//	secret           可选  签名密钥，留空则保持不变
func (c *Client) AccountWebhook(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "PUT", "/accounts/"+url.PathEscape(accountId)+"/webhook", nil, take(args, "url", "secret", "events"))
}

// --- 联系人 ---

// ContactIds 列出通讯录中的全部条目，不做任何筛选，只返回标识：好友为 wxid，群 ID 以 @chatroom 结尾，公众号以 gh_ 开头。需要资料时，再用「联系人详情」按需查询。数据直接从微信获取，实例需要在线。每页条数由微信决定。翻页时把 next_cursor 原样传回，next_cursor 为空表示已经到最后一页。
//
// 建议缓存：登录成功后拉一次完整列表，保存在你自己的系统中，之后根据事件更新：收到 friend.added 时添加新好友，收到 contact.updated 时更新联系人资料，收到 contact.deleted 时移除联系人。不要定时整份重拉：每次调用都会从微信拉取完整列表，联系人多时耗时长、开销大，频繁拉取还会增加被微信风控的概率。
//
// GET /v1/accounts/{account_id}/contacts
//
// args 里可以放：
//
//	cursor           可选  上一页返回的 next_cursor，首页留空
func (c *Client) ContactIds(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "GET", "/accounts/"+url.PathEscape(accountId)+"/contacts", take(args, "cursor"), nil)
}

// ContactBatch 按 wxid 批量查询联系人资料。它和「联系人详情」调用的是微信的两个不同接口，一次查询很多人时，更适合用这个接口。 对个人好友，还会返回加好友的时间和方式：added_at、added_ts 是添加时间；add_source 是微信记录的添加方式编号，add_source_text 是它的中文说明，比如「扫一扫」「群聊」「搜索手机号」「名片分享」。含义还没有确认的编号只返回 add_source，不返回中文说明。通过群聊加的好友，add_source_group 是来源群的 ID。微信没有记录的项不返回。群和公众号不返回这几个字段。 所有联系人还会返回：avatar_large 高清头像（…
//
// 建议缓存：按 wxid 保存查到的资料，收到 contact.updated 事件时再更新对应联系人的资料。不要每收到一条消息就查询一次发送人的资料。
//
// POST /v1/accounts/{account_id}/contacts/batch
//
// args 里可以放：
//
//	wxids            必填  要查询的 wxid 列表
func (c *Client) ContactBatch(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/contacts/batch", nil, take(args, "wxids"))
}

// ContactDetail 查询联系人的完整资料，包括昵称、备注、微信号、头像、性别、地区、签名，以及该联系人的标签。标签在 label_ids 字段中，对应「标签列表」里的 ID；联系人没有标签时不返回这个字段。个人好友还会返回加好友的方式 add_source 和 add_source_text，通过群聊加的好友还有来源群 add_source_group；加好友的时间只有「批量取详情」能查到，这里不返回。高清头像 avatar_large、拼音 pinyin、备注电话 phones 和「批量取详情」一样返回。
//
// 建议缓存：按 wxid 保存查到的资料，收到 contact.updated 事件时再更新对应联系人的资料。不要每收到一条消息就查询一次发送人的资料。
//
// POST /v1/accounts/{account_id}/contacts/detail
//
// args 里可以放：
//
//	wxids            必填  要查询的 wxid，一次最多 50 个
func (c *Client) ContactDetail(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/contacts/detail", nil, take(args, "wxids"))
}

// ContactCheck 检测这些人是否仍是你的好友。注意：微信对这个操作限制很严，一次检测的人数多或检测频繁，都可能导致实例被限制。一次最多检测 20 个，请按需使用。
//
// 建议缓存：保存检测结果，同一个人在短时间内不要重复检测。
//
// POST /v1/accounts/{account_id}/contacts/check
//
// args 里可以放：
//
//	wxids            必填  要检测的 wxid，一次最多 20 个
func (c *Client) ContactCheck(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/contacts/check", nil, take(args, "wxids"))
}

// ContactExternal 查询企业微信的外部联系人。这些联系人不在普通通讯录中，「通讯录列表」接口查不到他们。本接口返回平台已保存的数据，使用前请先调用一次「同步企微联系人」。
//
// GET /v1/accounts/{account_id}/contacts/external
func (c *Client) ContactExternal(ctx context.Context, accountId string) (json.RawMessage, error) {
	return c.Call(ctx, "GET", "/accounts/"+url.PathEscape(accountId)+"/contacts/external", nil, nil)
}

// ContactExternalSync 从微信重新拉取企业微信的外部联系人并保存到平台，返回拉取到的人数。没有头像的联系人会逐个补充获取头像，人数多时耗时较长，不建议频繁调用。
//
// POST /v1/accounts/{account_id}/contacts/external/sync
func (c *Client) ContactExternalSync(ctx context.Context, accountId string) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/contacts/external/sync", nil, nil)
}

// ContactSearch 按微信号或手机号搜索用户，返回可用于添加好友的 contact_token。
//
// 建议缓存：保存搜索到的 wxid 和昵称，不要反复搜索同一个号。搜索过于频繁时，微信会提示操作过于频繁，之后一段时间内都无法搜索。contact_token 会过期，真正要添加好友时，再搜索一次获取新的 contact_token。
//
// POST /v1/accounts/{account_id}/contacts/search
//
// args 里可以放：
//
//	keyword          必填  微信号或手机号
func (c *Client) ContactSearch(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/contacts/search", nil, take(args, "keyword"))
}

// ContactAdd 用搜索得到的 contact_token 发起好友申请。**这个接口响应较慢**：微信需要 5～20 秒才返回结果，实测平均 9 秒，最慢 16 秒。客户端超时时间请至少设为 30 秒。请求超时后不要直接重发，因为请求很可能已经发送成功。需要重试时，请带上 Idempotency-Key。
//
// 注意（易封号）：敏感接口，调用不当容易被微信限制甚至封号。添加好友是微信风控最严格的操作之一。不要在短时间内连续添加，不要批量自动加人，每次添加之间要留出间隔。新注册的号、刚换设备或刚登录的号风险更高，建议先正常使用几天再添加好友。
//
// POST /v1/accounts/{account_id}/contacts/add
//
// args 里可以放：
//
//	contact_token    必填  搜索结果中的 contact_token
//	greeting         可选  发给对方的验证消息
//	scene            可选  申请来源，留空则使用默认值
func (c *Client) ContactAdd(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/contacts/add", nil, take(args, "contact_token", "greeting", "scene"))
}

// ContactAccept 通过他人的好友申请。需要传入好友申请事件中的 friend_request_token。
//
// 注意（易封号）：敏感接口，调用不当容易被微信限制甚至封号。短时间内大量通过好友申请同样会触发风控。不要在收到申请后立即批量自动通过，每次通过之间要留出间隔；申请数量多时，请分散到不同时间段处理。
//
// POST /v1/accounts/{account_id}/contacts/accept
//
// args 里可以放：
//
//	friend_request_token 必填  好友申请事件中的 friend_request_token
func (c *Client) ContactAccept(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/contacts/accept", nil, take(args, "friend_request_token"))
}

// ContactRemark 修改一个联系人的备注名。
//
// PUT /v1/accounts/{account_id}/contacts/{wxid}/remark
//
// args 里可以放：
//
//	remark           必填  新的备注名
func (c *Client) ContactRemark(ctx context.Context, accountId string, wxid string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "PUT", "/accounts/"+url.PathEscape(accountId)+"/contacts/"+url.PathEscape(wxid)+"/remark", nil, take(args, "remark"))
}

// ContactDelete 将联系人从通讯录中删除。对方不会收到通知，但之后无法再给你发消息。如需恢复，需要重新添加好友。
//
// DELETE /v1/accounts/{account_id}/contacts/{wxid}
func (c *Client) ContactDelete(ctx context.Context, accountId string, wxid string) (json.RawMessage, error) {
	return c.Call(ctx, "DELETE", "/accounts/"+url.PathEscape(accountId)+"/contacts/"+url.PathEscape(wxid), nil, nil)
}

// LabelList 列出这个实例的联系人标签。标签仅自己可见。
//
// 建议缓存：标签只有在你自己修改时才会变化。获取一次并保存，之后在新建、改名或删除标签后，再更新你保存的数据。
//
// GET /v1/accounts/{account_id}/labels
func (c *Client) LabelList(ctx context.Context, accountId string) (json.RawMessage, error) {
	return c.Call(ctx, "GET", "/accounts/"+url.PathEscape(accountId)+"/labels", nil, nil)
}

// LabelAdd 新建一个联系人标签，返回该标签的 label_id。
//
// POST /v1/accounts/{account_id}/labels
//
// args 里可以放：
//
//	name             必填  标签名
func (c *Client) LabelAdd(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/labels", nil, take(args, "name"))
}

// LabelRename 修改一个标签的名称。
//
// PUT /v1/accounts/{account_id}/labels/{label_id}
//
// args 里可以放：
//
//	name             必填  新的标签名
func (c *Client) LabelRename(ctx context.Context, accountId string, labelId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "PUT", "/accounts/"+url.PathEscape(accountId)+"/labels/"+url.PathEscape(labelId), nil, take(args, "name"))
}

// LabelDelete 删除一个标签。带有这个标签的联系人本身不受影响，只是不再带有该标签。
//
// DELETE /v1/accounts/{account_id}/labels/{label_id}
func (c *Client) LabelDelete(ctx context.Context, accountId string, labelId string) (json.RawMessage, error) {
	return c.Call(ctx, "DELETE", "/accounts/"+url.PathEscape(accountId)+"/labels/"+url.PathEscape(labelId), nil, nil)
}

// ContactLabels 为指定的联系人设置标签。设置采用覆盖方式：这些联系人原有的标签会全部替换为本次传入的标签。label_ids 传空数组表示移除他们的全部标签。不在 wxids 中的联系人不受影响。给某些联系人设置一个标签，不会把这个标签从其他联系人身上移除。
//
// PUT /v1/accounts/{account_id}/contacts/labels
//
// args 里可以放：
//
//	label_ids        必填  设置后这些联系人拥有的全部标签 ID，对应「标签列表」里的 ID。传空数组表示不带任何标签
//	wxids            必填  要设置标签的联系人 wxid，一次最多 50 个
func (c *Client) ContactLabels(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "PUT", "/accounts/"+url.PathEscape(accountId)+"/contacts/labels", nil, take(args, "wxids", "label_ids"))
}

// --- 群 ---

// GroupCreate 邀请几位好友创建一个群聊，至少需要两个成员。
//
// POST /v1/accounts/{account_id}/groups
//
// args 里可以放：
//
//	members          必填  初始成员的 wxid
func (c *Client) GroupCreate(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/groups", nil, take(args, "members"))
}

// GroupGet 查询群的名称、公告、群主等资料。
//
// 建议缓存：保存群资料，收到 group.renamed 事件时再重新获取。公告和群主很少变化，不要每收到一条群消息就查询一次。
//
// GET /v1/accounts/{account_id}/groups/{group_id}
func (c *Client) GroupGet(ctx context.Context, accountId string, groupId string) (json.RawMessage, error) {
	return c.Call(ctx, "GET", "/accounts/"+url.PathEscape(accountId)+"/groups/"+url.PathEscape(groupId), nil, nil)
}

// GroupMembers 列出群成员。
//
// 建议缓存：保存成员列表，之后根据 group.member_joined 和 group.member_left 事件增减成员。每次调用都会实时从微信拉取，大群耗时长、开销大，不要定时重新拉取整个列表。
//
// GET /v1/accounts/{account_id}/groups/{group_id}/members
func (c *Client) GroupMembers(ctx context.Context, accountId string, groupId string) (json.RawMessage, error) {
	return c.Call(ctx, "GET", "/accounts/"+url.PathEscape(accountId)+"/groups/"+url.PathEscape(groupId)+"/members", nil, nil)
}

// GroupMemberDetail 查询指定群成员的完整资料，字段比「群成员」接口更全。
//
// 建议缓存：按 wxid 保存成员资料，不要每收到一条群消息就查询一次发言人的资料。
//
// POST /v1/accounts/{account_id}/groups/{group_id}/members/detail
//
// args 里可以放：
//
//	members          必填  要查询的 wxid
func (c *Client) GroupMemberDetail(ctx context.Context, accountId string, groupId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/groups/"+url.PathEscape(groupId)+"/members/detail", nil, take(args, "members"))
}

// GroupInvite 邀请好友入群。群人数较多时，微信会改为发送邀请链接。
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

// GroupRemove 将成员移出群聊。只有群主和管理员可以操作。
//
// POST /v1/accounts/{account_id}/groups/{group_id}/members/remove
//
// args 里可以放：
//
//	members          必填  要移出的 wxid
func (c *Client) GroupRemove(ctx context.Context, accountId string, groupId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/groups/"+url.PathEscape(groupId)+"/members/remove", nil, take(args, "members"))
}

// GroupAdmins 设置或取消群管理员，也可以转让群主。只有群主可以操作。
//
// POST /v1/accounts/{account_id}/groups/{group_id}/admins
//
// args 里可以放：
//
//	action           必填  grant 设为管理员，revoke 取消管理员，transfer 转让群主（转让群主时 members 只能填一个人）（grant / revoke / transfer）
//	members          必填  目标成员的 wxid
func (c *Client) GroupAdmins(ctx context.Context, accountId string, groupId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/groups/"+url.PathEscape(groupId)+"/admins", nil, take(args, "action", "members"))
}

// GroupRename 修改群名称。需要有修改群名称的权限。
//
// PUT /v1/accounts/{account_id}/groups/{group_id}/name
//
// args 里可以放：
//
//	name             必填  新的群名称
func (c *Client) GroupRename(ctx context.Context, accountId string, groupId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "PUT", "/accounts/"+url.PathEscape(accountId)+"/groups/"+url.PathEscape(groupId)+"/name", nil, take(args, "name"))
}

// GroupAnnouncement 修改群公告。只有群主和管理员可以操作，修改后会向全群发送一条提示。
//
// PUT /v1/accounts/{account_id}/groups/{group_id}/announcement
//
// args 里可以放：
//
//	content          必填  公告正文，留空表示清除
func (c *Client) GroupAnnouncement(ctx context.Context, accountId string, groupId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "PUT", "/accounts/"+url.PathEscape(accountId)+"/groups/"+url.PathEscape(groupId)+"/announcement", nil, take(args, "content"))
}

// GroupRemark 为群设置一个仅自己可见的备注名。
//
// PUT /v1/accounts/{account_id}/groups/{group_id}/remark
//
// args 里可以放：
//
//	remark           必填  备注名，留空表示清除
func (c *Client) GroupRemark(ctx context.Context, accountId string, groupId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "PUT", "/accounts/"+url.PathEscape(accountId)+"/groups/"+url.PathEscape(groupId)+"/remark", nil, take(args, "remark"))
}

// GroupNickname 修改自己在这个群里显示的昵称。
//
// PUT /v1/accounts/{account_id}/groups/{group_id}/nickname
//
// args 里可以放：
//
//	nickname         必填  群内昵称
func (c *Client) GroupNickname(ctx context.Context, accountId string, groupId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "PUT", "/accounts/"+url.PathEscape(accountId)+"/groups/"+url.PathEscape(groupId)+"/nickname", nil, take(args, "nickname"))
}

// GroupKept 将群保存到通讯录，或取消保存。没有保存到通讯录的群，在聊天会话被删除后将无法再找到。
//
// PUT /v1/accounts/{account_id}/groups/{group_id}/kept
//
// args 里可以放：
//
//	enabled          必填  true 保存，false 取消
func (c *Client) GroupKept(ctx context.Context, accountId string, groupId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "PUT", "/accounts/"+url.PathEscape(accountId)+"/groups/"+url.PathEscape(groupId)+"/kept", nil, take(args, "enabled"))
}

// GroupQrcode 获取群的邀请二维码。返回 data URL，可直接用作 img 标签的 src。
//
// 建议缓存：群二维码 7 天内有效。获取一次后保存为图片，快过期时再重新获取。
//
// GET /v1/accounts/{account_id}/groups/{group_id}/qrcode
func (c *Client) GroupQrcode(ctx context.Context, accountId string, groupId string) (json.RawMessage, error) {
	return c.Call(ctx, "GET", "/accounts/"+url.PathEscape(accountId)+"/groups/"+url.PathEscape(groupId)+"/qrcode", nil, nil)
}

// GroupJoin 通过收到的群邀请链接加入群聊。
//
// POST /v1/accounts/{account_id}/groups/join
//
// args 里可以放：
//
//	url              必填  邀请链接
func (c *Client) GroupJoin(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/groups/join", nil, take(args, "url"))
}

// GroupPreview 查看群邀请链接对应的群信息，不会加入该群。usable 为 false 时，notice 字段说明原因，比如链接已过期。
//
// POST /v1/accounts/{account_id}/groups/preview
//
// args 里可以放：
//
//	url              必填  邀请链接
func (c *Client) GroupPreview(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/groups/preview", nil, take(args, "url"))
}

// GroupApprove 群成员邀请他人入群后，群主用这个接口同意邀请。inviter、message_id、ticket、members 四个参数都来自这条邀请事件。
//
// POST /v1/accounts/{account_id}/groups/{group_id}/approve
//
// args 里可以放：
//
//	inviter          必填  邀请人的 wxid
//	members          必填  被邀请人的 wxid
//	message_id       必填  邀请事件中的消息 ID
//	ticket           必填  邀请事件中的凭据
func (c *Client) GroupApprove(ctx context.Context, accountId string, groupId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/groups/"+url.PathEscape(groupId)+"/approve", nil, take(args, "inviter", "message_id", "ticket", "members"))
}

// ChatMuted 为一个群或一个好友开启或关闭消息免打扰。
//
// PUT /v1/accounts/{account_id}/chats/{chat_id}/muted
//
// args 里可以放：
//
//	enabled          必填  true 开启免打扰，false 恢复消息提醒
func (c *Client) ChatMuted(ctx context.Context, accountId string, chatId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "PUT", "/accounts/"+url.PathEscape(accountId)+"/chats/"+url.PathEscape(chatId)+"/muted", nil, take(args, "enabled"))
}

// ChatPinned 将一个群或一个好友的会话置顶，或取消置顶。
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

// MessageText 发送一条文字消息。在群里发送时可以 @ 群成员。
//
// POST /v1/accounts/{account_id}/messages/text
//
// args 里可以放：
//
//	content          必填  消息正文
//	to               必填  接收方：好友的 wxid、群 ID，或 filehelper（自己的文件传输助手）
//	mentions         可选  要 @ 的成员 wxid，仅在群聊中有效
func (c *Client) MessageText(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/messages/text", nil, take(args, "to", "content", "mentions"))
}

// MessageImage 发送一张图片。url 和 media_id 二选一，使用 media_id 可以复用平台已保存的文件。
//
// POST /v1/accounts/{account_id}/messages/image
//
// args 里可以放：
//
//	to               必填  接收方：好友的 wxid、群 ID，或 filehelper（自己的文件传输助手）
//	media_id         可选  平台中已有文件的媒体 ID
//	url              可选  可从公网下载的文件地址
//	use_cache        可选  同一个 url 之前发送过时，直接复用已上传的文件，不再重新上传。默认 true。只有地址不变但文件内容已更换时，才需要传 false
func (c *Client) MessageImage(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/messages/image", nil, take(args, "to", "url", "media_id", "use_cache"))
}

// MessageVideo 发送一段视频。不填时长时由平台估算，部分客户端可能会显示异常。
//
// POST /v1/accounts/{account_id}/messages/video
//
// args 里可以放：
//
//	to               必填  接收方：好友的 wxid、群 ID，或 filehelper（自己的文件传输助手）
//	duration         可选  时长（秒）
//	media_id         可选  平台中已有文件的媒体 ID
//	thumbnail_url    可选  封面图地址，需要是可从公网下载的图片
//	url              可选  可从公网下载的文件地址
//	use_cache        可选  同一个 url 之前发送过时，直接复用已上传的文件，不再重新上传。默认 true。只有地址不变但文件内容已更换时，才需要传 false
func (c *Client) MessageVideo(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/messages/video", nil, take(args, "to", "url", "media_id", "use_cache", "duration", "thumbnail_url"))
}

// MessageVoice 发送一条语音。seconds 是语音时长，会显示在聊天中的语音消息上。
//
// POST /v1/accounts/{account_id}/messages/voice
//
// args 里可以放：
//
//	to               必填  接收方：好友的 wxid、群 ID，或 filehelper（自己的文件传输助手）
//	url              必填  可从公网下载的音频地址
//	seconds          可选  时长（秒）
func (c *Client) MessageVoice(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/messages/voice", nil, take(args, "to", "url", "seconds"))
}

// MessageFile 发送一个文件。
//
// POST /v1/accounts/{account_id}/messages/file
//
// args 里可以放：
//
//	to               必填  接收方：好友的 wxid、群 ID，或 filehelper（自己的文件传输助手）
//	filename         可选  对方看到的文件名
//	media_id         可选  平台中已有文件的媒体 ID
//	url              可选  可从公网下载的文件地址
//	use_cache        可选  同一个 url 之前发送过时，直接复用已上传的文件，不再重新上传。默认 true。只有地址不变但文件内容已更换时，才需要传 false
func (c *Client) MessageFile(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/messages/file", nil, take(args, "to", "url", "media_id", "use_cache", "filename"))
}

// MessageSticker 转发一个动图表情。表情通过引用发送，无需上传文件。checksum 和 length 取自收到的表情消息。
//
// POST /v1/accounts/{account_id}/messages/sticker
//
// args 里可以放：
//
//	checksum         必填  表情的校验值，取自收到的表情消息
//	length           必填  表情的字节数，取自同一条表情消息
//	to               必填  接收方：好友的 wxid、群 ID，或 filehelper（自己的文件传输助手）
func (c *Client) MessageSticker(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/messages/sticker", nil, take(args, "to", "checksum", "length"))
}

// MessageLink 发送一张可点击的链接卡片。
//
// POST /v1/accounts/{account_id}/messages/link
//
// args 里可以放：
//
//	title            必填  卡片标题
//	to               必填  接收方：好友的 wxid、群 ID，或 filehelper（自己的文件传输助手）
//	url              必填  点击后打开的地址
//	description      可选  卡片摘要
//	source_name      可选  来源名称
//	thumb_url        可选  封面图地址
func (c *Client) MessageLink(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/messages/link", nil, take(args, "to", "title", "description", "url", "thumb_url", "source_name"))
}

// MessageMiniapp 发送一张小程序卡片。需要提供小程序的标识。
//
// POST /v1/accounts/{account_id}/messages/miniapp
//
// args 里可以放：
//
//	app_id           必填  小程序的公开标识
//	title            必填  卡片标题
//	to               必填  接收方：好友的 wxid、群 ID，或 filehelper（自己的文件传输助手）
//	username         必填  小程序的原始 ID
//	description      可选  卡片摘要
//	path             可选  点击后打开的小程序页面路径
//	source_name      可选  来源名称
//	thumb_url        可选  封面图地址
func (c *Client) MessageMiniapp(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/messages/miniapp", nil, take(args, "to", "app_id", "username", "title", "description", "path", "thumb_url", "source_name"))
}

// MessageForward 将收到过的一条消息原样转发给其他人。
//
// POST /v1/accounts/{account_id}/messages/forward
//
// args 里可以放：
//
//	message_id       必填  要转发的消息 ID
//	to               必填  接收方：好友的 wxid、群 ID，或 filehelper（自己的文件传输助手）
func (c *Client) MessageForward(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/messages/forward", nil, take(args, "to", "message_id"))
}

// MessageRecall 撤回自己发出的一条消息。微信只允许在发出后约两分钟内撤回，超过时间会被拒绝。
//
// POST /v1/accounts/{account_id}/messages/{message_id}/recall
func (c *Client) MessageRecall(ctx context.Context, accountId string, messageId string) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/messages/"+url.PathEscape(messageId)+"/recall", nil, nil)
}

// MessageHistory 查询平台保存的消息记录，可以按会话筛选。
//
// GET /v1/accounts/{account_id}/messages
//
// args 里可以放：
//
//	cursor           可选  上一页返回的 next_cursor，首页留空
//	limit            可选  每页条数，最多 200，超过按 200 处理
//	peer             可选  只返回与某个 wxid 或群的会话中的消息
func (c *Client) MessageHistory(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "GET", "/accounts/"+url.PathEscape(accountId)+"/messages", take(args, "peer", "cursor", "limit"), nil)
}

// MessageSync 主动拉取这个实例收到的消息，内容与 Webhook 推送的完全相同。如果没有配置 Webhook、Webhook 中断过，或者服务重启过，可以用它补回这段时间的消息。cursor 留空时，从目前仍保留的最早一条消息开始返回（大约可追溯一天）。之后每次调用都传入上一次返回的 next_cursor。has_more 为 true 表示还没有拉取完，请立即再调用一次。没有新消息时，返回的 next_cursor 与传入的相同，游标不会前进。拉取到的消息不会写入数据库，也不会触发 Webhook，重复拉取没有副作用。
//
// POST /v1/accounts/{account_id}/messages/sync
//
// args 里可以放：
//
//	cursor           可选  上一次返回的 next_cursor，第一次留空
func (c *Client) MessageSync(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/messages/sync", nil, take(args, "cursor"))
}

// MessageGet 查询一条消息。
//
// GET /v1/accounts/{account_id}/messages/{message_id}
func (c *Client) MessageGet(ctx context.Context, accountId string, messageId string) (json.RawMessage, error) {
	return c.Call(ctx, "GET", "/accounts/"+url.PathEscape(accountId)+"/messages/"+url.PathEscape(messageId), nil, nil)
}

// FavoriteList 列出这个实例收藏的内容。cursor 留空时从第一页开始，返回的 next_cursor 为空表示已经到最后一页。
//
// 建议缓存：收藏只在你自己新增或删除收藏时才会变化。获取一次并保存，不要轮询；在你新增或删除收藏后再重新获取。
//
// GET /v1/accounts/{account_id}/favorites
//
// args 里可以放：
//
//	cursor           可选  上一页返回的 next_cursor，首页留空
func (c *Client) FavoriteList(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "GET", "/accounts/"+url.PathEscape(accountId)+"/favorites", take(args, "cursor"), nil)
}

// FavoriteGet 查询一条收藏的完整内容。内容为微信原始的 XML，平台原样返回，不同类型的收藏结构不同。
//
// 建议缓存：收藏的内容不会变化。按 fav_id 保存，获取过一次就不需要再获取。
//
// GET /v1/accounts/{account_id}/favorites/{fav_id}
func (c *Client) FavoriteGet(ctx context.Context, accountId string, favId string) (json.RawMessage, error) {
	return c.Call(ctx, "GET", "/accounts/"+url.PathEscape(accountId)+"/favorites/"+url.PathEscape(favId), nil, nil)
}

// FavoriteDelete 删除一条收藏。
//
// DELETE /v1/accounts/{account_id}/favorites/{fav_id}
func (c *Client) FavoriteDelete(ctx context.Context, accountId string, favId string) (json.RawMessage, error) {
	return c.Call(ctx, "DELETE", "/accounts/"+url.PathEscape(accountId)+"/favorites/"+url.PathEscape(favId), nil, nil)
}

// --- 媒体 ---

// MediaUpload 直接上传文件，获取一个 media_id。之后发送图片、视频、语音或文件时，只需传入这个 ID。适用于文件在你自己的机器上、没有公网地址的情况，比如程序刚生成的一张图片。请用 multipart/form-data 提交，文件放在 file 字段中，最大 20 MB。文件在第一次发送时才会真正上传到微信，之后用同一个 ID 发送不会重复上传。上传后一直没有发送过的文件保留 24 小时。
//
// POST /v1/accounts/{account_id}/media/upload
//
// args 里可以放：
//
//	file             必填  要上传的文件，multipart/form-data
//	kind             可选  这个文件将作为哪种消息发送，不填则根据文件类型自动判断（image / video / voice / file）
func (c *Client) MediaUpload(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	content, _ := args["file"].([]byte)
	name, _ := args["filename"].(string)
	return c.Upload(ctx, "/accounts/"+url.PathEscape(accountId)+"/media/upload", name, content, take(args, "kind"))
}

// MediaFromMessage 获取一条消息中的图片、视频、文件或语音，返回一个限时有效的下载地址。
//
// 建议缓存：下载地址有时效。拿到文件后请保存到你自己的存储中，不要每次展示时都重新下载。平台上已下载文件的总量超过上限时，会清除最早的一半，请不要把平台当作长期存储。
//
// POST /v1/accounts/{account_id}/media/download
//
// args 里可以放：
//
//	message_id       必填  带附件的消息 ID
func (c *Client) MediaFromMessage(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/media/download", nil, take(args, "message_id"))
}

// MediaCached 查询平台是否已经发送过某个地址的文件。发送过的文件可以直接复用，不需要重新上传，也不计流量。建议在把文件放到公网之前先调用这个接口。如果平台已经发送过，就不必再把文件放到公网。
//
// POST /v1/accounts/{account_id}/media/cached
//
// args 里可以放：
//
//	kind             必填  image、video 或 file
//	url              必填  要发送的文件地址，必须与发送时填写的地址完全一致才算命中
func (c *Client) MediaCached(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/media/cached", nil, take(args, "url", "kind"))
}

// MediaGet 为已经下载过的文件重新生成一个限时有效的下载地址。
//
// GET /v1/accounts/{account_id}/media/{media_id}
func (c *Client) MediaGet(ctx context.Context, accountId string, mediaId string) (json.RawMessage, error) {
	return c.Call(ctx, "GET", "/accounts/"+url.PathEscape(accountId)+"/media/"+url.PathEscape(mediaId), nil, nil)
}

// MediaMoment 获取一条朋友圈动态中的第 N 张图片，或动态中的视频。
//
// POST /v1/accounts/{account_id}/moments/{moment_id}/media/download
//
// args 里可以放：
//
//	index            可选  图片序号，从 0 开始。视频动态会忽略这个值
func (c *Client) MediaMoment(ctx context.Context, accountId string, momentId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/moments/"+url.PathEscape(momentId)+"/media/download", nil, take(args, "index"))
}

// --- 朋友圈 ---

// MomentTimeline 查询这个实例能看到的朋友圈时间线。
//
// 建议缓存：每次调用都会实时向微信请求，请不要定时刷新，刷新过于频繁会被视为异常行为。需要时再获取，并把获取到的动态保存在你自己的系统中。
//
// GET /v1/accounts/{account_id}/moments
//
// args 里可以放：
//
//	cursor           可选  上一页返回的 next_cursor
func (c *Client) MomentTimeline(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "GET", "/accounts/"+url.PathEscape(accountId)+"/moments", take(args, "cursor"), nil)
}

// MomentGet 查询一条朋友圈的完整内容。列表接口中的点赞和评论会被截断，这个接口返回完整的点赞和评论。
//
// 建议缓存：动态的正文和图片不会变化，获取后请保存。只有需要查看最新的点赞和评论时，才需要重新获取。
//
// GET /v1/accounts/{account_id}/moments/{moment_id}
func (c *Client) MomentGet(ctx context.Context, accountId string, momentId string) (json.RawMessage, error) {
	return c.Call(ctx, "GET", "/accounts/"+url.PathEscape(accountId)+"/moments/"+url.PathEscape(momentId), nil, nil)
}

// MomentUser 查询某个联系人的朋友圈主页。
//
// 建议缓存：每次调用都会实时向微信请求，请不要定时刷新，刷新过于频繁会被视为异常行为。需要时再获取，并把获取到的动态保存在你自己的系统中。
//
// GET /v1/accounts/{account_id}/moments/user/{wxid}
//
// args 里可以放：
//
//	cursor           可选  上一页返回的 next_cursor
func (c *Client) MomentUser(ctx context.Context, accountId string, wxid string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "GET", "/accounts/"+url.PathEscape(accountId)+"/moments/user/"+url.PathEscape(wxid), take(args, "cursor"), nil)
}

// MomentPostText 发布一条纯文字朋友圈。
//
// POST /v1/accounts/{account_id}/moments/text
//
// args 里可以放：
//
//	content          必填  正文
//	mentions         可选  要 @ 的 wxid
//	visibility       可选  可见范围。mode 可选 public、private、allow、deny；选择 allow 或 deny 时，需要同时提供 wxids 或 tag_ids
func (c *Client) MomentPostText(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/moments/text", nil, take(args, "content", "mentions", "visibility"))
}

// MomentPostImages 发布一条带图片的朋友圈。
//
// POST /v1/accounts/{account_id}/moments/images
//
// args 里可以放：
//
//	images           必填  图片列表
//	content          可选  正文
//	visibility       可选  可见范围。mode 可选 public、private、allow、deny；选择 allow 或 deny 时，需要同时提供 wxids 或 tag_ids
func (c *Client) MomentPostImages(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/moments/images", nil, take(args, "content", "images", "visibility"))
}

// MomentPostVideo 发布一条视频朋友圈。
//
// POST /v1/accounts/{account_id}/moments/video
//
// args 里可以放：
//
//	video            必填  视频，需提供可从公网下载的地址
//	content          可选  正文
//	cover            可选  封面图
//	duration         可选  时长（秒）
//	visibility       可选  可见范围。mode 可选 public、private、allow、deny；选择 allow 或 deny 时，需要同时提供 wxids 或 tag_ids
func (c *Client) MomentPostVideo(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/moments/video", nil, take(args, "content", "video", "cover", "duration", "visibility"))
}

// MomentRepost 将看到的一条动态原样重新发布一次。
//
// POST /v1/accounts/{account_id}/moments/forward
//
// args 里可以放：
//
//	moment_id        必填  要转发的动态 ID
//	visibility       可选  可见范围。mode 可选 public、private、allow、deny；选择 allow 或 deny 时，需要同时提供 wxids 或 tag_ids
func (c *Client) MomentRepost(ctx context.Context, accountId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/moments/forward", nil, take(args, "moment_id", "visibility"))
}

// MomentLike 给一条动态点赞。点赞前需要先通过朋友圈列表或详情接口读取过这条动态，读取后 24 小时内可以点赞。
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
//	reply_to         可选  要回复的评论 ID，留空表示直接评论这条动态
func (c *Client) MomentComment(ctx context.Context, accountId string, momentId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "POST", "/accounts/"+url.PathEscape(accountId)+"/moments/"+url.PathEscape(momentId)+"/comments", nil, take(args, "content", "reply_to"))
}

// MomentDeleteComment 删除自己发表的一条评论。
//
// DELETE /v1/accounts/{account_id}/moments/{moment_id}/comments/{comment_id}
func (c *Client) MomentDeleteComment(ctx context.Context, accountId string, momentId string, commentId string) (json.RawMessage, error) {
	return c.Call(ctx, "DELETE", "/accounts/"+url.PathEscape(accountId)+"/moments/"+url.PathEscape(momentId)+"/comments/"+url.PathEscape(commentId), nil, nil)
}

// MomentDelete 删除自己发布的一条朋友圈。
//
// DELETE /v1/accounts/{account_id}/moments/{moment_id}
func (c *Client) MomentDelete(ctx context.Context, accountId string, momentId string) (json.RawMessage, error) {
	return c.Call(ctx, "DELETE", "/accounts/"+url.PathEscape(accountId)+"/moments/"+url.PathEscape(momentId), nil, nil)
}

// MomentPrivacy 将自己的一条动态设为仅自己可见，或恢复为公开。
//
// PUT /v1/accounts/{account_id}/moments/{moment_id}/privacy
//
// args 里可以放：
//
//	private          必填  true 表示仅自己可见，false 表示公开
func (c *Client) MomentPrivacy(ctx context.Context, accountId string, momentId string, args M) (json.RawMessage, error) {
	return c.Call(ctx, "PUT", "/accounts/"+url.PathEscape(accountId)+"/moments/"+url.PathEscape(momentId)+"/privacy", nil, take(args, "private"))
}

// --- 平台 ---

// PlatformMe 查询当前 API Key 所属的用户，以及该用户的实例数量。
//
// GET /v1/me
func (c *Client) PlatformMe(ctx context.Context) (json.RawMessage, error) {
	return c.Call(ctx, "GET", "/me", nil, nil)
}

// PlatformEvents 查询平台记录的事件，可以按实例、事件类型、消息类型和时间筛选。没有配置 Webhook 时，可以轮询这个接口获取事件。 轮询方法：第一次调用可以用 since 指定起始时间。之后每次调用都传入上一次返回的 next_cursor，从该位置之后继续读取。只要本页有事件，就一定会返回 next_cursor。没有新事件时 next_cursor 为空，此时请继续使用你已保存的上一个 next_cursor。has_more 为 true 表示后面还有事件，请立即继续读取；否则请等待几秒后再轮询。如果处理过程中程序重启，而最新的游标还没来得及保存，重新读取时会再次拿到相同的几条事件，因此建议按 e…
//
// GET /v1/events
//
// args 里可以放：
//
//	account_id       可选  只返回某个实例的事件
//	cursor           可选  上一次返回的 next_cursor，从该位置之后继续读取。第一次调用时留空。如果返回的 next_cursor 为空，请继续使用上一次的值
//	keyword          可选  按事件内容搜索。需要同时指定时间范围，且范围不超过 1 小时
//	limit            可选  每页条数，最多 200，超过按 200 处理
//	message_type     可选  只返回某种消息类型的事件，如 text、image、file，参数可重复传入。设置后，非消息类事件不会出现在结果中
//	order            可选  oldest 按时间从早到晚返回（默认），newest 从最近发生的事件开始返回（oldest / newest）
//	since            可选  只返回这个时间之后的事件，格式为 RFC3339 或 Unix 秒级时间戳
//	type             可选  只返回某种类型的事件，参数可重复传入
//	until            可选  只返回这个时间之前的事件，格式为 RFC3339 或 Unix 秒级时间戳
func (c *Client) PlatformEvents(ctx context.Context, args M) (json.RawMessage, error) {
	return c.Call(ctx, "GET", "/events", take(args, "account_id", "type", "message_type", "since", "until", "keyword", "order", "cursor", "limit"), nil)
}

// PlatformStream 通过 SSE 长连接实时接收该实例的事件，事件内容与 Webhook 推送的相同。
//
// GET /v1/accounts/{account_id}/stream
// EventStream 持有 SSE 连接。调用者必须 Close，或取消传入的 context。
type EventStream struct {
	body    io.ReadCloser
	scanner *bufio.Scanner
}

func (s *EventStream) Close() error { return s.body.Close() }

// Next 返回下一条事件；心跳注释会跳过，连接结束返回 io.EOF。
func (s *EventStream) Next() (json.RawMessage, error) {
	var data []string
	for s.scanner.Scan() {
		line := strings.TrimSuffix(s.scanner.Text(), "\r")
		if line == "" && len(data) > 0 {
			raw := json.RawMessage(strings.Join(data, "\n"))
			if !json.Valid(raw) {
				return nil, &Error{Message: "事件内容不是 JSON"}
			}
			return raw, nil
		}
		if strings.HasPrefix(line, "data:") {
			data = append(data, strings.TrimPrefix(line[5:], " "))
		}
	}
	if err := s.scanner.Err(); err != nil {
		return nil, &Error{Message: "事件流连接中断：" + err.Error()}
	}
	return nil, io.EOF
}

func (c *Client) PlatformStream(ctx context.Context, accountId string) (*EventStream, error) {
	req, err := http.NewRequestWithContext(ctx, "GET", c.BaseURL+"/v1/accounts/"+url.PathEscape(accountId)+"/stream", nil)
	if err != nil {
		return nil, &Error{Message: err.Error()}
	}
	req.Header.Set("Authorization", "Bearer "+c.APIKey)
	req.Header.Set("Accept", "text/event-stream")
	client := c.HTTP
	if client == nil {
		client = http.DefaultClient
	}
	streamClient := *client
	streamClient.Timeout = 0 // 生命周期由 context 控制，避免普通请求超时截断长连接。
	resp, err := streamClient.Do(req)
	if err != nil {
		return nil, &Error{Message: "无法打开事件流：" + err.Error()}
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		defer resp.Body.Close()
		var envelope struct {
			Code      int    `json:"code"`
			Message   string `json:"message"`
			RequestID string `json:"request_id"`
		}
		_ = json.NewDecoder(resp.Body).Decode(&envelope)
		if envelope.Message == "" {
			envelope.Message = "无法打开事件流"
		}
		return nil, &Error{Code: envelope.Code, Message: envelope.Message, RequestID: envelope.RequestID, Status: resp.StatusCode}
	}
	if !strings.HasPrefix(resp.Header.Get("Content-Type"), "text/event-stream") {
		resp.Body.Close()
		return nil, &Error{Message: "服务没有返回事件流", Status: resp.StatusCode}
	}
	scanner := bufio.NewScanner(resp.Body)
	scanner.Buffer(make([]byte, 4096), 2<<20)
	return &EventStream{body: resp.Body, scanner: scanner}, nil
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
