# 完整案例：WebSocket 收消息并回复

适合第一次接入 WeLink 的开发者。完成后，你的程序会用一条账号级 WebSocket 连接接收事件，按 `account_id` 区分微信，只在指定会话回复三个命令。无需公网回调地址，也不会自动创建实例或替你扫码登录。

```text
指定联系人发送 /ping
  → WeLink 推送 message.received
  → 程序核对实例、会话、消息类型和去重记录
  → HTTP 文本发送接口回复 pong
  → 保存成功状态和事件游标
```

此案例不调用 AI，不消耗模型 Token。先跑通确定的收发流程，再把 `choose_reply()` 中的命令逻辑换成自己的业务。

## 1. 准备

1. 安装 Python 3.10+。在终端执行 `python --version`，确认版本；Linux/macOS 也可能使用 `python3`。
2. [下载正式接入套件](https://github.com/getwelink/weLink/releases/latest)，解压后进入 `welink-integration-kit` 目录，也就是能看到 `README.md`、`sdk`、`examples` 的目录。所有下文命令都在这个目录执行。
3. 在你的 WeLink 控制台创建 API Key，扫码登录一个微信实例，记录实例 ID `acc_...`。
4. 在实例的回调配置中选择 **WebSocket**。同一实例不能同时推送到 Webhook；仅切换选项不会自动启动本案例。
5. 准备一个能向该微信发消息的测试联系人，记录其 `wxid`。也可通过通讯录接口查询，见[接口文档](../../docs/API.md)。这里只需要 ID，不需要提供对方的密钥。

## 2. 安装依赖

Windows PowerShell：

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r examples/websocket-echo/requirements.txt
```

Linux / macOS：

```bash
python3 -m venv .venv
.venv/bin/python -m pip install -r examples/websocket-echo/requirements.txt
```

Python HTTP SDK 随仓库提供，代码会直接加载 `sdk/python`，不需要到 PyPI 安装同名包。请保留整个目录结构。

## 3. 配置

把占位内容替换为自己的信息。这些是终端环境变量，不会写入源码；关闭终端后，新终端需要重新设置。示例不会自动读取 `.env` 文件。

Windows PowerShell：

```powershell
$env:WELINK_BASE_URL='https://你的服务地址'
$env:WELINK_API_KEY='替换为自己的API Key'
$env:WELINK_ACCOUNT_IDS='acc_你的实例ID'
$env:WELINK_CHAT_IDS='wxid_测试联系人'
$env:WELINK_STATE_FILE='.welink-echo/state.sqlite3'
.\.venv\Scripts\python.exe examples/websocket-echo/main.py
```

Linux / macOS：

```bash
export WELINK_BASE_URL='https://你的服务地址'
export WELINK_API_KEY='替换为自己的API Key'
export WELINK_ACCOUNT_IDS='acc_你的实例ID'
export WELINK_CHAT_IDS='wxid_测试联系人'
export WELINK_STATE_FILE='.welink-echo/state.sqlite3'
.venv/bin/python examples/websocket-echo/main.py
```

| 配置项 | 如何填写 |
| --- | --- |
| `WELINK_BASE_URL` | 网关 HTTP(S) 根地址，末尾不加 `/v1`；程序自动转换为 ws/wss 连接 |
| `WELINK_API_KEY` | 当前平台账号的 API Key；通过 Authorization 请求头传递 |
| `WELINK_ACCOUNT_IDS` | 允许回复的实例 ID，多个用英文逗号隔开；必须与 API Key 所属账号一致 |
| `WELINK_CHAT_IDS` | 允许回复的联系人 wxid 或群 ID，多个用英文逗号隔开；会应用到上面所有允许实例 |
| `WELINK_STATE_FILE` | 可选，默认 `.welink-echo/state.sqlite3`；重启必须使用同一持久化文件 |
| `WELINK_ALLOW_GROUPS` | 可选，默认不回复群聊；设置为 `1` 后，还需将目标群 ID 加入会话列表 |

使用 HTTPS 时会校验证书；不要关闭校验或把 API Key 放进 URL。实际服务地址由你的控制台或服务提供方提供，仓库不内置生产地址。

## 4. 实际操作和预期结果

终端看到 `Connected; waiting for allowed commands` 后，让配置的测试联系人向已登录的微信发送：

| 发送内容 | 微信收到的回复 |
| --- | --- |
| `/ping` | `pong` |
| `/echo 你好，WeLink` | `你好，WeLink` |
| `/help` | `命令：/ping、/echo 内容、/help` |
| 普通聊天、图片、语音 | 不回复 |

发给错误实例或来自未配置的会话，不会回复。自己发送的消息也不会触发回复，避免循环。默认忽略群消息；启用群后，任意群成员在允许群内发送上述命令都能触发，不要求 @。

成功提交回复后，终端只打印 `Reply accepted; event_id=evt_...`，不输出 API Key、联系人或正文。这里的 accepted 表示发送接口返回成功，不代表对方已读。

首次无游标连接接收建立连接之后的新事件，不保证补发之前的聊天。程序连接成功之后再发送测试命令。按 `Ctrl+C` 停止；重新使用同一配置和状态文件启动，会携带保存的游标尝试恢复。

只需管理一个平台账号的一条连接。未列入允许实例的事件也会被读取并跳过，避免阻塞账号流；不会给这些实例自动发送消息。详细协议边界见[WebSocket 示例说明](../websocket/README.md)。

## 5. 去重、断线和异常处理

- 以“实例 ID + 消息 ID”保存回复记录，重复投递或程序正常重启不会再次回复同一条消息。所有事件处理完成后才推进游标；控制帧不改变游标。
- 传输断线后从 1 秒退避到最多 30 秒，另加随机间隔；WebSocket 库自动响应协议 Ping。网关回放受保留时间、历史可见性和回放数量限制，不保证无限补齐。
- 收到 `gap` / `error` 或握手 400/401/403/404 后退出，返回码为 2。先核对缺口或权限，再继续；不要通过删除状态文件掩盖问题。
- 调用发送前先落盘 `pending`，成功后原子保存 `sent` 和游标。发送接口报错、超时，或调用后进程意外退出，可能留下 `uncertain` / `pending`。程序会停止并在下次启动继续拒绝发送，防止未知结果自动重试。
- 本案例不宣称端到端“恰好发送一次”。发送成功与本地落盘之间仍有无法原子提交的窗口；未知结果必须人工判断。

### 处理未确认的发送

停止程序，先备份整个 `.welink-echo` 目录。使用 SQLite 工具打开状态文件，在 `replies` 表查看 `event_id` 与 `message_key`；它们可以定位到实例和原消息。结合控制台接口调用日志、消息记录及接收端核对实际发送结果。

若确认已发送，或决定跳过该次回复，可把**这条记录**的 `status` 改为 `sent`，保存后启动。这里只表示本案例不再发送该条，不是在补造网关发送记录。若确认没有发送且仍需回复，建议手动回复或让测试联系人发送一条新命令，再把旧记录按“跳过”处理。不要批量删除 pending 记录来重试。

SQLite 文件不保存原文和 API Key，只保存游标、服务/密钥的联合摘要、实例/消息 ID 和回复状态。记录随处理数量增长，示例不会自动清理；长期业务请设计独立存储与去重保留策略。限制文件访问权限，容器部署应挂载持久化目录。同一状态文件只允许运行一个进程；不同进程使用不同文件仍可能重复回复，不能当作多副本部署方案。更换 API Key 或服务地址时会拒绝复用原状态，需要先核对账号归属并安排迁移。

## 6. 改成自己的业务

`main.py` 中有两个重点：

1. `choose_reply()`：筛选收到的文本，返回目标会话、原消息 ID 和回复文字。此处可换成订单查询、通知助手或自有 AI 服务；保留会话与实例限制。
2. `process()`：调用仓库 Python SDK 的 `message_text()`，对应 `POST /v1/accounts/{account_id}/messages/text`。保留去重、发送前落盘和未知结果停止的逻辑。

替换为 AI 时，需自行管理外部数据授权、模型费用与超时；本案例没有内置模型服务。若业务需要后台队列、多副本、事务发件箱或人工工单，应在此基础上实现，不要直接增加消费者数量。

## 7. 不连接生产的本地验证

安装本案例 Python 依赖后，从仓库根目录执行：

```bash
python -m unittest discover -s tests -p test_websocket_echo.py
npm ci --prefix examples/websocket/node
node examples/websocket-echo/test_integration.mjs
```

如果使用虚拟环境，第一行使用虚拟环境的 Python；运行 Node 测试前还需设置 `WELINK_TEST_PYTHON` 为其**绝对路径**。Windows 可用 `$env:WELINK_TEST_PYTHON=(Resolve-Path .venv/Scripts/python.exe).Path`；Linux/macOS 可用 `export WELINK_TEST_PYTHON="$PWD/.venv/bin/python"`。

单元测试覆盖过滤、去重、状态落盘、未知结果停止和单进程锁；集成测试在本机启动临时 HTTP/WebSocket 服务，验证两个实例的消息路由、真正的 HTTP 请求与响应、Ping/Pong、重复投递、携带游标重连和 gap 退出。测试只使用固定的本地假密钥，不会登录或操作你的微信。
