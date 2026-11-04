# WeLink 接入套件 2026.11.04

本次正式发布提供可直接下载的 SDK、接口说明和接入示例快照，方便固定版本接入和本地验证。

## 下载后先看哪里

- 初次接入：[WebSocket 收消息并回复完整案例](examples/websocket-echo/README.md)。包含 Windows、Linux/macOS 操作步骤，不需要公网回调地址。
- 查接口：[接口文档](docs/API.md)、[错误码](docs/ERRORS.md)、[OpenAPI 机器可读说明](docs/openapi.json)。公开清单共 85 个接口。
- 选择语言：[Python](sdk/python/README.md)、[Node.js](sdk/node/README.md)、[Go](sdk/go/README.md)、[Java](sdk/java/README.md)。
- 只接收实时事件：[四语言 WebSocket 示例](examples/websocket/README.md)。

## 本版内容

- 四语言 HTTP SDK、事件轮询和 SSE 示例，以及 WebSocket 账号级连接示例。
- 新增完整 Python 案例：指定实例/会话回复 `/ping`、`/echo`、`/help`，SQLite 去重与游标落盘，断线退避重连，未知发送结果停止待核对。
- 案例附本地模拟网关和自动测试，不需要生产实例或真实密钥。
- 完整压缩包附 `MANIFEST.json`，逐文件列出 SHA-256；Release 同时提供外部清单和 `SHA256SUMS.txt`。

## 包和版本说明

下载 `welink-integration-kit-2026.11.04.zip`，解压后进入 `welink-integration-kit`。请保留目录结构，SDK 和案例文档中的相对路径依赖它。

Release 使用日期标签 `v2026.11.04`，代表本仓库的接入快照，不代表网关服务端版本，也不更改语言包自身的版本号。本次未向 PyPI、npm、Maven Central 或 Go 模块代理发布新包；请使用下载包中的代码或本仓库固定提交。网关需要提供 `/v1/ws` 才能运行 WebSocket 案例。

Python HTTP SDK 需要 Python 3.8+，完整案例需要 Python 3.10+ 和 `websocket-client`；Node.js 18+、Go 1.21+、JDK 11+。其他语言的 WebSocket 依赖见各自示例说明。

## 兼容性和边界

- 当前公开 SDK 已移除旧版的好友搜索、主动添加申请和好友关系检测专用方法；从旧快照升级前，请检查是否使用这些方法。此说明不表示生产网关旧接口已经关闭。
- 同一微信实例的 WebSocket 与 Webhook 二选一；账号级 WebSocket 接收该平台账号下选择 WebSocket 的实例事件。
- 游标回放受服务端保留与可见性限制；收到 gap 需核对补齐，不能承诺无限回放。
- 发送接口返回成功不代表对方已读。未知发送结果不能直接重复提交，完整案例默认停止等待核对。
- 套件不含协议服务端、真实密钥、生产地址或用户数据。试用和接入需另行配置可用服务。

## 校验下载

Windows PowerShell：

```powershell
Get-FileHash .\welink-integration-kit-2026.11.04.zip -Algorithm SHA256
```

Linux：`sha256sum welink-integration-kit-2026.11.04.zip`；macOS：`shasum -a 256 welink-integration-kit-2026.11.04.zip`。结果应与同一 Release 的 `SHA256SUMS.txt` 一致。

## 本地验证

四语言 SDK 测试使用本机模拟服务；新案例的单元测试与完整 HTTP/WebSocket 集成测试见[案例验证步骤](examples/websocket-echo/README.md#7-不连接生产的本地验证)。完整四语言接收测试见[WebSocket 验证步骤](examples/websocket/README.md#本地验证)。测试不替代你对实际部署版本、真实账号和网络环境的受控联调。

SDK 与示例按仓库 MIT 许可提供。使用服务时请遵守[用途与禁止事项](README.md#可申请试用与长期维护)。
