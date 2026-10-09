package welink

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestRequestsErrorsAndStream(t *testing.T) {
	mode := "ok"
	var received string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		received = r.RequestURI
		switch mode {
		case "stream":
			w.Header().Set("Content-Type", "text/event-stream")
			w.WriteHeader(200)
			w.(http.Flusher).Flush()
			io.WriteString(w, ": keep-alive\r\n\r\ndata: {\"event_id\":\"evt_你好\",\r\ndata: \"type\":\"message.received\"}\r\n\r\n")
			w.(http.Flusher).Flush()
			time.Sleep(60 * time.Millisecond)
			io.WriteString(w, "data: {\"event_id\":\"evt_two\"}\n\n")
		case "bad":
			io.WriteString(w, "null")
		case "missing":
			io.WriteString(w, "{}")
		case "http-error":
			w.WriteHeader(500)
			io.WriteString(w, `{"code":0}`)
		case "api-error":
			w.WriteHeader(403)
			io.WriteString(w, `{"code":40301,"message":"expired","request_id":"req_test"}`)
		default:
			io.WriteString(w, `{"code":0,"data":{"ok":true}}`)
		}
	}))
	defer server.Close()
	wx := New("key_test", server.URL)
	ctx := context.Background()
	if _, e := wx.MessageText(ctx, "acc a/b", M{"to": "wxid", "content": "你好"}); e != nil {
		t.Fatal(e)
	}
	if received != "/v1/accounts/acc%20a%2Fb/messages/text" {
		t.Fatal(received)
	}
	for _, m := range []string{"bad", "missing", "http-error", "api-error"} {
		mode = m
		_, e := wx.Call(ctx, "GET", "/test", nil, nil)
		var apiErr *Error
		if !errors.As(e, &apiErr) {
			t.Fatalf("%s error=%v", m, e)
		}
		if m == "api-error" && apiErr.RequestID != "req_test" {
			t.Fatal(apiErr)
		}
		if _, e = wx.Upload(ctx, "/test", "test.txt", []byte("abc"), nil); e == nil {
			t.Fatal("upload accepted invalid response")
		}
	}
	mode = "stream"
	wx.HTTP.Timeout = 20 * time.Millisecond
	stream, e := wx.PlatformStream(ctx, "acc_test")
	if e != nil {
		t.Fatal(e)
	}
	defer stream.Close()
	for i := 0; i < 2; i++ {
		if _, e = stream.Next(); e != nil {
			t.Fatal(e)
		}
	}
	if _, e = stream.Next(); !errors.Is(e, io.EOF) {
		t.Fatal(e)
	}
}

func TestWebhookSignature(t *testing.T) {
	body := []byte("你好")
	mac := hmac.New(sha256.New, []byte("secret"))
	mac.Write(body)
	sig := "sha256=" + hex.EncodeToString(mac.Sum(nil))
	if !VerifyWebhook("secret", body, sig) || VerifyWebhook("secret", []byte("wrong"), sig) {
		t.Fatal("signature")
	}
}
