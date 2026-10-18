# WebSocket / WSS 接入示例

程序主动连接网关，无需提供公网回调地址。支持接收微信消息和实例事件，WebSocket、Webhook、SSE 可同时使用。请先确认服务端已开放 `/v1/accounts/{account_id}/ws`；旧版本返回 404 时，需要先更新网关。这些示例不会登录微信、发送微信消息或自动补拉历史。

| 语言 | 运行环境 | 源码 | 安装依赖 |
| --- | --- | --- | --- |
| Python | Python 3.10+ | [python/client.py](python/client.py) | `pip install -r examples/websocket/python/requirements.txt` |
| Node.js | Node.js 18+ | [node/client.mjs](node/client.mjs) | 在 `examples/websocket/node` 执行 `npm ci` |
| Go | Go 1.21+ | [go/main.go](go/main.go) | 在 `examples/websocket/go` 执行 `go mod download` |
| Java | JDK 11+ | [java/WebSocketClient.java](java/WebSocketClient.java) | 无第三方依赖，与仓库 Java SDK 一起编译 |

[下载整个仓库 ZIP](https://github.com/getwelink/weLink/archive/refs/heads/main.zip)，解压后进入仓库根目录。Java 需要同时保留 `sdk/java/src/welink/WeLink.java`；Go 需要保留 `go.mod` 和 `go.sum`；Node.js 需要保留 `package.json` 和 `package-lock.json`。

## 1. 准备密钥和实例

在控制台创建 API Key，扫码登录微信，复制实例 ID。登录时默认选择 WebSocket 表示不绑定 Webhook，仍需启动本示例建立连接，不会自动替你创建长连接。已有 Webhook 绑定不影响另行建立 WebSocket。

配置以下环境变量，密钥不会写入游标文件：

| 变量 | 说明 |
| --- | --- |
| `WELINK_BASE_URL` | 网关根地址，例如 `https://你的网关地址`；支持 http、https、ws、wss，自动转换对应协议 |
| `WELINK_API_KEY` | 控制台 API Key |
| `WELINK_ACCOUNT_ID` | 实例 ID，例如 `acc_xxx` |
| `WELINK_CURSOR_FILE` | 可选，游标持久化文件路径；默认 `.welink-ws-实例ID.cursor` |

Windows PowerShell：

```powershell
$env:WELINK_BASE_URL='https://你的网关地址'
$env:WELINK_API_KEY='替换为自己的API Key'
$env:WELINK_ACCOUNT_ID='acc_xxx'
```

Linux / macOS：

```bash
export WELINK_BASE_URL='https://你的网关地址'
export WELINK_API_KEY='替换为自己的API Key'
export WELINK_ACCOUNT_ID='acc_xxx'
```

HTTPS 入口使用 **wss**，HTTP 入口使用 **ws**。wss 需要有效且与地址匹配的证书；示例不会跳过证书校验。不要将 API Key 放进 URL、源码或 Git 仓库。浏览器原生 WebSocket 不能设置本接口的鉴权头，请从业务服务器连接，再按需转发到自己的网页。

## 2. 启动客户端

以下命令均从仓库根目录开始执行，选择一种语言即可。

### Python

```bash
python -m venv .venv
```

Windows：

```powershell
.\.venv\Scripts\python.exe -m pip install -r examples/websocket/python/requirements.txt
.\.venv\Scripts\python.exe examples/websocket/python/client.py
```

Linux / macOS（创建环境时也可以使用 `python3`）：

```bash
.venv/bin/python -m pip install -r examples/websocket/python/requirements.txt
.venv/bin/python examples/websocket/python/client.py
```

### Node.js

```bash
cd examples/websocket/node
npm ci
npm start
```

### Go

```bash
cd examples/websocket/go
go mod download
go run .
```

### Java

```bash
javac -encoding UTF-8 --release 11 -d out sdk/java/src/welink/WeLink.java examples/websocket/java/WebSocketClient.java
java -cp out welink.WebSocketClient
```

Java 示例位于 `welink` 包中，只复用 SDK 中的 JSON 解析器，不调用其他微信接口。

## 3. 接入业务处理

连接成功后收到控制帧：

```json
{"type":"connected","account_id":"acc_xxx","heartbeat_interval":25,"resume_cursor":""}
```

真实事件直接使用与 Webhook 相同的结构：

```json
{"event_id":"evt_xxx","type":"message.received","account_id":"acc_xxx","timestamp":1792324800,"data":{"text":"示例消息"}}
```

替换 Python/Go 的 `handle_event` / `handleEvent`，Node.js 的 `handleEvent` 或 Java 的 `handleEvent`，执行自己的业务逻辑。**处理成功后才保存游标，处理失败时不要推进。** Node.js 按顺序等待异步业务函数完成；其他语言示例按接收顺序同步处理。

游标文件记录最后处理成功的 `event_id`。断线后示例使用 `?cursor=...` 自动重连，退避从 1 秒增加到最多 30 秒，另加随机间隔。每个实例单独运行、单独保存游标，不要让多个进程共享同一文件。若部署在容器中，请将游标文件放到持久化目录。

示例会跳过与当前游标相同的事件。生产业务仍应在数据库中以 `event_id` 建立幂等约束：业务成功与游标文件写入之间可能发生进程退出，不能假定消息只处理一次。

服务端每 25 秒发送协议 Ping，75 秒无响应断开；四种语言示例均自动响应 Pong。`connected`、`pong` 等控制消息不应进入微信业务处理，不更新业务游标。

## 4. 缺口与权限错误

最多补齐 199 条可见历史。收到下面的 `gap` 或 `error` 时，示例会退出，**不会反复自动重连**：

```json
{"type":"gap","reason":"cursor_expired","resume_cursor":"evt_xxx","action":"pull_sync"}
```

- `history_hidden`：历史展示已关闭或当前记录不可见。
- `cursor_expired`：游标过期、所属实例不符或记录不可见。
- `replay_limit`：积压过多，需要分页补拉。
- `slow_consumer`：消费太慢，连接缓冲溢出。
- `replay_unavailable`：当前服务端未启用回放。
- `access_revoked`：用户停用、实例删除/锁定、密钥撤销或会话失效。

缺口需要先通过同步消息或可见历史接口补齐，再把**已处理完成**的最新事件 ID 写回游标文件并重新启动。不要直接清空文件掩盖消息缺口。隐藏历史或保留期已过时，不保证能够完整补回所有消息。

握手 `401/403` 检查密钥和权限；`404` 检查实例 ID 与服务端版本；`429` 关闭多余连接并稍后重试。单实例最多 4 条 WebSocket、每用户最多 32 条。限额为当前单进程默认值，以部署的网关版本为准。

## 本地验证

测试使用本机临时接收端和固定测试密钥，不访问生产。它验证真实事件接收、协议 Ping/Pong、断线携带游标、游标落盘、重复事件跳过，以及收到 gap 后退出。先安装以上各语言依赖、编译 Java，再运行：

```bash
node examples/websocket/test_clients.mjs
```

可通过 `WELINK_TEST_LANGUAGES=python,node,go,java` 选择语言，`WELINK_TEST_PYTHON` 指定 Python 可执行文件。使用 Python 虚拟环境时请指定它的 Python 路径。
