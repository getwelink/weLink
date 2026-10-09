module welink-example

go 1.21

require github.com/getwelink/weLink/sdk/go/welink v0.0.0

// 在仓库内运行示例时使用本地 SDK。
replace github.com/getwelink/weLink/sdk/go/welink => ../../sdk/go/welink
