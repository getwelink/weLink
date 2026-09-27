import java.util.List;
import java.util.Map;

import welink.WeLink;

/**
 * 从零到发出第一条消息。
 *
 * <pre>
 * javac -encoding UTF-8 -d out ../../sdk/java/src/welink/WeLink.java Quickstart.java
 * java -cp out Quickstart
 * </pre>
 *
 * 返回值是 Map / List / String / BigDecimal / Boolean / null，
 * 不需要额外的 JSON 库。
 */
public class Quickstart {

    public static void main(String[] args) throws Exception {
        WeLink wx = new WeLink(System.getenv("WELINK_API_KEY"), System.getenv("WELINK_BASE_URL"));

        // 1. 开一个实例。已经有了就跳过这步，直接用它的 account_id。
        //    proxy 必填：area: 后面跟省份代码，平台会分一个那个省的代理给你，440000 是广东。
        //    离你常用手机所在的省越近越好。
        Map<?, ?> account = (Map<?, ?>) wx.accountCreate(
                Map.of("proxy", "area:440000", "name", "我的第一个实例"));
        String accountId = String.valueOf(account.get("account_id"));
        System.out.println("实例已创建：" + accountId);

        // 2. 取登录二维码，用微信扫它。
        Map<?, ?> code = (Map<?, ?>) wx.accountQrcode(accountId, null);
        String dataUrl = String.valueOf(code.get("qrcode"));
        System.out.println("二维码（把这个 data URL 贴到浏览器地址栏就能看到）：");
        System.out.println(dataUrl.substring(0, Math.min(80, dataUrl.length())) + " ...");

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
                wx.accountQrcode(accountId, null);
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
}
