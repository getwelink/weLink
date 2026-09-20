package welink;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * WeLink —— 把微信的能力做成 HTTP 接口。
 *
 * <pre>
 * WeLink wx = new WeLink("key_xxx", "https://你的服务地址");
 * wx.messageText("acc_xxx", Map.of("to", "filehelper", "content", "你好"));
 * </pre>
 *
 * 每个方法对应一个接口，返回的是响应里 data 字段的内容：JSON 对象是
 * {@code Map<String,Object>}，数组是 {@code List<Object>}，其余是
 * String / BigDecimal / Boolean / null。
 *
 * 调用失败抛 {@link WeLinkException}，上面带平台错误码和 requestId。
 *
 * 不依赖任何第三方库，JDK 11 以上即可。
 *
 * 本文件由接口清单生成，不要手改。
 */
public class WeLink {

    private final String apiKey;
    private final String baseUrl;
    private final HttpClient http;

    public WeLink(String apiKey, String baseUrl) {
        this(apiKey, baseUrl, Duration.ofSeconds(30));
    }

    public WeLink(String apiKey, String baseUrl, Duration timeout) {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalArgumentException("apiKey 不能为空");
        }
        if (baseUrl == null || baseUrl.isEmpty()) {
            throw new IllegalArgumentException("baseUrl 不能为空，填你拿到的服务地址");
        }
        this.apiKey = apiKey;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /** 一次失败的调用。 */
    public static class WeLinkException extends RuntimeException {
        public final int code;
        public final String requestId;
        public final int status;

        public WeLinkException(int code, String message, String requestId, int status) {
            super("[" + code + "] " + message);
            this.code = code;
            this.requestId = requestId;
            this.status = status;
        }
    }

    /**
     * 校验事件回调的签名。
     *
     * 必须拿原始请求体来算 —— 先反序列化再重新序列化，字段顺序和空格都会变，
     * 算出来的签名就对不上了。
     */
    public static boolean verifyWebhook(String secret, byte[] body, String signature) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            StringBuilder hex = new StringBuilder("sha256=");
            for (byte b : mac.doFinal(body)) {
                hex.append(String.format("%02x", b));
            }
            byte[] want = hex.toString().getBytes(StandardCharsets.UTF_8);
            byte[] got = (signature == null ? "" : signature).getBytes(StandardCharsets.UTF_8);
            if (want.length != got.length) {
                return false;
            }
            int diff = 0;
            for (int i = 0; i < want.length; i++) {
                diff |= want[i] ^ got[i];
            }
            return diff == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** 直接调用一个接口。清单里还没有的新接口可以用它。 */
    public Object call(String method, String path, Map<String, Object> query, Map<String, Object> body) {
        StringBuilder target = new StringBuilder(baseUrl).append("/v1").append(path);
        if (query != null && !query.isEmpty()) {
            StringBuilder q = new StringBuilder();
            for (Map.Entry<String, Object> entry : query.entrySet()) {
                Object value = entry.getValue();
                if (value == null || "".equals(value)) {
                    continue;
                }
                if (q.length() > 0) {
                    q.append('&');
                }
                q.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                        .append('=')
                        .append(URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8));
            }
            if (q.length() > 0) {
                target.append('?').append(q);
            }
        }

        HttpRequest.BodyPublisher payload = HttpRequest.BodyPublishers.noBody();
        boolean hasBody = false;
        if (body != null) {
            // 没填的参数不发出去，免得把服务端的默认值覆盖掉。
            Map<String, Object> clean = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : body.entrySet()) {
                if (entry.getValue() != null) {
                    clean.put(entry.getKey(), entry.getValue());
                }
            }
            payload = HttpRequest.BodyPublishers.ofString(Json.write(clean), StandardCharsets.UTF_8);
            hasBody = true;
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(target.toString()))
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .method(method, payload);
        if (hasBody) {
            builder.header("Content-Type", "application/json");
        }

        HttpResponse<String> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException | InterruptedException e) {
            throw new WeLinkException(0, "连不上服务：" + e.getMessage(), "", 0);
        }

        Object parsed;
        try {
            parsed = Json.read(response.body());
        } catch (RuntimeException e) {
            throw new WeLinkException(0, "服务返回的不是 JSON（HTTP " + response.statusCode() + "）",
                    "", response.statusCode());
        }
        if (!(parsed instanceof Map)) {
            throw new WeLinkException(0, "服务返回的不是预期的结构", "", response.statusCode());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> envelope = (Map<String, Object>) parsed;
        int code = envelope.get("code") instanceof BigDecimal
                ? ((BigDecimal) envelope.get("code")).intValue() : 0;
        if (code != 0) {
            throw new WeLinkException(code, String.valueOf(envelope.get("message")),
                    String.valueOf(envelope.getOrDefault("request_id", "")), response.statusCode());
        }
        return envelope.get("data");
    }

    /**
     * 以 multipart/form-data 上传一个文件。
     *
     * <p>JDK 自己不带 multipart，所以这里手工拼；好处是这个 SDK 依然只依赖 JDK。
     *
     * @param path    接口路径，例如 /accounts/acc_xxx/media/upload
     * @param name    文件名，影响对端认出来的类型
     * @param content 文件内容
     * @param fields  同时要带的普通表单字段，可以为 null
     */
    public Object upload(String path, String name, byte[] content, Map<String, Object> fields) {
        if (content == null || content.length == 0) {
            throw new WeLinkException(0, "文件是空的", "", 0);
        }
        String filename = (name == null || name.isEmpty()) ? "file" : name;
        String boundary = "----welink" + java.util.UUID.randomUUID().toString().replace("-", "");
        String crlf = "\r\n";

        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        try {
            if (fields != null) {
                for (Map.Entry<String, Object> entry : fields.entrySet()) {
                    Object value = entry.getValue();
                    if (value == null || String.valueOf(value).isEmpty()) {
                        continue;
                    }
                    buf.write(("--" + boundary + crlf).getBytes(StandardCharsets.UTF_8));
                    buf.write(("Content-Disposition: form-data; name=\"" + entry.getKey()
                            + "\"" + crlf + crlf).getBytes(StandardCharsets.UTF_8));
                    buf.write((String.valueOf(value) + crlf).getBytes(StandardCharsets.UTF_8));
                }
            }
            buf.write(("--" + boundary + crlf).getBytes(StandardCharsets.UTF_8));
            buf.write(("Content-Disposition: form-data; name=\"file\"; filename=\""
                    + filename + "\"" + crlf).getBytes(StandardCharsets.UTF_8));
            buf.write(("Content-Type: application/octet-stream" + crlf + crlf)
                    .getBytes(StandardCharsets.UTF_8));
            buf.write(content);
            buf.write(crlf.getBytes(StandardCharsets.UTF_8));
            buf.write(("--" + boundary + "--" + crlf).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new WeLinkException(0, "拼装上传内容失败：" + e.getMessage(), "", 0);
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1" + path))
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(buf.toByteArray()))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException | InterruptedException e) {
            throw new WeLinkException(0, "连不上服务：" + e.getMessage(), "", 0);
        }

        Object parsed;
        try {
            parsed = Json.read(response.body());
        } catch (RuntimeException e) {
            throw new WeLinkException(0, "服务返回的不是 JSON（HTTP " + response.statusCode() + "）",
                    "", response.statusCode());
        }
        if (!(parsed instanceof Map)) {
            throw new WeLinkException(0, "服务返回的不是预期的结构", "", response.statusCode());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> envelope = (Map<String, Object>) parsed;
        int code = envelope.get("code") instanceof BigDecimal
                ? ((BigDecimal) envelope.get("code")).intValue() : 0;
        if (code != 0) {
            throw new WeLinkException(code, String.valueOf(envelope.get("message")),
                    String.valueOf(envelope.getOrDefault("request_id", "")), response.statusCode());
        }
        return envelope.get("data");
    }

    /** 从参数里挑出这个接口认识的那几个，其余忽略。 */
    private static Map<String, Object> take(Map<String, Object> args, String... keys) {
        if (args == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : keys) {
            if (args.containsKey(key)) {
                out.put(key, args.get(key));
            }
        }
        return out;
    }


    // --- 实例 ---

    /**
     * 创建实例 —— 开一个实例。占用一个额度，删除后归还。创建后还要扫码才会上线。
     * <p>POST /v1/accounts
     * <p>args 里可以放：
     * <ul>
     * <li>platform —— 必填 登录方式（ipad / mac）</li>
     * <li>name —— 可选 备注名称，只给自己看</li>
     * <li>proxy —— 可选 代理网络，留空则直连。两种填法：socks5 代理地址（socks5://user:pass@host:port），或者网络助手的网络ID（把网络助手装到一台手机上，打开即可看到，这台手机的网络就是这个实例的出口）——…</li>
     * <li>webhook_url —— 可选 该实例的事件推送地址</li>
     * </ul>
     */
    public Object accountCreate(Map<String, Object> args) {
        return call("POST", "/accounts", null, take(args, "platform", "name", "proxy", "webhook_url"));
    }

    /**
     * 实例列表 —— 列出你的全部实例与它们的状态。
     * <p>GET /v1/accounts
     */
    public Object accountList() {
        return call("GET", "/accounts", null, null);
    }

    /**
     * 实例详情 —— 读一个实例。不在线时 reason 会说明原因（manual 主动退出、kicked 被别处挤下线、relogin_required 需重新扫码、recover_timeout 恢复超时、expired 授权到期）；status 为 recovering 时 recovering 里带恢复方式与放弃时间。
     * <p>GET /v1/accounts/{account_id}
     */
    public Object accountGet(String accountId) {
        return call("GET", "/accounts/" + accountId, null, null);
    }

    /**
     * 获取登录二维码 —— 取一张登录二维码，用手机扫。expires_in 是这张码还剩多少秒，以返回值为准，不要写死；过期了再取一张即可。带上 proxy 可以顺便换代理网络 —— 它是开会话时定下的，换了要重开会话，所以只能在扫码这一刻换；不传则沿用原来的。
     * <p>POST /v1/accounts/{account_id}/login/qrcode
     * <p>args 里可以放：
     * <ul>
     * <li>proxy —— 可选 改用这个代理网络：socks5 代理地址或网络助手的网络ID；传空串改为直连，不传则不动</li>
     * </ul>
     */
    public Object accountQrcode(String accountId, Map<String, Object> args) {
        return call("POST", "/accounts/" + accountId + "/login/qrcode", null, take(args, "proxy"));
    }

    /**
     * 登录状态 —— 轮询扫码进度：waiting（等待扫码）、scanned（已扫码待确认）、online（已上线）、cancelled、expired。等待扫码时还带 expires_in，是这张码此刻还剩多少秒，用它校准倒计时。
     * <p>GET /v1/accounts/{account_id}/login/status
     */
    public Object accountLoginStatus(String accountId) {
        return call("GET", "/accounts/" + accountId + "/login/status", null, null);
    }

    /**
     * 取消扫码 —— 放弃这次扫码。已经发出去的码会连同它背后的会话一起作废，扫了也不会让这个实例上线；实例回到未登录，重新取码即可。关闭扫码页面时调用它，别把一张还能用的码留在外面。
     * <p>POST /v1/accounts/{account_id}/login/cancel
     */
    public Object accountLoginCancel(String accountId) {
        return call("POST", "/accounts/" + accountId + "/login/cancel", null, null);
    }

    /**
     * 提交安全验证 —— 登录过程中出现安全验证时，把验证结果提交回来。
     * <p>POST /v1/accounts/{account_id}/login/captcha
     * <p>args 里可以放：
     * <ul>
     * <li>fields —— 必填 验证所需的字段，按提示填写</li>
     * </ul>
     */
    public Object accountCaptcha(String accountId, Map<String, Object> args) {
        return call("POST", "/accounts/" + accountId + "/login/captcha", null, take(args, "fields"));
    }

    /**
     * 重新连接 —— 掉线后尝试不重新扫码就恢复连接。恢复不了才需要重新扫码。
     * <p>POST /v1/accounts/{account_id}/reconnect
     */
    public Object accountReconnect(String accountId) {
        return call("POST", "/accounts/" + accountId + "/reconnect", null, null);
    }

    /**
     * 退出登录 —— 让实例下线。实例与额度保留，可以再扫码上线。
     * <p>POST /v1/accounts/{account_id}/logout
     */
    public Object accountLogout(String accountId) {
        return call("POST", "/accounts/" + accountId + "/logout", null, null);
    }

    /**
     * 删除实例 —— 删除槽位并归还额度。历史消息不会立刻清除。
     * <p>DELETE /v1/accounts/{account_id}
     */
    public Object accountDelete(String accountId) {
        return call("DELETE", "/accounts/" + accountId, null, null);
    }

    /**
     * 实例资料 —— 读这个实例自己的昵称、头像、地区等资料。
     * <p>GET /v1/accounts/{account_id}/profile
     */
    public Object accountProfile(String accountId) {
        return call("GET", "/accounts/" + accountId + "/profile", null, null);
    }

    /**
     * 修改个人资料 —— 改昵称、签名、性别与地区。字段留空就是清空该项，请把要保留的一起传。
     * <p>PUT /v1/accounts/{account_id}/profile
     * <p>args 里可以放：
     * <ul>
     * <li>city —— 可选 市</li>
     * <li>country —— 可选 国家</li>
     * <li>nickname —— 可选 昵称</li>
     * <li>province —— 可选 省</li>
     * <li>sex —— 可选 1 男，2 女，0 不显示（0 / 1 / 2）</li>
     * <li>signature —— 可选 个性签名</li>
     * </ul>
     */
    public Object accountUpdateProfile(String accountId, Map<String, Object> args) {
        return call("PUT", "/accounts/" + accountId + "/profile", null, take(args, "nickname", "signature", "sex", "country", "province", "city"));
    }

    /**
     * 设置微信号 —— 设置可被搜索的微信号。微信只允许设置一次，之后会拒绝。
     * <p>PUT /v1/accounts/{account_id}/profile/alias
     * <p>args 里可以放：
     * <ul>
     * <li>alias —— 必填 要设置的微信号</li>
     * </ul>
     */
    public Object accountSetAlias(String accountId, Map<String, Object> args) {
        return call("PUT", "/accounts/" + accountId + "/profile/alias", null, take(args, "alias"));
    }

    /**
     * 修改头像 —— 换头像。
     * <p>PUT /v1/accounts/{account_id}/profile/avatar
     * <p>args 里可以放：
     * <ul>
     * <li>url —— 必填 公网可下载的图片地址</li>
     * </ul>
     */
    public Object accountSetAvatar(String accountId, Map<String, Object> args) {
        return call("PUT", "/accounts/" + accountId + "/profile/avatar", null, take(args, "url"));
    }

    /**
     * 我的二维码 —— 取这个实例自己的名片二维码，返回 data URL，可直接放进 img。
     * <p>GET /v1/accounts/{account_id}/profile/qrcode
     */
    public Object accountQrcodeSelf(String accountId) {
        return call("GET", "/accounts/" + accountId + "/profile/qrcode", null, null);
    }

    /**
     * 隐私设置 —— 开关一项隐私设置。
     * <p>PUT /v1/accounts/{account_id}/privacy
     * <p>args 里可以放：
     * <ul>
     * <li>enabled —— 必填 开或关</li>
     * <li>option —— 必填 need_confirm_to_add 加我需验证；findable_by_phone 手机号可搜；findable_by_alias 微信号可搜；recommend_contacts 向我推荐通讯录好友；strang…（need_confirm_to_add / findable_by_phone / findable_by_alias / recommend_contacts / strangers_see_ten / visible_days）</li>
     * </ul>
     */
    public Object accountPrivacy(String accountId, Map<String, Object> args) {
        return call("PUT", "/accounts/" + accountId + "/privacy", null, take(args, "option", "enabled"));
    }

    /**
     * 已登录设备 —— 列出这个微信号登录过的设备，本平台也在其中。
     * <p>GET /v1/accounts/{account_id}/devices
     */
    public Object accountDevices(String accountId) {
        return call("GET", "/accounts/" + accountId + "/devices", null, null);
    }

    /**
     * 下线某个设备 —— 把某个已登录设备踢下线。注意别把本平台自己踢了。
     * <p>DELETE /v1/accounts/{account_id}/devices/{device_id}
     */
    public Object accountDeviceSignout(String accountId, String deviceId) {
        return call("DELETE", "/accounts/" + accountId + "/devices/" + deviceId, null, null);
    }

    /**
     * 设置 Webhook —— 设置该实例事件的推送地址。每次投递都带签名，用 secret 校验。
     * <p>PUT /v1/accounts/{account_id}/webhook
     * <p>args 里可以放：
     * <ul>
     * <li>url —— 必填 接收事件的地址</li>
     * <li>events —— 可选 只推这些类型，留空推全部</li>
     * <li>secret —— 可选 签名密钥，留空则保持不变</li>
     * </ul>
     */
    public Object accountWebhook(String accountId, Map<String, Object> args) {
        return call("PUT", "/accounts/" + accountId + "/webhook", null, take(args, "url", "secret", "events"));
    }


    // --- 联系人 ---

    /**
     * 通讯录标识 —— 列出通讯录里都有谁，只给标识：好友的 wxid、群的 @chatroom、公众号的 gh_ 开头，一个不筛。要资料再用「联系人详情」按需取——一千个人里你可能只关心十个。直接向微信取，实例要在线；一页多大由微信定，翻页把 next_cursor 原样带回来，为空表示到底。
     * <p>GET /v1/accounts/{account_id}/contacts
     * <p>args 里可以放：
     * <ul>
     * <li>cursor —— 可选 上一页返回的 next_cursor，首页留空</li>
     * </ul>
     */
    public Object contactIds(String accountId, Map<String, Object> args) {
        return call("GET", "/accounts/" + accountId + "/contacts", take(args, "cursor"), null);
    }

    /**
     * 批量取详情 —— 按 wxid 批量取联系人资料。与「联系人详情」走的是微信的两条不同路径，字段相同，这条更适合一次问很多人。
     * <p>POST /v1/accounts/{account_id}/contacts/batch
     * <p>args 里可以放：
     * <ul>
     * <li>wxids —— 必填 要查的 wxid 列表</li>
     * </ul>
     */
    public Object contactBatch(String accountId, Map<String, Object> args) {
        return call("POST", "/accounts/" + accountId + "/contacts/batch", null, take(args, "wxids"));
    }

    /**
     * 联系人详情 —— 读联系人的完整资料：昵称、备注、微信号、头像、性别、地区、签名。
     * <p>POST /v1/accounts/{account_id}/contacts/detail
     * <p>args 里可以放：
     * <ul>
     * <li>wxids —— 必填 要查的 wxid，一次最多 50 个</li>
     * </ul>
     */
    public Object contactDetail(String accountId, Map<String, Object> args) {
        return call("POST", "/accounts/" + accountId + "/contacts/detail", null, take(args, "wxids"));
    }

    /**
     * 检测好友关系 —— 查这些人是否还是好友。注意：微信对这个操作盯得很紧，查得多或查得频繁会导致实例被限制，一次最多 20 个，请按需使用。
     * <p>POST /v1/accounts/{account_id}/contacts/check
     * <p>args 里可以放：
     * <ul>
     * <li>wxids —— 必填 要检测的 wxid，一次最多 20 个</li>
     * </ul>
     */
    public Object contactCheck(String accountId, Map<String, Object> args) {
        return call("POST", "/accounts/" + accountId + "/contacts/check", null, take(args, "wxids"));
    }

    /**
     * 企微联系人 —— 读企业微信那边的外部联系人。这些人不在普通通讯录里，「通讯录标识」拉不到他们。读的是平台存下来的那一份，先调一次同步。
     * <p>GET /v1/accounts/{account_id}/contacts/external
     */
    public Object contactExternal(String accountId) {
        return call("GET", "/accounts/" + accountId + "/contacts/external", null, null);
    }

    /**
     * 同步企微联系人 —— 去微信那边重新拉一遍企微外部联系人并存下来，返回拉到多少个。没有头像的会逐个补拉，人多时会慢一些，不建议频繁调用。
     * <p>POST /v1/accounts/{account_id}/contacts/external/sync
     */
    public Object contactExternalSync(String accountId) {
        return call("POST", "/accounts/" + accountId + "/contacts/external/sync", null, null);
    }

    /**
     * 搜索用户 —— 按微信号或手机号搜人，返回一个可用于加好友的 contact_token。
     * <p>POST /v1/accounts/{account_id}/contacts/search
     * <p>args 里可以放：
     * <ul>
     * <li>keyword —— 必填 微信号或手机号</li>
     * </ul>
     */
    public Object contactSearch(String accountId, Map<String, Object> args) {
        return call("POST", "/accounts/" + accountId + "/contacts/search", null, take(args, "keyword"));
    }

    /**
     * 添加好友 —— 用搜索得到的 contact_token 发起好友申请。**这一条慢**：微信自己要 5～20 秒才回，实测平均 9 秒、最慢 16 秒，客户端超时请留够 30 秒。超时了不要直接重发——请求多半已经送出去了，要重试就带上 Idempotency-Key。加得太频繁会被微信限制。
     * <p>POST /v1/accounts/{account_id}/contacts/add
     * <p>args 里可以放：
     * <ul>
     * <li>contact_token —— 必填 搜索结果里的 contact_token</li>
     * <li>greeting —— 可选 打招呼的话</li>
     * <li>scene —— 可选 申请来源，留空用默认值</li>
     * </ul>
     */
    public Object contactAdd(String accountId, Map<String, Object> args) {
        return call("POST", "/accounts/" + accountId + "/contacts/add", null, take(args, "contact_token", "greeting", "scene"));
    }

    /**
     * 通过好友申请 —— 同意别人的好友申请，用事件里给出的 friend_request_token。
     * <p>POST /v1/accounts/{account_id}/contacts/accept
     * <p>args 里可以放：
     * <ul>
     * <li>friend_request_token —— 必填 好友申请事件里的 token</li>
     * </ul>
     */
    public Object contactAccept(String accountId, Map<String, Object> args) {
        return call("POST", "/accounts/" + accountId + "/contacts/accept", null, take(args, "friend_request_token"));
    }

    /**
     * 设置备注 —— 给一个联系人改备注名。
     * <p>PUT /v1/accounts/{account_id}/contacts/{wxid}/remark
     * <p>args 里可以放：
     * <ul>
     * <li>remark —— 必填 新的备注名</li>
     * </ul>
     */
    public Object contactRemark(String accountId, String wxid, Map<String, Object> args) {
        return call("PUT", "/accounts/" + accountId + "/contacts/" + wxid + "/remark", null, take(args, "remark"));
    }

    /**
     * 删除好友 —— 把人从通讯录里删掉。对方不会收到通知，但从此发不进来；要恢复得重新加。
     * <p>DELETE /v1/accounts/{account_id}/contacts/{wxid}
     */
    public Object contactDelete(String accountId, String wxid) {
        return call("DELETE", "/accounts/" + accountId + "/contacts/" + wxid, null, null);
    }

    /**
     * 标签列表 —— 列出这个实例的联系人标签。标签只有自己看得见。
     * <p>GET /v1/accounts/{account_id}/labels
     */
    public Object labelList(String accountId) {
        return call("GET", "/accounts/" + accountId + "/labels", null, null);
    }

    /**
     * 新建标签 —— 新建一个联系人标签，返回它的 label_id。
     * <p>POST /v1/accounts/{account_id}/labels
     * <p>args 里可以放：
     * <ul>
     * <li>name —— 必填 标签名</li>
     * </ul>
     */
    public Object labelAdd(String accountId, Map<String, Object> args) {
        return call("POST", "/accounts/" + accountId + "/labels", null, take(args, "name"));
    }

    /**
     * 改标签名 —— 改一个标签的名字。
     * <p>PUT /v1/accounts/{account_id}/labels/{label_id}
     * <p>args 里可以放：
     * <ul>
     * <li>name —— 必填 新的标签名</li>
     * </ul>
     */
    public Object labelRename(String accountId, String labelId, Map<String, Object> args) {
        return call("PUT", "/accounts/" + accountId + "/labels/" + labelId, null, take(args, "name"));
    }

    /**
     * 删除标签 —— 删掉一个标签。带这个标签的联系人不受影响，只是不再带它。
     * <p>DELETE /v1/accounts/{account_id}/labels/{label_id}
     */
    public Object labelDelete(String accountId, String labelId) {
        return call("DELETE", "/accounts/" + accountId + "/labels/" + labelId, null, null);
    }

    /**
     * 设置标签成员 —— 设置哪些联系人带这个标签。是覆盖不是追加：没列进来的会被摘掉。
     * <p>PUT /v1/accounts/{account_id}/labels/{label_id}/members
     * <p>args 里可以放：
     * <ul>
     * <li>wxids —— 必填 带这个标签的 wxid 全集</li>
     * </ul>
     */
    public Object labelMembers(String accountId, String labelId, Map<String, Object> args) {
        return call("PUT", "/accounts/" + accountId + "/labels/" + labelId + "/members", null, take(args, "wxids"));
    }


    // --- 群 ---

    /**
     * 创建群聊 —— 拉几个好友建一个群。至少两个成员。
     * <p>POST /v1/accounts/{account_id}/groups
     * <p>args 里可以放：
     * <ul>
     * <li>members —— 必填 初始成员的 wxid</li>
     * </ul>
     */
    public Object groupCreate(String accountId, Map<String, Object> args) {
        return call("POST", "/accounts/" + accountId + "/groups", null, take(args, "members"));
    }

    /**
     * 群详情 —— 读群的名称、公告、群主等资料。
     * <p>GET /v1/accounts/{account_id}/groups/{group_id}
     */
    public Object groupGet(String accountId, String groupId) {
        return call("GET", "/accounts/" + accountId + "/groups/" + groupId, null, null);
    }

    /**
     * 群成员 —— 列出群成员。
     * <p>GET /v1/accounts/{account_id}/groups/{group_id}/members
     */
    public Object groupMembers(String accountId, String groupId) {
        return call("GET", "/accounts/" + accountId + "/groups/" + groupId + "/members", null, null);
    }

    /**
     * 群成员详情 —— 读指定几个群成员的完整资料，比群成员列表更全。
     * <p>POST /v1/accounts/{account_id}/groups/{group_id}/members/detail
     * <p>args 里可以放：
     * <ul>
     * <li>members —— 必填 要查的 wxid</li>
     * </ul>
     */
    public Object groupMemberDetail(String accountId, String groupId, Map<String, Object> args) {
        return call("POST", "/accounts/" + accountId + "/groups/" + groupId + "/members/detail", null, take(args, "members"));
    }

    /**
     * 邀请入群 —— 邀请好友进群。群人数多时微信会改为发邀请链接。
     * <p>POST /v1/accounts/{account_id}/groups/{group_id}/invite
     * <p>args 里可以放：
     * <ul>
     * <li>members —— 必填 要邀请的 wxid</li>
     * <li>reason —— 可选 邀请说明</li>
     * </ul>
     */
    public Object groupInvite(String accountId, String groupId, Map<String, Object> args) {
        return call("POST", "/accounts/" + accountId + "/groups/" + groupId + "/invite", null, take(args, "members", "reason"));
    }

    /**
     * 移出群成员 —— 把人移出群。只有群主和管理员能做。
     * <p>POST /v1/accounts/{account_id}/groups/{group_id}/members/remove
     * <p>args 里可以放：
     * <ul>
     * <li>members —— 必填 要移出的 wxid</li>
     * </ul>
     */
    public Object groupRemove(String accountId, String groupId, Map<String, Object> args) {
        return call("POST", "/accounts/" + accountId + "/groups/" + groupId + "/members/remove", null, take(args, "members"));
    }

    /**
     * 群管理员 —— 设置或取消群管理员，也可以转让群主。只有群主能做。
     * <p>POST /v1/accounts/{account_id}/groups/{group_id}/admins
     * <p>args 里可以放：
     * <ul>
     * <li>action —— 必填 grant 设为管理员，revoke 取消，transfer 转让群主（只能一个人）（grant / revoke / transfer）</li>
     * <li>members —— 必填 目标 wxid</li>
     * </ul>
     */
    public Object groupAdmins(String accountId, String groupId, Map<String, Object> args) {
        return call("POST", "/accounts/" + accountId + "/groups/" + groupId + "/admins", null, take(args, "action", "members"));
    }

    /**
     * 修改群名 —— 改群名称。需要有权限改。
     * <p>PUT /v1/accounts/{account_id}/groups/{group_id}/name
     * <p>args 里可以放：
     * <ul>
     * <li>name —— 必填 新的群名称</li>
     * </ul>
     */
    public Object groupRename(String accountId, String groupId, Map<String, Object> args) {
        return call("PUT", "/accounts/" + accountId + "/groups/" + groupId + "/name", null, take(args, "name"));
    }

    /**
     * 设置群公告 —— 改群公告。只有群主和管理员能做，会给全群发一条提示。
     * <p>PUT /v1/accounts/{account_id}/groups/{group_id}/announcement
     * <p>args 里可以放：
     * <ul>
     * <li>content —— 必填 公告正文，留空表示清除</li>
     * </ul>
     */
    public Object groupAnnouncement(String accountId, String groupId, Map<String, Object> args) {
        return call("PUT", "/accounts/" + accountId + "/groups/" + groupId + "/announcement", null, take(args, "content"));
    }

    /**
     * 设置群备注 —— 给群起一个只有自己看得到的名字。
     * <p>PUT /v1/accounts/{account_id}/groups/{group_id}/remark
     * <p>args 里可以放：
     * <ul>
     * <li>remark —— 必填 备注名，留空表示清除</li>
     * </ul>
     */
    public Object groupRemark(String accountId, String groupId, Map<String, Object> args) {
        return call("PUT", "/accounts/" + accountId + "/groups/" + groupId + "/remark", null, take(args, "remark"));
    }

    /**
     * 设置我的群昵称 —— 改自己在这个群里显示的名字。
     * <p>PUT /v1/accounts/{account_id}/groups/{group_id}/nickname
     * <p>args 里可以放：
     * <ul>
     * <li>nickname —— 必填 群内昵称</li>
     * </ul>
     */
    public Object groupNickname(String accountId, String groupId, Map<String, Object> args) {
        return call("PUT", "/accounts/" + accountId + "/groups/" + groupId + "/nickname", null, take(args, "nickname"));
    }

    /**
     * 保存到通讯录 —— 把群保存到通讯录，或取消保存。不保存的群在会话删除后就找不回来了。
     * <p>PUT /v1/accounts/{account_id}/groups/{group_id}/kept
     * <p>args 里可以放：
     * <ul>
     * <li>enabled —— 必填 true 保存，false 取消</li>
     * </ul>
     */
    public Object groupKept(String accountId, String groupId, Map<String, Object> args) {
        return call("PUT", "/accounts/" + accountId + "/groups/" + groupId + "/kept", null, take(args, "enabled"));
    }

    /**
     * 群二维码 —— 取群的邀请二维码，返回 data URL，可直接放进 img。
     * <p>GET /v1/accounts/{account_id}/groups/{group_id}/qrcode
     */
    public Object groupQrcode(String accountId, String groupId) {
        return call("GET", "/accounts/" + accountId + "/groups/" + groupId + "/qrcode", null, null);
    }

    /**
     * 通过链接进群 —— 用收到的群邀请链接进群。
     * <p>POST /v1/accounts/{account_id}/groups/join
     * <p>args 里可以放：
     * <ul>
     * <li>url —— 必填 邀请链接</li>
     * </ul>
     */
    public Object groupJoin(String accountId, Map<String, Object> args) {
        return call("POST", "/accounts/" + accountId + "/groups/join", null, take(args, "url"));
    }

    /**
     * 查看群邀请 —— 拿一个群邀请链接先看看是什么群，不进群。usable 为 false 时 notice 说明原因，比如链接已过期。
     * <p>POST /v1/accounts/{account_id}/groups/preview
     * <p>args 里可以放：
     * <ul>
     * <li>url —— 必填 邀请链接</li>
     * </ul>
     */
    public Object groupPreview(String accountId, Map<String, Object> args) {
        return call("POST", "/accounts/" + accountId + "/groups/preview", null, take(args, "url"));
    }

    /**
     * 同意入群邀请 —— 群成员邀请了人进群，群主在这里放行。四个参数都来自那条邀请事件。
     * <p>POST /v1/accounts/{account_id}/groups/{group_id}/approve
     * <p>args 里可以放：
     * <ul>
     * <li>inviter —— 必填 邀请人的 wxid</li>
     * <li>members —— 必填 被邀请人的 wxid</li>
     * <li>message_id —— 必填 邀请事件里的消息 ID</li>
     * <li>ticket —— 必填 邀请事件里的凭据</li>
     * </ul>
     */
    public Object groupApprove(String accountId, String groupId, Map<String, Object> args) {
        return call("POST", "/accounts/" + accountId + "/groups/" + groupId + "/approve", null, take(args, "inviter", "message_id", "ticket", "members"));
    }

    /**
     * 消息免打扰 —— 对一个群或一个好友开关消息免打扰。
     * <p>PUT /v1/accounts/{account_id}/chats/{chat_id}/muted
     * <p>args 里可以放：
     * <ul>
     * <li>enabled —— 必填 true 免打扰，false 恢复提醒</li>
     * </ul>
     */
    public Object chatMuted(String accountId, String chatId, Map<String, Object> args) {
        return call("PUT", "/accounts/" + accountId + "/chats/" + chatId + "/muted", null, take(args, "enabled"));
    }

    /**
     * 聊天置顶 —— 把一个群或一个好友的会话置顶，或取消置顶。
     * <p>PUT /v1/accounts/{account_id}/chats/{chat_id}/pinned
     * <p>args 里可以放：
     * <ul>
     * <li>enabled —— 必填 true 置顶，false 取消</li>
     * </ul>
     */
    public Object chatPinned(String accountId, String chatId, Map<String, Object> args) {
        return call("PUT", "/accounts/" + accountId + "/chats/" + chatId + "/pinned", null, take(args, "enabled"));
    }


    // --- 消息 ---

    /**
     * 发文字 —— 发一条文字消息。群里可以 @人。
     * <p>POST /v1/accounts/{account_id}/messages/text
     * <p>args 里可以放：
     * <ul>
     * <li>content —— 必填 消息正文</li>
     * <li>to —— 必填 接收者：wxid、群 ID，或 filehelper（自己的文件传输助手）</li>
     * <li>mentions —— 可选 要 @ 的 wxid，只在群里有意义</li>
     * <li>to_list —— 可选 一次发给多个接收者，与 to 二选一</li>
     * </ul>
     */
    public Object messageText(String accountId, Map<String, Object> args) {
        return call("POST", "/accounts/" + accountId + "/messages/text", null, take(args, "to", "to_list", "content", "mentions"));
    }

    /**
     * 发图片 —— 发一张图片。url 与 media_id 二选一，media_id 可以复用平台已存的文件。
     * <p>POST /v1/accounts/{account_id}/messages/image
     * <p>args 里可以放：
     * <ul>
     * <li>to —— 必填 接收者：wxid、群 ID，或 filehelper（自己的文件传输助手）</li>
     * <li>media_id —— 可选 平台里已有的媒体 ID</li>
     * <li>url —— 可选 公网可下载的地址</li>
     * <li>use_cache —— 可选 同一个 url 之前发过就直接转发，不重新上传。默认 true；地址没变但内容换了才需要传 false</li>
     * </ul>
     */
    public Object messageImage(String accountId, Map<String, Object> args) {
        return call("POST", "/accounts/" + accountId + "/messages/image", null, take(args, "to", "url", "media_id", "use_cache"));
    }

    /**
     * 发视频 —— 发一段视频。不填时长的话由平台估算，有些客户端会显示得不好看。
     * <p>POST /v1/accounts/{account_id}/messages/video
     * <p>args 里可以放：
     * <ul>
     * <li>to —— 必填 接收者：wxid、群 ID，或 filehelper（自己的文件传输助手）</li>
     * <li>duration —— 可选 时长（秒）</li>
     * <li>height —— 可选 画面高，不传则动态里不带尺寸</li>
     * <li>media_id —— 可选 平台里已有的媒体 ID</li>
     * <li>thumbnail_url —— 可选 封面图地址，公网可下载的一张图片</li>
     * <li>url —— 可选 公网可下载的地址</li>
     * <li>use_cache —— 可选 同一个 url 之前发过就直接转发，不重新上传。默认 true；地址没变但内容换了才需要传 false</li>
     * <li>width —— 可选 画面宽，不传则动态里不带尺寸</li>
     * </ul>
     */
    public Object messageVideo(String accountId, Map<String, Object> args) {
        return call("POST", "/accounts/" + accountId + "/messages/video", null, take(args, "to", "url", "media_id", "use_cache", "duration", "width", "height", "thumbnail_url"));
    }

    /**
     * 发语音 —— 发一条语音。seconds 是时长，显示在气泡上。
     * <p>POST /v1/accounts/{account_id}/messages/voice
     * <p>args 里可以放：
     * <ul>
     * <li>to —— 必填 接收者：wxid、群 ID，或 filehelper（自己的文件传输助手）</li>
     * <li>url —— 必填 公网可下载的音频地址</li>
     * <li>seconds —— 可选 时长（秒）</li>
     * </ul>
     */
    public Object messageVoice(String accountId, Map<String, Object> args) {
        return call("POST", "/accounts/" + accountId + "/messages/voice", null, take(args, "to", "url", "seconds"));
    }

    /**
     * 发文件 —— 发一个文件。
     * <p>POST /v1/accounts/{account_id}/messages/file
     * <p>args 里可以放：
     * <ul>
     * <li>to —— 必填 接收者：wxid、群 ID，或 filehelper（自己的文件传输助手）</li>
     * <li>filename —— 可选 对方看到的文件名</li>
     * <li>media_id —— 可选 平台里已有的媒体 ID</li>
     * <li>url —— 可选 公网可下载的地址</li>
     * <li>use_cache —— 可选 同一个 url 之前发过就直接转发，不重新上传。默认 true；地址没变但内容换了才需要传 false</li>
     * </ul>
     */
    public Object messageFile(String accountId, Map<String, Object> args) {
        return call("POST", "/accounts/" + accountId + "/messages/file", null, take(args, "to", "url", "media_id", "use_cache", "filename"));
    }

    /**
     * 发动图表情 —— 转发一个动图表情。表情是引用不是上传：checksum 与 length 来自收到的那条表情消息。
     * <p>POST /v1/accounts/{account_id}/messages/sticker
     * <p>args 里可以放：
     * <ul>
     * <li>checksum —— 必填 表情的校验值，来自收到的表情消息</li>
     * <li>length —— 必填 表情的字节数，来自同一条消息</li>
     * <li>to —— 必填 接收者：wxid、群 ID，或 filehelper（自己的文件传输助手）</li>
     * </ul>
     */
    public Object messageSticker(String accountId, Map<String, Object> args) {
        return call("POST", "/accounts/" + accountId + "/messages/sticker", null, take(args, "to", "checksum", "length"));
    }

    /**
     * 发链接卡片 —— 发一张可点击的链接卡片。
     * <p>POST /v1/accounts/{account_id}/messages/link
     * <p>args 里可以放：
     * <ul>
     * <li>title —— 必填 卡片标题</li>
     * <li>to —— 必填 接收者：wxid、群 ID，或 filehelper（自己的文件传输助手）</li>
     * <li>url —— 必填 点击后打开的地址</li>
     * <li>description —— 可选 卡片摘要</li>
     * <li>source_name —— 可选 来源名称</li>
     * <li>thumb_url —— 可选 封面图地址</li>
     * </ul>
     */
    public Object messageLink(String accountId, Map<String, Object> args) {
        return call("POST", "/accounts/" + accountId + "/messages/link", null, take(args, "to", "title", "description", "url", "thumb_url", "source_name"));
    }

    /**
     * 发小程序卡片 —— 发一张小程序卡片。需要小程序自己的标识。
     * <p>POST /v1/accounts/{account_id}/messages/miniapp
     * <p>args 里可以放：
     * <ul>
     * <li>app_id —— 必填 小程序的公开标识</li>
     * <li>title —— 必填 卡片标题</li>
     * <li>to —— 必填 接收者：wxid、群 ID，或 filehelper（自己的文件传输助手）</li>
     * <li>username —— 必填 小程序的原始 ID</li>
     * <li>description —— 可选 卡片摘要</li>
     * <li>path —— 可选 打开的页面路径</li>
     * <li>source_name —— 可选 来源名称</li>
     * <li>thumb_url —— 可选 封面图地址</li>
     * </ul>
     */
    public Object messageMiniapp(String accountId, Map<String, Object> args) {
        return call("POST", "/accounts/" + accountId + "/messages/miniapp", null, take(args, "to", "app_id", "username", "title", "description", "path", "thumb_url", "source_name"));
    }

    /**
     * 转发消息 —— 把收到过的一条消息原样转给别人。
     * <p>POST /v1/accounts/{account_id}/messages/forward
     * <p>args 里可以放：
     * <ul>
     * <li>message_id —— 必填 要转发的消息 ID</li>
     * <li>to —— 必填 接收者：wxid、群 ID，或 filehelper（自己的文件传输助手）</li>
     * </ul>
     */
    public Object messageForward(String accountId, Map<String, Object> args) {
        return call("POST", "/accounts/" + accountId + "/messages/forward", null, take(args, "to", "message_id"));
    }

    /**
     * 撤回消息 —— 撤回自己发出的一条消息。微信只允许发出后约两分钟内撤回，超时会被拒绝。
     * <p>POST /v1/accounts/{account_id}/messages/{message_id}/recall
     */
    public Object messageRecall(String accountId, String messageId) {
        return call("POST", "/accounts/" + accountId + "/messages/" + messageId + "/recall", null, null);
    }

    /**
     * 消息记录 —— 读平台保存的消息记录，可按会话筛选。
     * <p>GET /v1/accounts/{account_id}/messages
     * <p>args 里可以放：
     * <ul>
     * <li>cursor —— 可选 上一页返回的 next_cursor，首页留空</li>
     * <li>limit —— 可选 每页条数，最多 200，超过按 200 处理</li>
     * <li>peer —— 可选 只看与某个 wxid 或群的会话</li>
     * </ul>
     */
    public Object messageHistory(String accountId, Map<String, Object> args) {
        return call("GET", "/accounts/" + accountId + "/messages", take(args, "peer", "cursor", "limit"), null);
    }

    /**
     * 同步消息 —— 主动拉取这个实例收到的消息，内容和 Webhook 推的完全一样。没配 Webhook、Webhook 断过、或者服务重启过，用它把这段时间的消息补回来。cursor 留空从最早还留着的消息开始（大约一天），之后每次带上一次返回的 next_cursor；has_more 为 true 说明还没拉完，立刻再调一次。没有新消息时 next_cursor 原样返回，游标不动。拉到的消息不入库、不触发 Webhook，重复拉不会有副作用。
     * <p>POST /v1/accounts/{account_id}/messages/sync
     * <p>args 里可以放：
     * <ul>
     * <li>cursor —— 可选 上一次返回的 next_cursor，第一次留空</li>
     * </ul>
     */
    public Object messageSync(String accountId, Map<String, Object> args) {
        return call("POST", "/accounts/" + accountId + "/messages/sync", null, take(args, "cursor"));
    }

    /**
     * 消息详情 —— 读一条消息。
     * <p>GET /v1/accounts/{account_id}/messages/{message_id}
     */
    public Object messageGet(String accountId, String messageId) {
        return call("GET", "/accounts/" + accountId + "/messages/" + messageId, null, null);
    }

    /**
     * 收藏列表 —— 列出这个实例收藏的内容。cursor 留空从头读，返回的 next_cursor 为空表示到底。
     * <p>GET /v1/accounts/{account_id}/favorites
     * <p>args 里可以放：
     * <ul>
     * <li>cursor —— 可选 上一页返回的 next_cursor，首页留空</li>
     * </ul>
     */
    public Object favoriteList(String accountId, Map<String, Object> args) {
        return call("GET", "/accounts/" + accountId + "/favorites", take(args, "cursor"), null);
    }

    /**
     * 收藏详情 —— 读一条收藏的完整内容。内容是微信自己的 XML，不同类型结构不同，原样返回。
     * <p>GET /v1/accounts/{account_id}/favorites/{fav_id}
     */
    public Object favoriteGet(String accountId, String favId) {
        return call("GET", "/accounts/" + accountId + "/favorites/" + favId, null, null);
    }

    /**
     * 删除收藏 —— 删掉一条收藏。
     * <p>DELETE /v1/accounts/{account_id}/favorites/{fav_id}
     */
    public Object favoriteDelete(String accountId, String favId) {
        return call("DELETE", "/accounts/" + accountId + "/favorites/" + favId, null, null);
    }


    // --- 媒体 ---

    /**
     * 上传文件 —— 把文件直接传上来，换一个 media_id，之后发图片、视频、语音、文件都可以只给这个 ID。适合文件在你自己机器上、没有公网地址可给的情况 —— 比如程序刚生成的一张图。用 multipart/form-data 提交，文件放在 file 字段里，最大 20 MB。第一次发送时这个文件才真正上传到微信，之后再用同一个 ID 发就不再重传了。没有发送过的上传保留 24 小时。
     * <p>POST /v1/accounts/{account_id}/media/upload
     * <p>args 里可以放：
     * <ul>
     * <li>file —— 必填 要上传的文件，multipart/form-data</li>
     * <li>kind —— 可选 这个文件打算当什么发，不填按类型自动判断（image / video / voice / file）</li>
     * </ul>
     */
    public Object mediaUpload(String accountId, Map<String, Object> args) {
        byte[] content = (byte[]) args.get("file");
        Object named = args.get("filename");
        return upload("/accounts/" + accountId + "/media/upload", named == null ? null : String.valueOf(named),
                content, take(args, "kind"));
    }

    /**
     * 下载消息附件 —— 取一条消息里的图片、视频、文件或语音，返回一个限时下载地址。
     * <p>POST /v1/accounts/{account_id}/media/download
     * <p>args 里可以放：
     * <ul>
     * <li>message_id —— 必填 带附件的消息 ID</li>
     * </ul>
     */
    public Object mediaFromMessage(String accountId, Map<String, Object> args) {
        return call("POST", "/accounts/" + accountId + "/media/download", null, take(args, "message_id"));
    }

    /**
     * 查文件是否已缓存 —— 问一个地址平台是否已经发过。发过就能直接转发，不用重新上传，也不算流量 —— 在你把文件准备好挂到公网之前先问一句，省的就是这一趟。
     * <p>POST /v1/accounts/{account_id}/media/cached
     * <p>args 里可以放：
     * <ul>
     * <li>kind —— 必填 image、video 或 file</li>
     * <li>url —— 必填 要发的那个地址，和发送时填的一模一样才算命中</li>
     * </ul>
     */
    public Object mediaCached(String accountId, Map<String, Object> args) {
        return call("POST", "/accounts/" + accountId + "/media/cached", null, take(args, "url", "kind"));
    }

    /**
     * 重新取下载地址 —— 为已经下载过的文件换一个新的限时地址。
     * <p>GET /v1/accounts/{account_id}/media/{media_id}
     */
    public Object mediaGet(String accountId, String mediaId) {
        return call("GET", "/accounts/" + accountId + "/media/" + mediaId, null, null);
    }

    /**
     * 下载动态媒体 —— 取一条朋友圈动态里的第 N 张图，或它的视频。
     * <p>POST /v1/accounts/{account_id}/moments/{moment_id}/media/download
     * <p>args 里可以放：
     * <ul>
     * <li>index —— 可选 第几张图，从 0 开始；视频动态忽略这个值</li>
     * </ul>
     */
    public Object mediaMoment(String accountId, String momentId, Map<String, Object> args) {
        return call("POST", "/accounts/" + accountId + "/moments/" + momentId + "/media/download", null, take(args, "index"));
    }


    // --- 朋友圈 ---

    /**
     * 我的朋友圈 —— 读自己看到的朋友圈时间线。
     * <p>GET /v1/accounts/{account_id}/moments
     * <p>args 里可以放：
     * <ul>
     * <li>cursor —— 可选 上一页返回的 next_cursor</li>
     * </ul>
     */
    public Object momentTimeline(String accountId, Map<String, Object> args) {
        return call("GET", "/accounts/" + accountId + "/moments", take(args, "cursor"), null);
    }

    /**
     * 朋友圈详情 —— 读一条朋友圈。列表会截断点赞与评论，这里是完整的。
     * <p>GET /v1/accounts/{account_id}/moments/{moment_id}
     */
    public Object momentGet(String accountId, String momentId) {
        return call("GET", "/accounts/" + accountId + "/moments/" + momentId, null, null);
    }

    /**
     * 某人的朋友圈 —— 读某个联系人的朋友圈主页。
     * <p>GET /v1/accounts/{account_id}/moments/user/{wxid}
     * <p>args 里可以放：
     * <ul>
     * <li>cursor —— 可选 上一页返回的 next_cursor</li>
     * </ul>
     */
    public Object momentUser(String accountId, String wxid, Map<String, Object> args) {
        return call("GET", "/accounts/" + accountId + "/moments/user/" + wxid, take(args, "cursor"), null);
    }

    /**
     * 发文字动态 —— 发一条纯文字朋友圈。
     * <p>POST /v1/accounts/{account_id}/moments/text
     * <p>args 里可以放：
     * <ul>
     * <li>content —— 必填 正文</li>
     * <li>mentions —— 可选 要 @ 的 wxid</li>
     * <li>visibility —— 可选 可见范围：mode 为 public / private / allow / deny，后两种要给 wxids 或 tag_ids</li>
     * </ul>
     */
    public Object momentPostText(String accountId, Map<String, Object> args) {
        return call("POST", "/accounts/" + accountId + "/moments/text", null, take(args, "content", "mentions", "visibility"));
    }

    /**
     * 发图片动态 —— 发一条带图的朋友圈。
     * <p>POST /v1/accounts/{account_id}/moments/images
     * <p>args 里可以放：
     * <ul>
     * <li>images —— 必填 图片列表</li>
     * <li>content —— 可选 正文</li>
     * <li>visibility —— 可选 可见范围：mode 为 public / private / allow / deny，后两种要给 wxids 或 tag_ids</li>
     * </ul>
     */
    public Object momentPostImages(String accountId, Map<String, Object> args) {
        return call("POST", "/accounts/" + accountId + "/moments/images", null, take(args, "content", "images", "visibility"));
    }

    /**
     * 发视频动态 —— 发一条视频朋友圈。
     * <p>POST /v1/accounts/{account_id}/moments/video
     * <p>args 里可以放：
     * <ul>
     * <li>video —— 必填 视频，给一个公网可下载的地址</li>
     * <li>content —— 可选 正文</li>
     * <li>cover —— 可选 封面图</li>
     * <li>duration —— 可选 时长（秒）</li>
     * <li>visibility —— 可选 可见范围：mode 为 public / private / allow / deny，后两种要给 wxids 或 tag_ids</li>
     * </ul>
     */
    public Object momentPostVideo(String accountId, Map<String, Object> args) {
        return call("POST", "/accounts/" + accountId + "/moments/video", null, take(args, "content", "video", "cover", "duration", "visibility"));
    }

    /**
     * 转发动态 —— 把看到的一条动态原样再发一遍。
     * <p>POST /v1/accounts/{account_id}/moments/forward
     * <p>args 里可以放：
     * <ul>
     * <li>moment_id —— 必填 要转发的动态 ID</li>
     * <li>visibility —— 可选 可见范围：mode 为 public / private / allow / deny，后两种要给 wxids 或 tag_ids</li>
     * </ul>
     */
    public Object momentRepost(String accountId, Map<String, Object> args) {
        return call("POST", "/accounts/" + accountId + "/moments/forward", null, take(args, "moment_id", "visibility"));
    }

    /**
     * 点赞 —— 给一条动态点赞。动态要先读过一次，24 小时内有效。
     * <p>POST /v1/accounts/{account_id}/moments/{moment_id}/like
     */
    public Object momentLike(String accountId, String momentId) {
        return call("POST", "/accounts/" + accountId + "/moments/" + momentId + "/like", null, null);
    }

    /**
     * 取消赞 —— 取消对一条动态的赞。
     * <p>DELETE /v1/accounts/{account_id}/moments/{moment_id}/like
     */
    public Object momentUnlike(String accountId, String momentId) {
        return call("DELETE", "/accounts/" + accountId + "/moments/" + momentId + "/like", null, null);
    }

    /**
     * 评论 —— 评论一条动态，或回复别人的评论。
     * <p>POST /v1/accounts/{account_id}/moments/{moment_id}/comments
     * <p>args 里可以放：
     * <ul>
     * <li>content —— 必填 评论内容，最多 500 字</li>
     * <li>reply_to —— 可选 要回复的评论 ID，留空为一级评论</li>
     * </ul>
     */
    public Object momentComment(String accountId, String momentId, Map<String, Object> args) {
        return call("POST", "/accounts/" + accountId + "/moments/" + momentId + "/comments", null, take(args, "content", "reply_to"));
    }

    /**
     * 删除评论 —— 删掉自己发的一条评论。
     * <p>DELETE /v1/accounts/{account_id}/moments/{moment_id}/comments/{comment_id}
     */
    public Object momentDeleteComment(String accountId, String momentId, String commentId) {
        return call("DELETE", "/accounts/" + accountId + "/moments/" + momentId + "/comments/" + commentId, null, null);
    }

    /**
     * 删除动态 —— 删掉自己发的一条朋友圈。
     * <p>DELETE /v1/accounts/{account_id}/moments/{moment_id}
     */
    public Object momentDelete(String accountId, String momentId) {
        return call("DELETE", "/accounts/" + accountId + "/moments/" + momentId, null, null);
    }

    /**
     * 设为私密 / 公开 —— 把自己的一条动态设为仅自己可见，或改回公开。
     * <p>PUT /v1/accounts/{account_id}/moments/{moment_id}/privacy
     * <p>args 里可以放：
     * <ul>
     * <li>private —— 必填 true 为仅自己可见</li>
     * </ul>
     */
    public Object momentPrivacy(String accountId, String momentId, Map<String, Object> args) {
        return call("PUT", "/accounts/" + accountId + "/moments/" + momentId + "/privacy", null, take(args, "private"));
    }


    // --- 平台 ---

    /**
     * 当前用户 —— 读这个 Key 属于谁，以及实例数量。
     * <p>GET /v1/me
     */
    public Object platformMe() {
        return call("GET", "/me", null, null);
    }

    /**
     * 事件列表 —— 读平台记录的事件，可按实例、类型、消息类型与时间筛选。没配 Webhook 时可以轮询这里。
     * <p>GET /v1/events
     * <p>args 里可以放：
     * <ul>
     * <li>account_id —— 可选 只看某个实例</li>
     * <li>cursor —— 可选 上一页返回的 next_cursor，首页留空</li>
     * <li>keyword —— 可选 按事件内容搜索。需要同时给时间范围，且不超过 1 小时</li>
     * <li>limit —— 可选 每页条数，最多 200，超过按 200 处理</li>
     * <li>message_type —— 可选 只看某种消息，如 text、image、file，可重复；非消息事件不会命中</li>
     * <li>order —— 可选 oldest 从头逐条读（默认），newest 先看最近发生的（oldest / newest）</li>
     * <li>since —— 可选 只看这个时间之后的，RFC3339 或 Unix 秒</li>
     * <li>type —— 可选 只看某种事件，可重复</li>
     * <li>until —— 可选 只看这个时间之前的，RFC3339 或 Unix 秒</li>
     * </ul>
     */
    public Object platformEvents(Map<String, Object> args) {
        return call("GET", "/events", take(args, "account_id", "type", "message_type", "since", "until", "keyword", "order", "cursor", "limit"), null);
    }

    /**
     * 事件流 —— 以 SSE 长连接实时接收该实例的事件，内容与 Webhook 相同。
     * <p>GET /v1/accounts/{account_id}/stream
     */
    public Object platformStream(String accountId) {
        return call("GET", "/accounts/" + accountId + "/stream", null, null);
    }


    /** 够用就好的 JSON 读写，省掉一个依赖。 */
    static final class Json {

        static String write(Object value) {
            StringBuilder out = new StringBuilder();
            writeTo(value, out);
            return out.toString();
        }

        private static void writeTo(Object value, StringBuilder out) {
            if (value == null) {
                out.append("null");
            } else if (value instanceof String) {
                quote((String) value, out);
            } else if (value instanceof Number || value instanceof Boolean) {
                out.append(value);
            } else if (value instanceof Map) {
                out.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    quote(String.valueOf(entry.getKey()), out);
                    out.append(':');
                    writeTo(entry.getValue(), out);
                }
                out.append('}');
            } else if (value instanceof Iterable) {
                out.append('[');
                boolean first = true;
                for (Object item : (Iterable<?>) value) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    writeTo(item, out);
                }
                out.append(']');
            } else if (value instanceof Object[]) {
                writeTo(List.of((Object[]) value), out);
            } else {
                quote(String.valueOf(value), out);
            }
        }

        private static void quote(String s, StringBuilder out) {
            out.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"': out.append("\\\""); break;
                    case '\\': out.append("\\\\"); break;
                    case '\n': out.append("\\n"); break;
                    case '\r': out.append("\\r"); break;
                    case '\t': out.append("\\t"); break;
                    default:
                        if (c < 0x20) {
                            out.append(String.format("\\u%04x", (int) c));
                        } else {
                            out.append(c);
                        }
                }
            }
            out.append('"');
        }

        static Object read(String text) {
            Reader reader = new Reader(text);
            reader.skipSpace();
            Object value = reader.value();
            reader.skipSpace();
            if (!reader.done()) {
                throw new IllegalArgumentException("JSON 后面还有多余的内容");
            }
            return value;
        }

        private static final class Reader {
            private final String s;
            private int i;

            Reader(String s) {
                this.s = s;
            }

            boolean done() {
                return i >= s.length();
            }

            void skipSpace() {
                while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                    i++;
                }
            }

            Object value() {
                skipSpace();
                if (done()) {
                    throw new IllegalArgumentException("JSON 不完整");
                }
                char c = s.charAt(i);
                switch (c) {
                    case '{': return object();
                    case '[': return array();
                    case '"': return string();
                    case 't': expect("true"); return Boolean.TRUE;
                    case 'f': expect("false"); return Boolean.FALSE;
                    case 'n': expect("null"); return null;
                    default: return number();
                }
            }

            private void expect(String word) {
                if (!s.startsWith(word, i)) {
                    throw new IllegalArgumentException("第 " + i + " 个字符处不认识");
                }
                i += word.length();
            }

            private Map<String, Object> object() {
                Map<String, Object> out = new LinkedHashMap<>();
                i++;
                skipSpace();
                if (i < s.length() && s.charAt(i) == '}') {
                    i++;
                    return out;
                }
                while (true) {
                    skipSpace();
                    String key = string();
                    skipSpace();
                    if (s.charAt(i) != ':') {
                        throw new IllegalArgumentException("第 " + i + " 个字符处缺少冒号");
                    }
                    i++;
                    out.put(key, value());
                    skipSpace();
                    char c = s.charAt(i++);
                    if (c == '}') {
                        return out;
                    }
                    if (c != ',') {
                        throw new IllegalArgumentException("第 " + (i - 1) + " 个字符处缺少逗号");
                    }
                }
            }

            private List<Object> array() {
                List<Object> out = new ArrayList<>();
                i++;
                skipSpace();
                if (i < s.length() && s.charAt(i) == ']') {
                    i++;
                    return out;
                }
                while (true) {
                    out.add(value());
                    skipSpace();
                    char c = s.charAt(i++);
                    if (c == ']') {
                        return out;
                    }
                    if (c != ',') {
                        throw new IllegalArgumentException("第 " + (i - 1) + " 个字符处缺少逗号");
                    }
                }
            }

            private String string() {
                if (s.charAt(i) != '"') {
                    throw new IllegalArgumentException("第 " + i + " 个字符处缺少引号");
                }
                i++;
                StringBuilder out = new StringBuilder();
                while (true) {
                    char c = s.charAt(i++);
                    if (c == '"') {
                        return out.toString();
                    }
                    if (c != '\\') {
                        out.append(c);
                        continue;
                    }
                    char esc = s.charAt(i++);
                    switch (esc) {
                        case 'n': out.append('\n'); break;
                        case 'r': out.append('\r'); break;
                        case 't': out.append('\t'); break;
                        case 'b': out.append('\b'); break;
                        case 'f': out.append('\f'); break;
                        case 'u':
                            out.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default: out.append(esc);
                    }
                }
            }

            private BigDecimal number() {
                int start = i;
                while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                    i++;
                }
                return new BigDecimal(s.substring(start, i));
            }
        }
    }
}
