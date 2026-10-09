import java.util.List;
import java.util.Map;

import welink.WeLink;

/**
 * 从零到发出第一条消息。
 *
 * <pre>
 * javac -encoding UTF-8 -d out ../../sdk/java/src/welink/WeLink.java Quickstart.java
 * WELINK_API_KEY=key_xxx WELINK_BASE_URL=https://你的地址 WELINK_PROXY=socks5://… java -cp out Quickstart
 * </pre>
 *
 * 返回值是 Map / List / String / BigDecimal / Boolean / null，
 * 不需要额外的 JSON 库。
 */
public class Quickstart {

    public static void main(String[] args) throws Exception {
        WeLink wx = new WeLink(System.getenv("WELINK_API_KEY"), System.getenv("WELINK_BASE_URL"));

        // 1. 开一个实例。已经有了就跳过这步，直接用它的 account_id。
        //    proxy 必填，不能直连：socks5 代理地址（socks5://user:pass@host:port），或者网络助手的网络ID。
        //    这里从环境变量 WELINK_PROXY 读。
        String proxy = System.getenv("WELINK_PROXY");
        if (proxy == null || proxy.isEmpty()) {
            System.err.println("先设置环境变量 WELINK_PROXY");
            return;
        }
        Map<?, ?> account = (Map<?, ?>) wx.accountCreate(
                Map.of("proxy", proxy, "name", "我的第一个实例"));
        String accountId = String.valueOf(account.get("account_id"));
        System.out.println("实例已创建：" + accountId);

        // 2. 取登录二维码，用微信扫它。
        Map<?, ?> code = (Map<?, ?>) wx.accountQrcode(accountId, null);
        saveQRCode(code);

        // 3. 等扫码。Mac 登录扫完还要过一次新设备验证，平台自动做，
        //    会在 scanned 停一会儿，别急着取消。
        while (true) {
            Map<?, ?> status = (Map<?, ?>) wx.accountLoginStatus(accountId);
            Object notice = status.get("notice");
            System.out.println("  当前状态：" + status.get("status") + (notice == null ? "" : " " + notice));
            if ("online".equals(status.get("status"))) {
                break;
            }
            if (List.of("expired", "cancelled", "offline").contains(status.get("status"))) {
                System.out.println("  这张码用不了了，重新取一张");
                saveQRCode((Map<?, ?>) wx.accountQrcode(accountId, null));
            }
            Thread.sleep(3000);
        }

        // 4. 上线了，给自己的文件传输助手发一条。
        try {
            wx.messageText(accountId, Map.of("to", "filehelper", "content", "Hello from WeLink"));
            System.out.println("发出去了");
        } catch (WeLink.WeLinkException e) {
            System.out.println("发失败了：" + e.code + " " + e.getMessage()
                    + " request_id: " + e.requestId);
        }

        // 5. 轮询事件。不想开公网地址就这么收。
        String cursor = "";
        for (int i = 0; i < 5; i++) {
            Map<?, ?> page = (Map<?, ?>) wx.platformEvents(
                    Map.of("cursor", cursor, "limit", 50, "order", "oldest"));
            List<?> items = (List<?>) page.get("items");
            for (Object item : items == null ? List.of() : items) {
                Map<?, ?> event = (Map<?, ?>) item;
                System.out.println(event.get("created_at") + " " + event.get("type"));
            }
            Object next = page.get("next_cursor");
            if (next != null && !"".equals(next)) {
                cursor = String.valueOf(next);
            }
            if (items == null || items.isEmpty()) {
                Thread.sleep(3000);
            }
        }
    }
    static void saveQRCode(Map<?, ?> code) throws java.io.IOException {
        String dataUrl = String.valueOf(code.get("qrcode"));
        java.nio.file.Path path = java.nio.file.Path.of("welink-login.png");
        java.nio.file.Files.write(path, java.util.Base64.getDecoder().decode(dataUrl.substring(dataUrl.indexOf(',') + 1)));
        System.out.println("用微信扫描二维码文件：" + path.toAbsolutePath());
    }

}
