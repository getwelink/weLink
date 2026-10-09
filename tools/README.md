# 文档与 SDK 检查

`docs/reference.json` 保存公开接口、事件字段和错误码快照，`docs/openapi.json` 是对应的 OpenAPI 文件。更新接口时同步这两个文件，并修改四种 SDK 的对应方法。

```bash
python tools/refresh_docs.py
python tools/check_reference.py
python -m unittest discover -s tests -p test_python.py
node --test tests/node.test.mjs
```

`refresh_docs.py` 从快照生成回调与错误码文档；`check_reference.py` 检查四种 SDK、OpenAPI 的接口覆盖、回调字段和本地文档链接。Python 与 Node.js 测试逐个调用 88 个 SDK 方法并核对路径、查询及请求体参数，还用本地模拟服务测试异常响应与事件流，不连接生产服务。

Go 检查：在 `sdk/go/welink` 和 `examples/go` 分别执行 `go test ./...`。Java 检查：按 `.github/workflows/sdk-check.yml` 中的编译和运行命令执行。提交后 GitHub Actions 会检查 Python 3.8/3.12、Node.js 18/22、Go 1.21 和 Java 11。
