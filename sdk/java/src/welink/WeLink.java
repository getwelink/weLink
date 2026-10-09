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
 * 接口方法按接口清单整理；请求、验签和事件流逻辑在本文件维护。
 */
public class WeLink {

    private final String apiKey;
    private final String baseUrl;
    private final HttpClient http;
    private final Duration timeout;

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
        this.timeout = timeout;
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
                Iterable<?> values = value instanceof Iterable ? (Iterable<?>) value : java.util.Collections.singletonList(value);
                for (Object one : values) {
                    if (one == null) continue;
                    if (q.length() > 0) q.append('&');
                    q.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)).append('=')
                            .append(URLEncoder.encode(String.valueOf(one), StandardCharsets.UTF_8));
                }
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
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .method(method, payload);
        if (hasBody) {
            builder.header("Content-Type", "application/json");
        }

        HttpResponse<String> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WeLinkException(0, "请求已中断", "", 0);
        } catch (IOException e) {
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
        if (!(envelope.get("code") instanceof BigDecimal)) {
            throw new WeLinkException(0, "服务返回的不是预期的结构", "", response.statusCode());
        }
        int code;
        try { code = ((BigDecimal) envelope.get("code")).intValueExact(); }
        catch (ArithmeticException e) { throw new WeLinkException(0, "响应错误码不是整数", "", response.statusCode()); }
        if (response.statusCode() < 200 || response.statusCode() >= 300 || code != 0) {
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
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(buf.toByteArray()))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WeLinkException(0, "请求已中断", "", 0);
        } catch (IOException e) {
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
        if (!(envelope.get("code") instanceof BigDecimal)) {
            throw new WeLinkException(0, "服务返回的不是预期的结构", "", response.statusCode());
        }
        int code;
        try { code = ((BigDecimal) envelope.get("code")).intValueExact(); }
        catch (ArithmeticException e) { throw new WeLinkException(0, "响应错误码不是整数", "", response.statusCode()); }
        if (response.statusCode() < 200 || response.statusCode() >= 300 || code != 0) {
            throw new WeLinkException(code, String.valueOf(envelope.get("message")),
                    String.valueOf(envelope.getOrDefault("request_id", "")), response.statusCode());
        }
        return envelope.get("data");
    }

    private static String pathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
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
     * 创建实例 —— 创建一个实例。每个实例占用一个额度，删除实例后额度归还。实例创建后需要扫码登录才会上线。
     * <p>POST /v1/accounts
     * <p>args 里可以放：
     * <ul>
     * <li>proxy —— 必填 代理网络，必填，不能直连。有两种填法：socks5 代理地址，如 socks5://user:pass@host:port；网络助手的网络ID，在一台手机上安装并打开网络助手即可看到，实例将通过这台手机的网络连接微信。…</li>
     * <li>keep_history —— 可选 是否保存收发的消息和推送记录，默认 true。设为 false 时，消息不写入数据库，推送记录在投递结束后立即删除。图片等文件仍可下载，撤回功能仍可使用；但无法查询历史消息，也无法转发文字和卡片消息</li>
     * <li>name —— 可选 备注名称，仅自己可见</li>
     * <li>platform —— 可选 登录方式，留空时使用默认方式。并非每个部署都同时开通了两种方式。选择未开通的方式会直接报错，错误信息中会列出可选的方式（ipad / mac）</li>
     * <li>webhook_url —— 可选 接收该实例事件的 Webhook 地址</li>
     * </ul>
     */
    public Object accountCreate(Map<String, Object> args) {
        return call("POST", "/accounts", null, take(args, "platform", "name", "proxy", "webhook_url", "keep_history"));
    }

    /**
     * 实例列表 —— 列出你的全部实例及其状态。
     * <p>GET /v1/accounts
     */
    public Object accountList() {
        return call("GET", "/accounts", null, null);
    }

    /**
     * 实例详情 —— 查询一个实例的详情。实例不在线时，reason 字段说明原因：manual 表示主动退出，kicked 表示因其他设备登录而被挤下线，relogin_required 表示需要重新扫码登录，recover_timeout 表示自动恢复超时，expired 表示授权到期。status 为 recovering 时，recovering 字段给出恢复方式和放弃恢复的时间。
     * <p>GET /v1/accounts/{account_id}
     */
    public Object accountGet(String accountId) {
        return call("GET", "/accounts/" + pathSegment(accountId), null, null);
    }

    /**
     * 获取登录二维码 —— 获取一张登录二维码，用手机微信扫码登录。expires_in 是二维码剩余的有效秒数，请以返回值为准，不要写死。二维码过期后重新获取即可。请求时可以带上 proxy 来更换代理网络。代理网络在建立登录会话时确定，更换后需要重新建立会话，所以只能在获取二维码时更换。不传 proxy 则沿用原来的代理网络。
     * <p>POST /v1/accounts/{account_id}/login/qrcode
     * <p>args 里可以放：
     * <ul>
     * <li>proxy —— 可选 要改用的代理网络，可以是socks5 地址或网络助手的网络ID。不传则沿用实例现有的代理网络。不能传空字符串，因为实例必须配置代理网络，不允许直连</li>
     * </ul>
     */
    public Object accountQrcode(String accountId, Map<String, Object> args) {
        return call("POST", "/accounts/" + pathSegment(accountId) + "/login/qrcode", null, take(args, "proxy"));
    }

    /**
     * 登录状态 —— 查询扫码登录的进度，供轮询使用。状态取值：waiting（等待扫码）、scanned（已扫码，等待确认）、verify（等待验证）、online（已上线）、cancelled（已取消）、expired（已过期）。状态为 waiting 时还会返回 expires_in，表示二维码此刻剩余的有效秒数，可用于校准倒计时。返回 notice 时，请把它原样展示给用户。Mac 端扫码后需要通过一次新设备验证，平台会自动完成这一步，这期间状态会一直保持为 scanned。请提示用户耐心等待，避免用户误以为登录卡住而取消登录。
     * <p>GET /v1/accounts/{account_id}/login/status
     */
    public Object accountLoginStatus(String accountId) {
        return call("GET", "/accounts/" + pathSegment(accountId) + "/login/status", null, null);
    }

    /**
     * 重新连接 —— 实例掉线后，尝试在不重新扫码的情况下恢复连接。无法恢复时，才需要重新扫码登录。
     * <p>POST /v1/accounts/{account_id}/reconnect
     */
    public Object accountReconnect(String accountId) {
        return call("POST", "/accounts/" + pathSegment(accountId) + "/reconnect", null, null);
    }

    /**
     * 退出登录 —— 让实例下线。实例和额度都会保留，之后可以重新扫码上线。
     * <p>POST /v1/accounts/{account_id}/logout
     */
    public Object accountLogout(String accountId) {
        return call("POST", "/accounts/" + pathSegment(accountId) + "/logout", null, null);
    }

    /**
     * 删除实例 —— 删除实例并归还额度。历史消息不会立即清除。在线的实例不能直接删除，请先调用「退出登录」。如果直接删除，微信端的登录会话会继续保持，而平台已经无法再关闭它。
     * <p>DELETE /v1/accounts/{account_id}
     */
    public Object accountDelete(String accountId) {
        return call("DELETE", "/accounts/" + pathSegment(accountId), null, null);
    }

    /**
     * 实例资料 —— 查询这个实例自己的昵称、头像、地区等资料。
     * <p>建议缓存：资料很少变化，登录成功后获取一次并保存即可。平时需要 wxid、昵称、头像时，请读取「实例详情」中的 profile 字段。该字段来自平台已保存的数据，不会向微信发起请求。
     * <p>GET /v1/accounts/{account_id}/profile
     */
    public Object accountProfile(String accountId) {
        return call("GET", "/accounts/" + pathSegment(accountId) + "/profile", null, null);
    }

    /**
     * 修改个人资料 —— 修改昵称、签名、性别和地区。留空的字段会被清空，请把需要保留的字段一并传入。
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
        return call("PUT", "/accounts/" + pathSegment(accountId) + "/profile", null, take(args, "nickname", "signature", "sex", "country", "province", "city"));
    }

    /**
     * 修改头像 —— 修改头像。
     * <p>PUT /v1/accounts/{account_id}/profile/avatar
     * <p>args 里可以放：
     * <ul>
     * <li>url —— 必填 公网可下载的图片地址</li>
     * </ul>
     */
    public Object accountSetAvatar(String accountId, Map<String, Object> args) {
        return call("PUT", "/accounts/" + pathSegment(accountId) + "/profile/avatar", null, take(args, "url"));
    }

    /**
     * 我的二维码 —— 获取这个实例自己的名片二维码。返回 data URL，可直接用作 img 标签的 src。
     * <p>建议缓存：名片二维码基本不会变化。获取一次后保存为图片重复使用，不要每次展示时都重新获取。
     * <p>GET /v1/accounts/{account_id}/profile/qrcode
     */
    public Object accountQrcodeSelf(String accountId) {
        return call("GET", "/accounts/" + pathSegment(accountId) + "/profile/qrcode", null, null);
    }

    /**
     * 隐私设置 —— 开启或关闭一项隐私设置。
     * <p>PUT /v1/accounts/{account_id}/privacy
     * <p>args 里可以放：
     * <ul>
     * <li>enabled —— 必填 true 开启，false 关闭</li>
     * <li>option —— 必填 need_confirm_to_add：加我为好友时需要验证；findable_by_phone：可以通过手机号搜到我；findable_by_alias：可以通过微信号搜到我；recommend_contacts：向…（need_confirm_to_add / findable_by_phone / findable_by_alias / recommend_contacts / strangers_see_ten / visible_days）</li>
     * </ul>
     */
    public Object accountPrivacy(String accountId, Map<String, Object> args) {
        return call("PUT", "/accounts/" + pathSegment(accountId) + "/privacy", null, take(args, "option", "enabled"));
    }

    /**
     * 已登录设备 —— 列出这个微信号登录过的设备，其中包括本平台。
     * <p>GET /v1/accounts/{account_id}/devices
     */
    public Object accountDevices(String accountId) {
        return call("GET", "/accounts/" + pathSegment(accountId) + "/devices", null, null);
    }

    /**
     * 下线某个设备 —— 让某个已登录的设备强制下线。注意不要把本平台自己也下线。
     * <p>DELETE /v1/accounts/{account_id}/devices/{device_id}
     */
    public Object accountDeviceSignout(String accountId, String deviceId) {
        return call("DELETE", "/accounts/" + pathSegment(accountId) + "/devices/" + pathSegment(deviceId), null, null);
    }

    /**
     * 消息保存设置 —— 设置这个实例是否保存收发的消息和推送记录。关闭后，新收发的消息不写入数据库，Webhook 推送记录在投递成功或放弃重试后立即删除。图片、语音、视频、文件仍然可以下载，自己发的消息仍然可以撤回，重复的推送仍然会去重。但无法查询历史消息，也无法转发文字和卡片消息。事件仍然会保存。关闭前已保存的消息不会立即删除，会按原来的保存期限自动清理。
     * <p>PUT /v1/accounts/{account_id}/history
     * <p>args 里可以放：
     * <ul>
     * <li>keep —— 必填 true 保存，false 不保存</li>
     * </ul>
     */
    public Object accountHistory(String accountId, Map<String, Object> args) {
        return call("PUT", "/accounts/" + pathSegment(accountId) + "/history", null, take(args, "keep"));
    }

    /**
     * 设置 Webhook —— 设置接收该实例事件的 Webhook 地址。每次推送都带有签名，可以用 secret 校验。
     * <p>PUT /v1/accounts/{account_id}/webhook
     * <p>args 里可以放：
     * <ul>
     * <li>url —— 必填 接收事件的地址</li>
     * <li>events —— 可选 只推送这些类型的事件，留空则推送全部事件</li>
     * <li>secret —— 可选 签名密钥，留空则保持不变</li>
     * </ul>
     */
    public Object accountWebhook(String accountId, Map<String, Object> args) {
        return call("PUT", "/accounts/" + pathSegment(accountId) + "/webhook", null, take(args, "url", "secret", "events"));
    }


    // --- 联系人 ---

    /**
     * 通讯录列表 —— 列出通讯录中的全部条目，不做任何筛选，只返回标识：好友为 wxid，群 ID 以 @chatroom 结尾，公众号以 gh_ 开头。需要资料时，再用「联系人详情」按需查询。数据直接从微信获取，实例需要在线。每页条数由微信决定。翻页时把 next_cursor 原样传回，next_cursor 为空表示已经到最后一页。
     * <p>建议缓存：登录成功后拉一次完整列表，保存在你自己的系统中，之后根据事件更新：收到 friend.added 时添加新好友，收到 contact.updated 时更新联系人资料，收到 contact.deleted 时移除联系人。不要定时整份重拉：每次调用都会从微信拉取完整列表，联系人多时耗时长、开销大，频繁拉取还会增加被微信风控的概率。
     * <p>GET /v1/accounts/{account_id}/contacts
     * <p>args 里可以放：
     * <ul>
     * <li>cursor —— 可选 上一页返回的 next_cursor，首页留空</li>
     * </ul>
     */
    public Object contactIds(String accountId, Map<String, Object> args) {
        return call("GET", "/accounts/" + pathSegment(accountId) + "/contacts", take(args, "cursor"), null);
    }

    /**
     * 批量取详情 —— 按 wxid 批量查询联系人资料。它和「联系人详情」调用的是微信的两个不同接口，一次查询很多人时，更适合用这个接口。 对个人好友，还会返回加好友的时间和方式：added_at、added_ts 是添加时间；add_source 是微信记录的添加方式编号，add_source_text 是它的中文说明，比如「扫一扫」「群聊」「搜索手机号」「名片分享」。含义还没有确认的编号只返回 add_source，不返回中文说明。通过群聊加的好友，add_source_group 是来源群的 ID。微信没有记录的项不返回。群和公众号不返回这几个字段。 所有联系人还会返回：avatar_large 高清头像（…
     * <p>建议缓存：按 wxid 保存查到的资料，收到 contact.updated 事件时再更新对应联系人的资料。不要每收到一条消息就查询一次发送人的资料。
     * <p>POST /v1/accounts/{account_id}/contacts/batch
     * <p>args 里可以放：
     * <ul>
     * <li>wxids —— 必填 要查询的 wxid 列表</li>
     * </ul>
     */
    public Object contactBatch(String accountId, Map<String, Object> args) {
        return call("POST", "/accounts/" + pathSegment(accountId) + "/contacts/batch", null, take(args, "wxids"));
    }

    /**
     * 联系人详情 —— 查询联系人的完整资料，包括昵称、备注、微信号、头像、性别、地区、签名，以及该联系人的标签。标签在 label_ids 字段中，对应「标签列表」里的 ID；联系人没有标签时不返回这个字段。个人好友还会返回加好友的方式 add_source 和 add_source_text，通过群聊加的好友还有来源群 add_source_group；加好友的时间只有「批量取详情」能查到，这里不返回。高清头像 avatar_large、拼音 pinyin、备注电话 phones 和「批量取详情」一样返回。
     * <p>建议缓存：按 wxid 保存查到的资料，收到 contact.updated 事件时再更新对应联系人的资料。不要每收到一条消息就查询一次发送人的资料。
     * <p>POST /v1/accounts/{account_id}/contacts/detail
     * <p>args 里可以放：
     * <ul>
     * <li>wxids —— 必填 要查询的 wxid，一次最多 50 个</li>
     * </ul>
     */
    public Object contactDetail(String accountId, Map<String, Object> args) {
        return call("POST", "/accounts/" + pathSegment(accountId) + "/contacts/detail", null, take(args, "wxids"));
    }

    /**
     * 检测好友关系 —— 检测这些人是否仍是你的好友。注意：微信对这个操作限制很严，一次检测的人数多或检测频繁，都可能导致实例被限制。一次最多检测 20 个，请按需使用。
     * <p>建议缓存：保存检测结果，同一个人在短时间内不要重复检测。
     * <p>POST /v1/accounts/{account_id}/contacts/check
     * <p>args 里可以放：
     * <ul>
     * <li>wxids —— 必填 要检测的 wxid，一次最多 20 个</li>
     * </ul>
     */
    public Object contactCheck(String accountId, Map<String, Object> args) {
        return call("POST", "/accounts/" + pathSegment(accountId) + "/contacts/check", null, take(args, "wxids"));
    }

    /**
     * 企微联系人 —— 查询企业微信的外部联系人。这些联系人不在普通通讯录中，「通讯录列表」接口查不到他们。本接口返回平台已保存的数据，使用前请先调用一次「同步企微联系人」。
     * <p>GET /v1/accounts/{account_id}/contacts/external
     */
    public Object contactExternal(String accountId) {
        return call("GET", "/accounts/" + pathSegment(accountId) + "/contacts/external", null, null);
    }

    /**
     * 同步企微联系人 —— 从微信重新拉取企业微信的外部联系人并保存到平台，返回拉取到的人数。没有头像的联系人会逐个补充获取头像，人数多时耗时较长，不建议频繁调用。
     * <p>POST /v1/accounts/{account_id}/contacts/external/sync
     */
    public Object contactExternalSync(String accountId) {
        return call("POST", "/accounts/" + pathSegment(accountId) + "/contacts/external/sync", null, null);
    }

    /**
     * 搜索用户 —— 按微信号或手机号搜索用户，返回可用于添加好友的 contact_token。
     * <p>建议缓存：保存搜索到的 wxid 和昵称，不要反复搜索同一个号。搜索过于频繁时，微信会提示操作过于频繁，之后一段时间内都无法搜索。contact_token 会过期，真正要添加好友时，再搜索一次获取新的 contact_token。
     * <p>POST /v1/accounts/{account_id}/contacts/search
     * <p>args 里可以放：
     * <ul>
     * <li>keyword —— 必填 微信号或手机号</li>
     * </ul>
     */
    public Object contactSearch(String accountId, Map<String, Object> args) {
        return call("POST", "/accounts/" + pathSegment(accountId) + "/contacts/search", null, take(args, "keyword"));
    }

    /**
     * 添加好友 —— 用搜索得到的 contact_token 发起好友申请。**这个接口响应较慢**：微信需要 5～20 秒才返回结果，实测平均 9 秒，最慢 16 秒。客户端超时时间请至少设为 30 秒。请求超时后不要直接重发，因为请求很可能已经发送成功。需要重试时，请带上 Idempotency-Key。
     * <p>注意（易封号）：敏感接口，调用不当容易被微信限制甚至封号。添加好友是微信风控最严格的操作之一。不要在短时间内连续添加，不要批量自动加人，每次添加之间要留出间隔。新注册的号、刚换设备或刚登录的号风险更高，建议先正常使用几天再添加好友。
     * <p>POST /v1/accounts/{account_id}/contacts/add
     * <p>args 里可以放：
     * <ul>
     * <li>contact_token —— 必填 搜索结果中的 contact_token</li>
     * <li>greeting —— 可选 发给对方的验证消息</li>
     * <li>scene —— 可选 申请来源，留空则使用默认值</li>
     * </ul>
     */
    public Object contactAdd(String accountId, Map<String, Object> args) {
        return call("POST", "/accounts/" + pathSegment(accountId) + "/contacts/add", null, take(args, "contact_token", "greeting", "scene"));
    }

    /**
     * 通过好友申请 —— 通过他人的好友申请。需要传入好友申请事件中的 friend_request_token。
     * <p>注意（易封号）：敏感接口，调用不当容易被微信限制甚至封号。短时间内大量通过好友申请同样会触发风控。不要在收到申请后立即批量自动通过，每次通过之间要留出间隔；申请数量多时，请分散到不同时间段处理。
     * <p>POST /v1/accounts/{account_id}/contacts/accept
     * <p>args 里可以放：
     * <ul>
     * <li>friend_request_token —— 必填 好友申请事件中的 friend_request_token</li>
     * </ul>
     */
    public Object contactAccept(String accountId, Map<String, Object> args) {
        return call("POST", "/accounts/" + pathSegment(accountId) + "/contacts/accept", null, take(args, "friend_request_token"));
    }

    /**
     * 设置备注 —— 修改一个联系人的备注名。
     * <p>PUT /v1/accounts/{account_id}/contacts/{wxid}/remark
     * <p>args 里可以放：
     * <ul>
     * <li>remark —— 必填 新的备注名</li>
     * </ul>
     */
    public Object contactRemark(String accountId, String wxid, Map<String, Object> args) {
        return call("PUT", "/accounts/" + pathSegment(accountId) + "/contacts/" + pathSegment(wxid) + "/remark", null, take(args, "remark"));
    }

    /**
     * 删除好友 —— 将联系人从通讯录中删除。对方不会收到通知，但之后无法再给你发消息。如需恢复，需要重新添加好友。
     * <p>DELETE /v1/accounts/{account_id}/contacts/{wxid}
     */
    public Object contactDelete(String accountId, String wxid) {
        return call("DELETE", "/accounts/" + pathSegment(accountId) + "/contacts/" + pathSegment(wxid), null, null);
    }

    /**
     * 标签列表 —— 列出这个实例的联系人标签。标签仅自己可见。
     * <p>建议缓存：标签只有在你自己修改时才会变化。获取一次并保存，之后在新建、改名或删除标签后，再更新你保存的数据。
     * <p>GET /v1/accounts/{account_id}/labels
     */
    public Object labelList(String accountId) {
        return call("GET", "/accounts/" + pathSegment(accountId) + "/labels", null, null);
    }

    /**
     * 新建标签 —— 新建一个联系人标签，返回该标签的 label_id。
     * <p>POST /v1/accounts/{account_id}/labels
     * <p>args 里可以放：
     * <ul>
     * <li>name —— 必填 标签名</li>
     * </ul>
     */
    public Object labelAdd(String accountId, Map<String, Object> args) {
        return call("POST", "/accounts/" + pathSegment(accountId) + "/labels", null, take(args, "name"));
    }

    /**
     * 改标签名 —— 修改一个标签的名称。
     * <p>PUT /v1/accounts/{account_id}/labels/{label_id}
     * <p>args 里可以放：
     * <ul>
     * <li>name —— 必填 新的标签名</li>
     * </ul>
     */
    public Object labelRename(String accountId, String labelId, Map<String, Object> args) {
        return call("PUT", "/accounts/" + pathSegment(accountId) + "/labels/" + pathSegment(labelId), null, take(args, "name"));
    }

    /**
     * 删除标签 —— 删除一个标签。带有这个标签的联系人本身不受影响，只是不再带有该标签。
     * <p>DELETE /v1/accounts/{account_id}/labels/{label_id}
     */
    public Object labelDelete(String accountId, String labelId) {
        return call("DELETE", "/accounts/" + pathSegment(accountId) + "/labels/" + pathSegment(labelId), null, null);
    }

    /**
     * 设置联系人的标签 —— 为指定的联系人设置标签。设置采用覆盖方式：这些联系人原有的标签会全部替换为本次传入的标签。label_ids 传空数组表示移除他们的全部标签。不在 wxids 中的联系人不受影响。给某些联系人设置一个标签，不会把这个标签从其他联系人身上移除。
     * <p>PUT /v1/accounts/{account_id}/contacts/labels
     * <p>args 里可以放：
     * <ul>
     * <li>label_ids —— 必填 设置后这些联系人拥有的全部标签 ID，对应「标签列表」里的 ID。传空数组表示不带任何标签</li>
     * <li>wxids —— 必填 要设置标签的联系人 wxid，一次最多 50 个</li>
     * </ul>
     */
    public Object contactLabels(String accountId, Map<String, Object> args) {
        return call("PUT", "/accounts/" + pathSegment(accountId) + "/contacts/labels", null, take(args, "wxids", "label_ids"));
    }


    // --- 群 ---

    /**
     * 创建群聊 —— 邀请几位好友创建一个群聊，至少需要两个成员。
     * <p>POST /v1/accounts/{account_id}/groups
     * <p>args 里可以放：
     * <ul>
     * <li>members —— 必填 初始成员的 wxid</li>
     * </ul>
     */
    public Object groupCreate(String accountId, Map<String, Object> args) {
        return call("POST", "/accounts/" + pathSegment(accountId) + "/groups", null, take(args, "members"));
    }

    /**
     * 群详情 —— 查询群的名称、公告、群主等资料。
     * <p>建议缓存：保存群资料，收到 group.renamed 事件时再重新获取。公告和群主很少变化，不要每收到一条群消息就查询一次。
     * <p>GET /v1/accounts/{account_id}/groups/{group_id}
     */
    public Object groupGet(String accountId, String groupId) {
        return call("GET", "/accounts/" + pathSegment(accountId) + "/groups/" + pathSegment(groupId), null, null);
    }

    /**
     * 群成员 —— 列出群成员。
     * <p>建议缓存：保存成员列表，之后根据 group.member_joined 和 group.member_left 事件增减成员。每次调用都会实时从微信拉取，大群耗时长、开销大，不要定时重新拉取整个列表。
     * <p>GET /v1/accounts/{account_id}/groups/{group_id}/members
     */
    public Object groupMembers(String accountId, String groupId) {
        return call("GET", "/accounts/" + pathSegment(accountId) + "/groups/" + pathSegment(groupId) + "/members", null, null);
    }

    /**
     * 群成员详情 —— 查询指定群成员的完整资料，字段比「群成员」接口更全。
     * <p>建议缓存：按 wxid 保存成员资料，不要每收到一条群消息就查询一次发言人的资料。
     * <p>POST /v1/accounts/{account_id}/groups/{group_id}/members/detail
     * <p>args 里可以放：
     * <ul>
     * <li>members —— 必填 要查询的 wxid</li>
     * </ul>
     */
    public Object groupMemberDetail(String accountId, String groupId, Map<String, Object> args) {
        return call("POST", "/accounts/" + pathSegment(accountId) + "/groups/" + pathSegment(groupId) + "/members/detail", null, take(args, "members"));
    }

    /**
     * 邀请入群 —— 邀请好友入群。群人数较多时，微信会改为发送邀请链接。
     * <p>POST /v1/accounts/{account_id}/groups/{group_id}/invite
     * <p>args 里可以放：
     * <ul>
     * <li>members —— 必填 要邀请的 wxid</li>
     * <li>reason —— 可选 邀请说明</li>
     * </ul>
     */
    public Object groupInvite(String accountId, String groupId, Map<String, Object> args) {
        return call("POST", "/accounts/" + pathSegment(accountId) + "/groups/" + pathSegment(groupId) + "/invite", null, take(args, "members", "reason"));
    }

    /**
     * 移出群成员 —— 将成员移出群聊。只有群主和管理员可以操作。
     * <p>POST /v1/accounts/{account_id}/groups/{group_id}/members/remove
     * <p>args 里可以放：
     * <ul>
     * <li>members —— 必填 要移出的 wxid</li>
     * </ul>
     */
    public Object groupRemove(String accountId, String groupId, Map<String, Object> args) {
        return call("POST", "/accounts/" + pathSegment(accountId) + "/groups/" + pathSegment(groupId) + "/members/remove", null, take(args, "members"));
    }

    /**
     * 群管理员 —— 设置或取消群管理员，也可以转让群主。只有群主可以操作。
     * <p>POST /v1/accounts/{account_id}/groups/{group_id}/admins
     * <p>args 里可以放：
     * <ul>
     * <li>action —— 必填 grant 设为管理员，revoke 取消管理员，transfer 转让群主（转让群主时 members 只能填一个人）（grant / revoke / transfer）</li>
     * <li>members —— 必填 目标成员的 wxid</li>
     * </ul>
     */
    public Object groupAdmins(String accountId, String groupId, Map<String, Object> args) {
        return call("POST", "/accounts/" + pathSegment(accountId) + "/groups/" + pathSegment(groupId) + "/admins", null, take(args, "action", "members"));
    }

    /**
     * 修改群名 —— 修改群名称。需要有修改群名称的权限。
     * <p>PUT /v1/accounts/{account_id}/groups/{group_id}/name
     * <p>args 里可以放：
     * <ul>
     * <li>name —— 必填 新的群名称</li>
     * </ul>
     */
    public Object groupRename(String accountId, String groupId, Map<String, Object> args) {
        return call("PUT", "/accounts/" + pathSegment(accountId) + "/groups/" + pathSegment(groupId) + "/name", null, take(args, "name"));
    }

    /**
     * 设置群公告 —— 修改群公告。只有群主和管理员可以操作，修改后会向全群发送一条提示。
     * <p>PUT /v1/accounts/{account_id}/groups/{group_id}/announcement
     * <p>args 里可以放：
     * <ul>
     * <li>content —— 必填 公告正文，留空表示清除</li>
     * </ul>
     */
    public Object groupAnnouncement(String accountId, String groupId, Map<String, Object> args) {
        return call("PUT", "/accounts/" + pathSegment(accountId) + "/groups/" + pathSegment(groupId) + "/announcement", null, take(args, "content"));
    }

    /**
     * 设置群备注 —— 为群设置一个仅自己可见的备注名。
     * <p>PUT /v1/accounts/{account_id}/groups/{group_id}/remark
     * <p>args 里可以放：
     * <ul>
     * <li>remark —— 必填 备注名，留空表示清除</li>
     * </ul>
     */
    public Object groupRemark(String accountId, String groupId, Map<String, Object> args) {
        return call("PUT", "/accounts/" + pathSegment(accountId) + "/groups/" + pathSegment(groupId) + "/remark", null, take(args, "remark"));
    }

    /**
     * 设置我的群昵称 —— 修改自己在这个群里显示的昵称。
     * <p>PUT /v1/accounts/{account_id}/groups/{group_id}/nickname
     * <p>args 里可以放：
     * <ul>
     * <li>nickname —— 必填 群内昵称</li>
     * </ul>
     */
    public Object groupNickname(String accountId, String groupId, Map<String, Object> args) {
        return call("PUT", "/accounts/" + pathSegment(accountId) + "/groups/" + pathSegment(groupId) + "/nickname", null, take(args, "nickname"));
    }

    /**
     * 保存到通讯录 —— 将群保存到通讯录，或取消保存。没有保存到通讯录的群，在聊天会话被删除后将无法再找到。
     * <p>PUT /v1/accounts/{account_id}/groups/{group_id}/kept
     * <p>args 里可以放：
     * <ul>
     * <li>enabled —— 必填 true 保存，false 取消</li>
     * </ul>
     */
    public Object groupKept(String accountId, String groupId, Map<String, Object> args) {
        return call("PUT", "/accounts/" + pathSegment(accountId) + "/groups/" + pathSegment(groupId) + "/kept", null, take(args, "enabled"));
    }

    /**
     * 群二维码 —— 获取群的邀请二维码。返回 data URL，可直接用作 img 标签的 src。
     * <p>建议缓存：群二维码 7 天内有效。获取一次后保存为图片，快过期时再重新获取。
     * <p>GET /v1/accounts/{account_id}/groups/{group_id}/qrcode
     */
    public Object groupQrcode(String accountId, String groupId) {
        return call("GET", "/accounts/" + pathSegment(accountId) + "/groups/" + pathSegment(groupId) + "/qrcode", null, null);
    }

    /**
     * 通过链接进群 —— 通过收到的群邀请链接加入群聊。
     * <p>POST /v1/accounts/{account_id}/groups/join
     * <p>args 里可以放：
     * <ul>
     * <li>url —— 必填 邀请链接</li>
     * </ul>
     */
    public Object groupJoin(String accountId, Map<String, Object> args) {
        return call("POST", "/accounts/" + pathSegment(accountId) + "/groups/join", null, take(args, "url"));
    }

    /**
     * 查看群邀请 —— 查看群邀请链接对应的群信息，不会加入该群。usable 为 false 时，notice 字段说明原因，比如链接已过期。
     * <p>POST /v1/accounts/{account_id}/groups/preview
     * <p>args 里可以放：
     * <ul>
     * <li>url —— 必填 邀请链接</li>
     * </ul>
     */
    public Object groupPreview(String accountId, Map<String, Object> args) {
        return call("POST", "/accounts/" + pathSegment(accountId) + "/groups/preview", null, take(args, "url"));
    }

    /**
     * 同意入群邀请 —— 群成员邀请他人入群后，群主用这个接口同意邀请。inviter、message_id、ticket、members 四个参数都来自这条邀请事件。
     * <p>POST /v1/accounts/{account_id}/groups/{group_id}/approve
     * <p>args 里可以放：
     * <ul>
     * <li>inviter —— 必填 邀请人的 wxid</li>
     * <li>members —— 必填 被邀请人的 wxid</li>
     * <li>message_id —— 必填 邀请事件中的消息 ID</li>
     * <li>ticket —— 必填 邀请事件中的凭据</li>
     * </ul>
     */
    public Object groupApprove(String accountId, String groupId, Map<String, Object> args) {
        return call("POST", "/accounts/" + pathSegment(accountId) + "/groups/" + pathSegment(groupId) + "/approve", null, take(args, "inviter", "message_id", "ticket", "members"));
    }

    /**
     * 消息免打扰 —— 为一个群或一个好友开启或关闭消息免打扰。
     * <p>PUT /v1/accounts/{account_id}/chats/{chat_id}/muted
     * <p>args 里可以放：
     * <ul>
     * <li>enabled —— 必填 true 开启免打扰，false 恢复消息提醒</li>
     * </ul>
     */
    public Object chatMuted(String accountId, String chatId, Map<String, Object> args) {
        return call("PUT", "/accounts/" + pathSegment(accountId) + "/chats/" + pathSegment(chatId) + "/muted", null, take(args, "enabled"));
    }

    /**
     * 聊天置顶 —— 将一个群或一个好友的会话置顶，或取消置顶。
     * <p>PUT /v1/accounts/{account_id}/chats/{chat_id}/pinned
     * <p>args 里可以放：
     * <ul>
     * <li>enabled —— 必填 true 置顶，false 取消</li>
     * </ul>
     */
    public Object chatPinned(String accountId, String chatId, Map<String, Object> args) {
        return call("PUT", "/accounts/" + pathSegment(accountId) + "/chats/" + pathSegment(chatId) + "/pinned", null, take(args, "enabled"));
    }


    // --- 消息 ---

    /**
     * 发文字 —— 发送一条文字消息。在群里发送时可以 @ 群成员。
     * <p>POST /v1/accounts/{account_id}/messages/text
     * <p>args 里可以放：
     * <ul>
     * <li>content —— 必填 消息正文</li>
     * <li>to —— 必填 接收方：好友的 wxid、群 ID，或 filehelper（自己的文件传输助手）</li>
     * <li>mentions —— 可选 要 @ 的成员 wxid，仅在群聊中有效</li>
     * </ul>
     */
    public Object messageText(String accountId, Map<String, Object> args) {
        return call("POST", "/accounts/" + pathSegment(accountId) + "/messages/text", null, take(args, "to", "content", "mentions"));
    }

    /**
     * 发图片 —— 发送一张图片。url 和 media_id 二选一，使用 media_id 可以复用平台已保存的文件。
     * <p>POST /v1/accounts/{account_id}/messages/image
     * <p>args 里可以放：
     * <ul>
     * <li>to —— 必填 接收方：好友的 wxid、群 ID，或 filehelper（自己的文件传输助手）</li>
     * <li>media_id —— 可选 平台中已有文件的媒体 ID</li>
     * <li>url —— 可选 可从公网下载的文件地址</li>
     * <li>use_cache —— 可选 同一个 url 之前发送过时，直接复用已上传的文件，不再重新上传。默认 true。只有地址不变但文件内容已更换时，才需要传 false</li>
     * </ul>
     */
    public Object messageImage(String accountId, Map<String, Object> args) {
        return call("POST", "/accounts/" + pathSegment(accountId) + "/messages/image", null, take(args, "to", "url", "media_id", "use_cache"));
    }

    /**
     * 发视频 —— 发送一段视频。不填时长时由平台估算，部分客户端可能会显示异常。
     * <p>POST /v1/accounts/{account_id}/messages/video
     * <p>args 里可以放：
     * <ul>
     * <li>to —— 必填 接收方：好友的 wxid、群 ID，或 filehelper（自己的文件传输助手）</li>
     * <li>duration —— 可选 时长（秒）</li>
     * <li>media_id —— 可选 平台中已有文件的媒体 ID</li>
     * <li>thumbnail_url —— 可选 封面图地址，需要是可从公网下载的图片</li>
     * <li>url —— 可选 可从公网下载的文件地址</li>
     * <li>use_cache —— 可选 同一个 url 之前发送过时，直接复用已上传的文件，不再重新上传。默认 true。只有地址不变但文件内容已更换时，才需要传 false</li>
     * </ul>
     */
    public Object messageVideo(String accountId, Map<String, Object> args) {
        return call("POST", "/accounts/" + pathSegment(accountId) + "/messages/video", null, take(args, "to", "url", "media_id", "use_cache", "duration", "thumbnail_url"));
    }

    /**
     * 发语音 —— 发送一条语音。seconds 是语音时长，会显示在聊天中的语音消息上。
     * <p>POST /v1/accounts/{account_id}/messages/voice
     * <p>args 里可以放：
     * <ul>
     * <li>to —— 必填 接收方：好友的 wxid、群 ID，或 filehelper（自己的文件传输助手）</li>
     * <li>url —— 必填 可从公网下载的音频地址</li>
     * <li>seconds —— 可选 时长（秒）</li>
     * </ul>
     */
    public Object messageVoice(String accountId, Map<String, Object> args) {
        return call("POST", "/accounts/" + pathSegment(accountId) + "/messages/voice", null, take(args, "to", "url", "seconds"));
    }

    /**
     * 发文件 —— 发送一个文件。
     * <p>POST /v1/accounts/{account_id}/messages/file
     * <p>args 里可以放：
     * <ul>
     * <li>to —— 必填 接收方：好友的 wxid、群 ID，或 filehelper（自己的文件传输助手）</li>
     * <li>filename —— 可选 对方看到的文件名</li>
     * <li>media_id —— 可选 平台中已有文件的媒体 ID</li>
     * <li>url —— 可选 可从公网下载的文件地址</li>
     * <li>use_cache —— 可选 同一个 url 之前发送过时，直接复用已上传的文件，不再重新上传。默认 true。只有地址不变但文件内容已更换时，才需要传 false</li>
     * </ul>
     */
    public Object messageFile(String accountId, Map<String, Object> args) {
        return call("POST", "/accounts/" + pathSegment(accountId) + "/messages/file", null, take(args, "to", "url", "media_id", "use_cache", "filename"));
    }

    /**
     * 发动图表情 —— 转发一个动图表情。表情通过引用发送，无需上传文件。checksum 和 length 取自收到的表情消息。
     * <p>POST /v1/accounts/{account_id}/messages/sticker
     * <p>args 里可以放：
     * <ul>
     * <li>checksum —— 必填 表情的校验值，取自收到的表情消息</li>
     * <li>length —— 必填 表情的字节数，取自同一条表情消息</li>
     * <li>to —— 必填 接收方：好友的 wxid、群 ID，或 filehelper（自己的文件传输助手）</li>
     * </ul>
     */
    public Object messageSticker(String accountId, Map<String, Object> args) {
        return call("POST", "/accounts/" + pathSegment(accountId) + "/messages/sticker", null, take(args, "to", "checksum", "length"));
    }

    /**
     * 发链接卡片 —— 发送一张可点击的链接卡片。
     * <p>POST /v1/accounts/{account_id}/messages/link
     * <p>args 里可以放：
     * <ul>
     * <li>title —— 必填 卡片标题</li>
     * <li>to —— 必填 接收方：好友的 wxid、群 ID，或 filehelper（自己的文件传输助手）</li>
     * <li>url —— 必填 点击后打开的地址</li>
     * <li>description —— 可选 卡片摘要</li>
     * <li>source_name —— 可选 来源名称</li>
     * <li>thumb_url —— 可选 封面图地址</li>
     * </ul>
     */
    public Object messageLink(String accountId, Map<String, Object> args) {
        return call("POST", "/accounts/" + pathSegment(accountId) + "/messages/link", null, take(args, "to", "title", "description", "url", "thumb_url", "source_name"));
    }

    /**
     * 发小程序卡片 —— 发送一张小程序卡片。需要提供小程序的标识。
     * <p>POST /v1/accounts/{account_id}/messages/miniapp
     * <p>args 里可以放：
     * <ul>
     * <li>app_id —— 必填 小程序的公开标识</li>
     * <li>title —— 必填 卡片标题</li>
     * <li>to —— 必填 接收方：好友的 wxid、群 ID，或 filehelper（自己的文件传输助手）</li>
     * <li>username —— 必填 小程序的原始 ID</li>
     * <li>description —— 可选 卡片摘要</li>
     * <li>path —— 可选 点击后打开的小程序页面路径</li>
     * <li>source_name —— 可选 来源名称</li>
     * <li>thumb_url —— 可选 封面图地址</li>
     * </ul>
     */
    public Object messageMiniapp(String accountId, Map<String, Object> args) {
        return call("POST", "/accounts/" + pathSegment(accountId) + "/messages/miniapp", null, take(args, "to", "app_id", "username", "title", "description", "path", "thumb_url", "source_name"));
    }

    /**
     * 转发消息 —— 将收到过的一条消息原样转发给其他人。
     * <p>POST /v1/accounts/{account_id}/messages/forward
     * <p>args 里可以放：
     * <ul>
     * <li>message_id —— 必填 要转发的消息 ID</li>
     * <li>to —— 必填 接收方：好友的 wxid、群 ID，或 filehelper（自己的文件传输助手）</li>
     * </ul>
     */
    public Object messageForward(String accountId, Map<String, Object> args) {
        return call("POST", "/accounts/" + pathSegment(accountId) + "/messages/forward", null, take(args, "to", "message_id"));
    }

    /**
     * 撤回消息 —— 撤回自己发出的一条消息。微信只允许在发出后约两分钟内撤回，超过时间会被拒绝。
     * <p>POST /v1/accounts/{account_id}/messages/{message_id}/recall
     */
    public Object messageRecall(String accountId, String messageId) {
        return call("POST", "/accounts/" + pathSegment(accountId) + "/messages/" + pathSegment(messageId) + "/recall", null, null);
    }

    /**
     * 消息记录 —— 查询平台保存的消息记录，可以按会话筛选。
     * <p>GET /v1/accounts/{account_id}/messages
     * <p>args 里可以放：
     * <ul>
     * <li>cursor —— 可选 上一页返回的 next_cursor，首页留空</li>
     * <li>limit —— 可选 每页条数，最多 200，超过按 200 处理</li>
     * <li>peer —— 可选 只返回与某个 wxid 或群的会话中的消息</li>
     * </ul>
     */
    public Object messageHistory(String accountId, Map<String, Object> args) {
        return call("GET", "/accounts/" + pathSegment(accountId) + "/messages", take(args, "peer", "cursor", "limit"), null);
    }

    /**
     * 同步消息 —— 主动拉取这个实例收到的消息，内容与 Webhook 推送的完全相同。如果没有配置 Webhook、Webhook 中断过，或者服务重启过，可以用它补回这段时间的消息。cursor 留空时，从目前仍保留的最早一条消息开始返回（大约可追溯一天）。之后每次调用都传入上一次返回的 next_cursor。has_more 为 true 表示还没有拉取完，请立即再调用一次。没有新消息时，返回的 next_cursor 与传入的相同，游标不会前进。拉取到的消息不会写入数据库，也不会触发 Webhook，重复拉取没有副作用。
     * <p>POST /v1/accounts/{account_id}/messages/sync
     * <p>args 里可以放：
     * <ul>
     * <li>cursor —— 可选 上一次返回的 next_cursor，第一次留空</li>
     * </ul>
     */
    public Object messageSync(String accountId, Map<String, Object> args) {
        return call("POST", "/accounts/" + pathSegment(accountId) + "/messages/sync", null, take(args, "cursor"));
    }

    /**
     * 消息详情 —— 查询一条消息。
     * <p>GET /v1/accounts/{account_id}/messages/{message_id}
     */
    public Object messageGet(String accountId, String messageId) {
        return call("GET", "/accounts/" + pathSegment(accountId) + "/messages/" + pathSegment(messageId), null, null);
    }

    /**
     * 收藏列表 —— 列出这个实例收藏的内容。cursor 留空时从第一页开始，返回的 next_cursor 为空表示已经到最后一页。
     * <p>建议缓存：收藏只在你自己新增或删除收藏时才会变化。获取一次并保存，不要轮询；在你新增或删除收藏后再重新获取。
     * <p>GET /v1/accounts/{account_id}/favorites
     * <p>args 里可以放：
     * <ul>
     * <li>cursor —— 可选 上一页返回的 next_cursor，首页留空</li>
     * </ul>
     */
    public Object favoriteList(String accountId, Map<String, Object> args) {
        return call("GET", "/accounts/" + pathSegment(accountId) + "/favorites", take(args, "cursor"), null);
    }

    /**
     * 收藏详情 —— 查询一条收藏的完整内容。内容为微信原始的 XML，平台原样返回，不同类型的收藏结构不同。
     * <p>建议缓存：收藏的内容不会变化。按 fav_id 保存，获取过一次就不需要再获取。
     * <p>GET /v1/accounts/{account_id}/favorites/{fav_id}
     */
    public Object favoriteGet(String accountId, String favId) {
        return call("GET", "/accounts/" + pathSegment(accountId) + "/favorites/" + pathSegment(favId), null, null);
    }

    /**
     * 删除收藏 —— 删除一条收藏。
     * <p>DELETE /v1/accounts/{account_id}/favorites/{fav_id}
     */
    public Object favoriteDelete(String accountId, String favId) {
        return call("DELETE", "/accounts/" + pathSegment(accountId) + "/favorites/" + pathSegment(favId), null, null);
    }


    // --- 媒体 ---

    /**
     * 上传文件 —— 直接上传文件，获取一个 media_id。之后发送图片、视频、语音或文件时，只需传入这个 ID。适用于文件在你自己的机器上、没有公网地址的情况，比如程序刚生成的一张图片。请用 multipart/form-data 提交，文件放在 file 字段中，最大 20 MB。文件在第一次发送时才会真正上传到微信，之后用同一个 ID 发送不会重复上传。上传后一直没有发送过的文件保留 24 小时。
     * <p>POST /v1/accounts/{account_id}/media/upload
     * <p>args 里可以放：
     * <ul>
     * <li>file —— 必填 要上传的文件，multipart/form-data</li>
     * <li>kind —— 可选 这个文件将作为哪种消息发送，不填则根据文件类型自动判断（image / video / voice / file）</li>
     * </ul>
     */
    public Object mediaUpload(String accountId, Map<String, Object> args) {
        byte[] content = (byte[]) args.get("file");
        Object named = args.get("filename");
        return upload("/accounts/" + pathSegment(accountId) + "/media/upload", named == null ? null : String.valueOf(named),
                content, take(args, "kind"));
    }

    /**
     * 下载消息附件 —— 获取一条消息中的图片、视频、文件或语音，返回一个限时有效的下载地址。
     * <p>建议缓存：下载地址有时效。拿到文件后请保存到你自己的存储中，不要每次展示时都重新下载。平台上已下载文件的总量超过上限时，会清除最早的一半，请不要把平台当作长期存储。
     * <p>POST /v1/accounts/{account_id}/media/download
     * <p>args 里可以放：
     * <ul>
     * <li>message_id —— 必填 带附件的消息 ID</li>
     * </ul>
     */
    public Object mediaFromMessage(String accountId, Map<String, Object> args) {
        return call("POST", "/accounts/" + pathSegment(accountId) + "/media/download", null, take(args, "message_id"));
    }

    /**
     * 查文件是否已缓存 —— 查询平台是否已经发送过某个地址的文件。发送过的文件可以直接复用，不需要重新上传，也不计流量。建议在把文件放到公网之前先调用这个接口。如果平台已经发送过，就不必再把文件放到公网。
     * <p>POST /v1/accounts/{account_id}/media/cached
     * <p>args 里可以放：
     * <ul>
     * <li>kind —— 必填 image、video 或 file</li>
     * <li>url —— 必填 要发送的文件地址，必须与发送时填写的地址完全一致才算命中</li>
     * </ul>
     */
    public Object mediaCached(String accountId, Map<String, Object> args) {
        return call("POST", "/accounts/" + pathSegment(accountId) + "/media/cached", null, take(args, "url", "kind"));
    }

    /**
     * 重新取下载地址 —— 为已经下载过的文件重新生成一个限时有效的下载地址。
     * <p>GET /v1/accounts/{account_id}/media/{media_id}
     */
    public Object mediaGet(String accountId, String mediaId) {
        return call("GET", "/accounts/" + pathSegment(accountId) + "/media/" + pathSegment(mediaId), null, null);
    }

    /**
     * 下载动态媒体 —— 获取一条朋友圈动态中的第 N 张图片，或动态中的视频。
     * <p>POST /v1/accounts/{account_id}/moments/{moment_id}/media/download
     * <p>args 里可以放：
     * <ul>
     * <li>index —— 可选 图片序号，从 0 开始。视频动态会忽略这个值</li>
     * </ul>
     */
    public Object mediaMoment(String accountId, String momentId, Map<String, Object> args) {
        return call("POST", "/accounts/" + pathSegment(accountId) + "/moments/" + pathSegment(momentId) + "/media/download", null, take(args, "index"));
    }


    // --- 朋友圈 ---

    /**
     * 我的朋友圈 —— 查询这个实例能看到的朋友圈时间线。
     * <p>建议缓存：每次调用都会实时向微信请求，请不要定时刷新，刷新过于频繁会被视为异常行为。需要时再获取，并把获取到的动态保存在你自己的系统中。
     * <p>GET /v1/accounts/{account_id}/moments
     * <p>args 里可以放：
     * <ul>
     * <li>cursor —— 可选 上一页返回的 next_cursor</li>
     * </ul>
     */
    public Object momentTimeline(String accountId, Map<String, Object> args) {
        return call("GET", "/accounts/" + pathSegment(accountId) + "/moments", take(args, "cursor"), null);
    }

    /**
     * 朋友圈详情 —— 查询一条朋友圈的完整内容。列表接口中的点赞和评论会被截断，这个接口返回完整的点赞和评论。
     * <p>建议缓存：动态的正文和图片不会变化，获取后请保存。只有需要查看最新的点赞和评论时，才需要重新获取。
     * <p>GET /v1/accounts/{account_id}/moments/{moment_id}
     */
    public Object momentGet(String accountId, String momentId) {
        return call("GET", "/accounts/" + pathSegment(accountId) + "/moments/" + pathSegment(momentId), null, null);
    }

    /**
     * 某人的朋友圈 —— 查询某个联系人的朋友圈主页。
     * <p>建议缓存：每次调用都会实时向微信请求，请不要定时刷新，刷新过于频繁会被视为异常行为。需要时再获取，并把获取到的动态保存在你自己的系统中。
     * <p>GET /v1/accounts/{account_id}/moments/user/{wxid}
     * <p>args 里可以放：
     * <ul>
     * <li>cursor —— 可选 上一页返回的 next_cursor</li>
     * </ul>
     */
    public Object momentUser(String accountId, String wxid, Map<String, Object> args) {
        return call("GET", "/accounts/" + pathSegment(accountId) + "/moments/user/" + pathSegment(wxid), take(args, "cursor"), null);
    }

    /**
     * 发文字动态 —— 发布一条纯文字朋友圈。
     * <p>POST /v1/accounts/{account_id}/moments/text
     * <p>args 里可以放：
     * <ul>
     * <li>content —— 必填 正文</li>
     * <li>mentions —— 可选 要 @ 的 wxid</li>
     * <li>visibility —— 可选 可见范围。mode 可选 public、private、allow、deny；选择 allow 或 deny 时，需要同时提供 wxids 或 tag_ids</li>
     * </ul>
     */
    public Object momentPostText(String accountId, Map<String, Object> args) {
        return call("POST", "/accounts/" + pathSegment(accountId) + "/moments/text", null, take(args, "content", "mentions", "visibility"));
    }

    /**
     * 发图片动态 —— 发布一条带图片的朋友圈。
     * <p>POST /v1/accounts/{account_id}/moments/images
     * <p>args 里可以放：
     * <ul>
     * <li>images —— 必填 图片列表</li>
     * <li>content —— 可选 正文</li>
     * <li>visibility —— 可选 可见范围。mode 可选 public、private、allow、deny；选择 allow 或 deny 时，需要同时提供 wxids 或 tag_ids</li>
     * </ul>
     */
    public Object momentPostImages(String accountId, Map<String, Object> args) {
        return call("POST", "/accounts/" + pathSegment(accountId) + "/moments/images", null, take(args, "content", "images", "visibility"));
    }

    /**
     * 发视频动态 —— 发布一条视频朋友圈。
     * <p>POST /v1/accounts/{account_id}/moments/video
     * <p>args 里可以放：
     * <ul>
     * <li>video —— 必填 视频，需提供可从公网下载的地址</li>
     * <li>content —— 可选 正文</li>
     * <li>cover —— 可选 封面图</li>
     * <li>duration —— 可选 时长（秒）</li>
     * <li>visibility —— 可选 可见范围。mode 可选 public、private、allow、deny；选择 allow 或 deny 时，需要同时提供 wxids 或 tag_ids</li>
     * </ul>
     */
    public Object momentPostVideo(String accountId, Map<String, Object> args) {
        return call("POST", "/accounts/" + pathSegment(accountId) + "/moments/video", null, take(args, "content", "video", "cover", "duration", "visibility"));
    }

    /**
     * 转发动态 —— 将看到的一条动态原样重新发布一次。
     * <p>POST /v1/accounts/{account_id}/moments/forward
     * <p>args 里可以放：
     * <ul>
     * <li>moment_id —— 必填 要转发的动态 ID</li>
     * <li>visibility —— 可选 可见范围。mode 可选 public、private、allow、deny；选择 allow 或 deny 时，需要同时提供 wxids 或 tag_ids</li>
     * </ul>
     */
    public Object momentRepost(String accountId, Map<String, Object> args) {
        return call("POST", "/accounts/" + pathSegment(accountId) + "/moments/forward", null, take(args, "moment_id", "visibility"));
    }

    /**
     * 点赞 —— 给一条动态点赞。点赞前需要先通过朋友圈列表或详情接口读取过这条动态，读取后 24 小时内可以点赞。
     * <p>POST /v1/accounts/{account_id}/moments/{moment_id}/like
     */
    public Object momentLike(String accountId, String momentId) {
        return call("POST", "/accounts/" + pathSegment(accountId) + "/moments/" + pathSegment(momentId) + "/like", null, null);
    }

    /**
     * 取消赞 —— 取消对一条动态的赞。
     * <p>DELETE /v1/accounts/{account_id}/moments/{moment_id}/like
     */
    public Object momentUnlike(String accountId, String momentId) {
        return call("DELETE", "/accounts/" + pathSegment(accountId) + "/moments/" + pathSegment(momentId) + "/like", null, null);
    }

    /**
     * 评论 —— 评论一条动态，或回复别人的评论。
     * <p>POST /v1/accounts/{account_id}/moments/{moment_id}/comments
     * <p>args 里可以放：
     * <ul>
     * <li>content —— 必填 评论内容，最多 500 字</li>
     * <li>reply_to —— 可选 要回复的评论 ID，留空表示直接评论这条动态</li>
     * </ul>
     */
    public Object momentComment(String accountId, String momentId, Map<String, Object> args) {
        return call("POST", "/accounts/" + pathSegment(accountId) + "/moments/" + pathSegment(momentId) + "/comments", null, take(args, "content", "reply_to"));
    }

    /**
     * 删除评论 —— 删除自己发表的一条评论。
     * <p>DELETE /v1/accounts/{account_id}/moments/{moment_id}/comments/{comment_id}
     */
    public Object momentDeleteComment(String accountId, String momentId, String commentId) {
        return call("DELETE", "/accounts/" + pathSegment(accountId) + "/moments/" + pathSegment(momentId) + "/comments/" + pathSegment(commentId), null, null);
    }

    /**
     * 删除动态 —— 删除自己发布的一条朋友圈。
     * <p>DELETE /v1/accounts/{account_id}/moments/{moment_id}
     */
    public Object momentDelete(String accountId, String momentId) {
        return call("DELETE", "/accounts/" + pathSegment(accountId) + "/moments/" + pathSegment(momentId), null, null);
    }

    /**
     * 设为私密 / 公开 —— 将自己的一条动态设为仅自己可见，或恢复为公开。
     * <p>PUT /v1/accounts/{account_id}/moments/{moment_id}/privacy
     * <p>args 里可以放：
     * <ul>
     * <li>private —— 必填 true 表示仅自己可见，false 表示公开</li>
     * </ul>
     */
    public Object momentPrivacy(String accountId, String momentId, Map<String, Object> args) {
        return call("PUT", "/accounts/" + pathSegment(accountId) + "/moments/" + pathSegment(momentId) + "/privacy", null, take(args, "private"));
    }


    // --- 平台 ---

    /**
     * 当前用户 —— 查询当前 API Key 所属的用户，以及该用户的实例数量。
     * <p>GET /v1/me
     */
    public Object platformMe() {
        return call("GET", "/me", null, null);
    }

    /**
     * 事件列表 —— 查询平台记录的事件，可以按实例、事件类型、消息类型和时间筛选。没有配置 Webhook 时，可以轮询这个接口获取事件。 轮询方法：第一次调用可以用 since 指定起始时间。之后每次调用都传入上一次返回的 next_cursor，从该位置之后继续读取。只要本页有事件，就一定会返回 next_cursor。没有新事件时 next_cursor 为空，此时请继续使用你已保存的上一个 next_cursor。has_more 为 true 表示后面还有事件，请立即继续读取；否则请等待几秒后再轮询。如果处理过程中程序重启，而最新的游标还没来得及保存，重新读取时会再次拿到相同的几条事件，因此建议按 e…
     * <p>GET /v1/events
     * <p>args 里可以放：
     * <ul>
     * <li>account_id —— 可选 只返回某个实例的事件</li>
     * <li>cursor —— 可选 上一次返回的 next_cursor，从该位置之后继续读取。第一次调用时留空。如果返回的 next_cursor 为空，请继续使用上一次的值</li>
     * <li>keyword —— 可选 按事件内容搜索。需要同时指定时间范围，且范围不超过 1 小时</li>
     * <li>limit —— 可选 每页条数，最多 200，超过按 200 处理</li>
     * <li>message_type —— 可选 只返回某种消息类型的事件，如 text、image、file，参数可重复传入。设置后，非消息类事件不会出现在结果中</li>
     * <li>order —— 可选 oldest 按时间从早到晚返回（默认），newest 从最近发生的事件开始返回（oldest / newest）</li>
     * <li>since —— 可选 只返回这个时间之后的事件，格式为 RFC3339 或 Unix 秒级时间戳</li>
     * <li>type —— 可选 只返回某种类型的事件，参数可重复传入</li>
     * <li>until —— 可选 只返回这个时间之前的事件，格式为 RFC3339 或 Unix 秒级时间戳</li>
     * </ul>
     */
    public Object platformEvents(Map<String, Object> args) {
        return call("GET", "/events", take(args, "account_id", "type", "message_type", "since", "until", "keyword", "order", "cursor", "limit"), null);
    }

    /**
     * 事件流 —— 通过 SSE 长连接实时接收该实例的事件，事件内容与 Webhook 推送的相同。
     * <p>GET /v1/accounts/{account_id}/stream
     */
    public EventStream platformStream(String accountId) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/accounts/" + pathSegment(accountId) + "/stream"))
                .timeout(timeout).header("Authorization", "Bearer " + apiKey)
                .header("Accept", "text/event-stream").GET().build();
        try {
            HttpResponse<java.io.InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                try (java.io.InputStream body = response.body()) {
                    Object parsed;
                    try { parsed = Json.read(new String(body.readAllBytes(), StandardCharsets.UTF_8)); }
                    catch (RuntimeException e) { parsed = null; }
                    Map<?, ?> envelope = parsed instanceof Map ? (Map<?, ?>) parsed : Map.of();
                    Object code = envelope.get("code");
                    throw new WeLinkException(code instanceof BigDecimal ? ((BigDecimal) code).intValue() : 0,
                            envelope.get("message") == null ? "无法打开事件流" : String.valueOf(envelope.get("message")),
                            envelope.get("request_id") == null ? "" : String.valueOf(envelope.get("request_id")), response.statusCode());
                }
            }
            if (!response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream")) {
                response.body().close();
                throw new WeLinkException(0, "服务没有返回事件流", "", response.statusCode());
            }
            return new EventStream(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WeLinkException(0, "请求已中断", "", 0);
        } catch (IOException e) {
            throw new WeLinkException(0, "无法打开事件流：" + e.getMessage(), "", 0);
        }
    }

    /** SSE 连接，须使用 try-with-resources 关闭；next() 在连接结束时返回 null。 */
    public static final class EventStream implements AutoCloseable {
        private final java.io.BufferedReader reader;
        EventStream(java.io.InputStream body) {
            reader = new java.io.BufferedReader(new java.io.InputStreamReader(body, StandardCharsets.UTF_8));
        }
        public Map<String, Object> next() {
            try {
                java.util.List<String> data = new java.util.ArrayList<>();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty() && !data.isEmpty()) {
                        Object parsed = Json.read(String.join("\n", data));
                        if (!(parsed instanceof Map)) throw new WeLinkException(0, "事件内容不是 JSON 对象", "", 0);
                        @SuppressWarnings("unchecked") Map<String, Object> event = (Map<String, Object>) parsed;
                        return event;
                    }
                    if (line.startsWith("data:")) {
                        String value = line.substring(5);
                        data.add(value.startsWith(" ") ? value.substring(1) : value);
                    }
                }
                return null;
            } catch (IOException e) {
                throw new WeLinkException(0, "事件流连接中断：" + e.getMessage(), "", 0);
            } catch (RuntimeException e) {
                if (e instanceof WeLinkException) throw e;
                throw new WeLinkException(0, "事件内容不是 JSON", "", 0);
            }
        }
        public void close() throws IOException { reader.close(); }
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
