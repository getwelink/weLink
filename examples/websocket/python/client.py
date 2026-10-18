"""Python 3.10+; pip install -r requirements.txt. See ../README.md."""
import json
import os
import random
import re
import sys
import time
from pathlib import Path
from urllib.parse import quote, urlencode, urlsplit, urlunsplit

from websocket import create_connection, WebSocketBadStatusException


def handle_event(event):
    # Replace with your business handler. Deduplicate event_id in your database.
    # Raise on failure; the cursor is saved only after this function returns.
    print(json.dumps(event, ensure_ascii=False), flush=True)


def run():
    key = os.environ.get("WELINK_API_KEY", "")
    account = os.environ.get("WELINK_ACCOUNT_ID", "")
    if not key or not re.fullmatch(r"acc_[A-Za-z0-9_]{1,60}", account):
        raise ValueError("Set WELINK_API_KEY and WELINK_ACCOUNT_ID")
    base = urlsplit(os.environ.get("WELINK_BASE_URL", ""))
    scheme = {"http": "ws", "https": "wss", "ws": "ws", "wss": "wss"}.get(base.scheme)
    if not scheme or not base.netloc or base.username or base.query or base.fragment:
        raise ValueError("WELINK_BASE_URL must be an http(s)/ws(s) server URL without credentials/query/fragment")
    endpoint = urlunsplit((scheme, base.netloc, base.path.rstrip("/") + "/v1/accounts/" + quote(account) + "/ws", "", ""))
    state = Path(os.environ.get("WELINK_CURSOR_FILE", ".welink-ws-" + account + ".cursor"))
    state.parent.mkdir(parents=True, exist_ok=True)
    cursor = state.read_text(encoding="utf-8").strip() if state.exists() else ""
    if len(cursor) > 64:
        raise ValueError("Invalid cursor file")
    wait = 1
    while True:
        conn = None
        try:
            url = endpoint + ("?" + urlencode({"cursor": cursor}) if cursor else "")
            conn = create_connection(url, header={"Authorization": "Bearer " + key}, timeout=60)
            while True:
                raw = conn.recv()  # websocket-client replies to protocol Ping automatically.
                if not raw:
                    raise ConnectionError("connection closed")
                event = json.loads(raw)
                kind = event.get("type")
                if kind in ("gap", "error"):
                    print("Stopped: %s (%s); resolve access or pull missing messages before restarting" % (kind, event.get("reason", "")), file=sys.stderr)
                    return 2
                event_id = event.get("event_id")
                if not event_id or event_id == cursor:
                    continue
                handle_event(event)
                temp = state.with_name(state.name + ".tmp")
                temp.write_text(event_id + "\n", encoding="utf-8")
                os.replace(str(temp), str(state))
                cursor = event_id
                wait = 1
        except WebSocketBadStatusException as err:
            status = err.status_code
            if status in (400, 401, 403, 404):
                print("Handshake HTTP %s: check service version, key and account" % status, file=sys.stderr)
                return 2
            print("Handshake HTTP %s; retrying" % status, file=sys.stderr)
        except KeyboardInterrupt:
            return 0
        except Exception as err:
            # Do not log exception text that could contain an Authorization header.
            print("%s; reconnecting from saved cursor" % type(err).__name__, file=sys.stderr)
        finally:
            if conn:
                conn.close()
        try:
            time.sleep(wait + random.random())
        except KeyboardInterrupt:
            return 0
        wait = min(wait * 2, 30)


if __name__ == "__main__":
    sys.exit(run())
