module welink-example

go 1.21

require github.com/wechatLink/weLink/sdk/go/welink v0.0.0

// 从这个仓库里直接用。发布到 pkg.go.dev 之后可以去掉这行。
replace github.com/wechatLink/weLink/sdk/go/welink => ../../sdk/go/welink
