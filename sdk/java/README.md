# WeLink Java 客户端

只用 JDK，11 以上，不需要任何第三方库 —— JSON 的读和写都在这一个文件里。

## 装

把 `src/welink/WeLink.java` 拷进你的项目即可。

```bash
javac -encoding UTF-8 -d out src/welink/WeLink.java
```

## 用

```java
WeLink wx = new WeLink("key_xxx", "https://你的服务地址");

try {
    wx.messageText("acc_xxx", Map.of("to", "filehelper", "content", "你好"));
} catch (WeLink.WeLinkException e) {
    System.out.println(e.code + " " + e.getMessage() + " " + e.requestId);
}
```

返回值是 `data` 字段的内容：JSON 对象是 `Map<String,Object>`，数组是
`List<Object>`，其余是 `String` / `BigDecimal` / `Boolean` / `null`。

方法名是接口 ID 去掉点转成小驼峰：`message.text` → `messageText`。

清单里还没有的新接口，用底层的 `call`：

```java
wx.call("POST", "/accounts/acc_xxx/some/new/route", null, Map.of("a", 1));
```

## 收事件

```java
if (!WeLink.verifyWebhook(secret, rawBody, request.getHeader("X-Orbit-Signature"))) {
    response.setStatus(403);
    return;
}
```

`rawBody` 必须是原始字节。Spring 里用 `@RequestBody byte[] body`，
不要用已经反序列化好的对象。
