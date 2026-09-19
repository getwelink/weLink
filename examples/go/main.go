// 从零到发出第一条消息，再顺手收一会儿事件。
//
//	WELINK_API_KEY=key_xxx WELINK_BASE_URL=https://你的地址 go run .
package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"os"
	"time"

	"github.com/wechatLink/weLink/sdk/go/welink"
)

func main() {
	wx := welink.New(os.Getenv("WELINK_API_KEY"), os.Getenv("WELINK_BASE_URL"))
	ctx := context.Background()

	// 1. 开一个实例。已经有了就跳过这步，直接用它的 account_id。
	raw, err := wx.AccountCreate(ctx, welink.M{"platform": "ipad", "name": "我的第一个实例"})
	must(err)
	var account struct {
		AccountID string `json:"account_id"`
	}
	must(json.Unmarshal(raw, &account))
	fmt.Println("实例已创建：", account.AccountID)

	// 2. 取登录二维码，用微信扫它。
	raw, err = wx.AccountQrcode(ctx, account.AccountID, nil)
	must(err)
	var code struct {
		QRCode string `json:"qrcode"`
	}
	must(json.Unmarshal(raw, &code))
	fmt.Println("二维码（把这个 data URL 贴到浏览器地址栏就能看到）：")
	fmt.Println(code.QRCode[:80], "...")

	// 3. 等扫码。
	for {
		raw, err = wx.AccountLoginStatus(ctx, account.AccountID)
		must(err)
		var status struct {
			Status string `json:"status"`
		}
		must(json.Unmarshal(raw, &status))
		fmt.Println("  当前状态：", status.Status)
		if status.Status == "online" {
			break
		}
		time.Sleep(3 * time.Second)
	}

	// 4. 上线了，给自己的文件传输助手发一条。
	_, err = wx.MessageText(ctx, account.AccountID, welink.M{
		"to":      "filehelper",
		"content": "Hello from WeLink",
	})
	var apiErr *welink.Error
	if errors.As(err, &apiErr) {
		fmt.Println("发失败了：", apiErr.Code, apiErr.Message, "request_id:", apiErr.RequestID)
	} else {
		must(err)
		fmt.Println("发出去了")
	}

	// 5. 轮询事件。不想开公网地址就这么收。
	cursor := "" // 第一次留空，之后一直带上回来的那个
	for i := 0; i < 5; i++ {
		raw, err = wx.PlatformEvents(ctx, welink.M{"cursor": cursor, "limit": 50, "order": "oldest"})
		must(err)
		var page struct {
			Items []struct {
				Type      string `json:"type"`
				CreatedAt string `json:"created_at"`
			} `json:"items"`
			NextCursor string `json:"next_cursor"`
		}
		must(json.Unmarshal(raw, &page))
		for _, e := range page.Items {
			fmt.Println(e.CreatedAt, e.Type)
		}
		if page.NextCursor != "" {
			cursor = page.NextCursor
		}
		if len(page.Items) == 0 {
			time.Sleep(3 * time.Second)
		}
	}
}

func must(err error) {
	if err != nil {
		log.Fatal(err)
	}
}
