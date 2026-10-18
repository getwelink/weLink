// Go 1.21+; go mod download && go run .
package main

import (
	"context"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"math/rand"
	"net/http"
	"net/url"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"time"

	"github.com/gorilla/websocket"
)

type client struct {
	endpoint           *url.URL
	key, state, cursor string
}

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(2)
	}
}
func run() error {
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	c, err := configuration()
	if err != nil {
		return err
	}
	wait := time.Second
	for ctx.Err() == nil {
		reset, stop, err := c.once(ctx)
		if stop {
			return err
		}
		if ctx.Err() != nil {
			return nil
		}
		// Avoid writing network exception text containing request details/credentials.
		fmt.Fprintln(os.Stderr, "Connection ended; reconnecting from saved cursor")
		if reset {
			wait = time.Second
		}
		select {
		case <-ctx.Done():
			return nil
		case <-time.After(wait + time.Duration(rand.Intn(1000))*time.Millisecond):
		}
		wait *= 2
		if wait > 30*time.Second {
			wait = 30 * time.Second
		}
	}
	return nil
}
func configuration() (*client, error) {
	key := os.Getenv("WELINK_API_KEY")
	if key == "" {
		return nil, errors.New("set WELINK_API_KEY")
	}
	target, err := url.Parse(os.Getenv("WELINK_BASE_URL"))
	if err != nil || target.Host == "" || target.User != nil || target.RawQuery != "" || target.Fragment != "" {
		return nil, errors.New("invalid WELINK_BASE_URL")
	}
	switch target.Scheme {
	case "https":
		target.Scheme = "wss"
	case "http":
		target.Scheme = "ws"
	case "ws", "wss":
	default:
		return nil, errors.New("invalid WELINK_BASE_URL scheme")
	}
	target.Path = strings.TrimRight(target.Path, "/") + "/v1/ws"
	state := os.Getenv("WELINK_CURSOR_FILE")
	if state == "" {
		sum := sha256.Sum256([]byte(key))
		state = fmt.Sprintf(".welink-ws-user-%x.cursor", sum[:6])
	}
	if err := os.MkdirAll(filepath.Dir(state), 0700); err != nil {
		return nil, err
	}
	cursor := ""
	raw, err := os.ReadFile(state)
	if err == nil {
		cursor = strings.TrimSpace(string(raw))
	} else if !errors.Is(err, os.ErrNotExist) {
		return nil, err
	}
	if len(cursor) > 64 {
		return nil, errors.New("invalid cursor file")
	}
	return &client{endpoint: target, key: key, state: state, cursor: cursor}, nil
}
func (c *client) once(ctx context.Context) (processed, stop bool, err error) {
	target := *c.endpoint
	query := target.Query()
	if c.cursor != "" {
		query.Set("cursor", c.cursor)
	}
	target.RawQuery = query.Encode()
	header := http.Header{"Authorization": []string{"Bearer " + c.key}}
	dial := websocket.Dialer{HandshakeTimeout: 10 * time.Second}
	conn, res, err := dial.DialContext(ctx, target.String(), header)
	if err != nil {
		if res != nil {
			res.Body.Close()
			if res.StatusCode == 400 || res.StatusCode == 401 || res.StatusCode == 403 || res.StatusCode == 404 {
				return false, true, fmt.Errorf("handshake HTTP %d: check service version, key and account", res.StatusCode)
			}
		}
		return false, false, err
	}
	defer conn.Close()
	stopCancel := context.AfterFunc(ctx, func() { conn.Close() })
	defer stopCancel()
	conn.SetReadLimit(8 << 20)
	_ = conn.SetReadDeadline(time.Now().Add(75 * time.Second))
	ping := conn.PingHandler()
	conn.SetPingHandler(func(data string) error { _ = conn.SetReadDeadline(time.Now().Add(75 * time.Second)); return ping(data) })
	for {
		var message map[string]json.RawMessage
		if err := conn.ReadJSON(&message); err != nil {
			if websocket.IsCloseError(err, 1008) {
				return processed, true, errors.New("connection access revoked")
			}
			return processed, false, err
		}
		_ = conn.SetReadDeadline(time.Now().Add(75 * time.Second))
		var kind, id, reason string
		_ = json.Unmarshal(message["type"], &kind)
		_ = json.Unmarshal(message["event_id"], &id)
		_ = json.Unmarshal(message["reason"], &reason)
		if kind == "gap" || kind == "error" {
			return processed, true, fmt.Errorf("stopped: %s (%s); resolve access or pull missing messages before restarting", kind, reason)
		}
		if id == "" || id == c.cursor {
			continue
		}
		if err := handleEvent(message); err != nil {
			return processed, false, err
		}
		if err := os.WriteFile(c.state+".tmp", []byte(id+"\n"), 0600); err != nil {
			return processed, false, err
		}
		if err := os.Rename(c.state+".tmp", c.state); err != nil {
			return processed, false, err
		}
		c.cursor = id
		processed = true
	}
}
func handleEvent(event map[string]json.RawMessage) error {
	// Replace with your business handler, deduplicate event_id in your database,
	// and return errors. The cursor is saved only after this succeeds.
	body, err := json.Marshal(event)
	if err != nil {
		return err
	}
	_, err = fmt.Println(string(body))
	return err
}
