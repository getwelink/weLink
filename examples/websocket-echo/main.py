"""Account-scoped WebSocket -> text reply. Run from a downloaded repository."""
import hashlib
import json
import os
from pathlib import Path
import random
import sqlite3
import sys
import time
from urllib.parse import urlencode, urlsplit, urlunsplit

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'sdk' / 'python'))
from welink import WeLink
from websocket import create_connection, WebSocketBadStatusException


class Stop(Exception):
    """Requires an operator decision; must not trigger an automatic send retry."""


class State:
    def __init__(self, path, identity):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.lock = open(str(self.path) + '.lock', 'a+b')
        try:
            self.lock.seek(0)
            if not self.lock.read(1):
                self.lock.write(b'0')
                self.lock.flush()
            self.lock.seek(0)
            if os.name == 'nt':
                import msvcrt
                msvcrt.locking(self.lock.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(self.lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            self.lock.close()
            raise Stop('State is already in use by another process') from None
        self.db = sqlite3.connect(self.path)
        self.db.execute('PRAGMA synchronous=FULL')
        self.db.execute('CREATE TABLE IF NOT EXISTS meta (name TEXT PRIMARY KEY, value TEXT NOT NULL)')
        self.db.execute('CREATE TABLE IF NOT EXISTS replies (message_key TEXT PRIMARY KEY, event_id TEXT NOT NULL, status TEXT NOT NULL)')
        old = self.get('identity')
        if old and old != identity:
            self.close()
            raise Stop('State belongs to another service/API Key; use its original configuration')
        with self.db:
            self.db.execute('INSERT OR IGNORE INTO meta VALUES (?, ?)', ('identity', identity))

    def get(self, name):
        row = self.db.execute('SELECT value FROM meta WHERE name=?', (name,)).fetchone()
        return row[0] if row else ''

    def checkpoint(self, event_id):
        with self.db:
            self.db.execute('INSERT OR REPLACE INTO meta VALUES (?, ?)', ('cursor', event_id))

    def unresolved(self):
        return self.db.execute("SELECT event_id FROM replies WHERE status!='sent' LIMIT 1").fetchone()

    def close(self):
        self.db.close()
        self.lock.close()


def choose_reply(event, accounts, chats, groups):
    if event.get('type') != 'message.received' or event.get('account_id') not in accounts:
        return None
    data = event.get('data')
    if not isinstance(data, dict) or data.get('self') is not False or data.get('type') != 'text':
        return None
    chat = data.get('chat_id')
    if chat not in chats or (data.get('is_group') or '@chatroom' in chat) and not groups:
        return None
    message_id, text = data.get('message_id'), data.get('text')
    if not isinstance(message_id, str) or not message_id or not isinstance(text, str):
        return None
    if text == '/ping':
        return chat, message_id, 'pong'
    if text == '/help':
        return chat, message_id, '命令：/ping、/echo 内容、/help'
    if text.startswith('/echo ') and text[6:].strip():
        return chat, message_id, text[6:].strip()[:500]
    return None


def process(event, state, client, accounts, chats, groups=False):
    if not isinstance(event, dict):
        raise Stop('Invalid event object')
    if event.get('type') in ('gap', 'error'):
        raise Stop('Gateway reported gap/error; inspect history or access before restarting')
    event_id = event.get('event_id')
    if not isinstance(event_id, str) or not event_id:
        return  # connected/pong frames do not advance the business cursor.
    reply = choose_reply(event, accounts, chats, groups)
    if reply:
        chat, message_id, text = reply
        message_key = event['account_id'] + ':' + message_id
        with state.db:
            inserted = state.db.execute('INSERT OR IGNORE INTO replies VALUES (?, ?, ?)',
                                        (message_key, event_id, 'pending')).rowcount
        if inserted:
            try:
                client.message_text(event['account_id'], to=chat, content=text)
            except Exception:
                with state.db:
                    state.db.execute("UPDATE replies SET status='uncertain' WHERE message_key=?", (message_key,))
                raise Stop('Send result is unconfirmed; no automatic retry. Inspect replies state and gateway records') from None
            # Commit successful send state and cursor together; pending survives a crash.
            with state.db:
                state.db.execute("UPDATE replies SET status='sent' WHERE message_key=?", (message_key,))
                state.db.execute('INSERT OR REPLACE INTO meta VALUES (?, ?)', ('cursor', event_id))
            print('Reply accepted; event_id=' + event_id, flush=True)
            return
        row = state.db.execute('SELECT status FROM replies WHERE message_key=?', (message_key,)).fetchone()
        if row[0] != 'sent':
            raise Stop('Previous send is pending/uncertain; inspect before continuing')
    state.checkpoint(event_id)


def settings():
    base = urlsplit(os.environ.get('WELINK_BASE_URL', ''))
    if base.scheme not in ('http', 'https') or not base.netloc or base.username or base.password or base.query or base.fragment:
        raise Stop('Set WELINK_BASE_URL to the HTTP(S) service root, without /v1, credentials or query')
    if base.path.rstrip('/').endswith('/v1'):
        raise Stop('WELINK_BASE_URL must not end with /v1')
    key = os.environ.get('WELINK_API_KEY', '').strip()
    accounts = set(filter(None, (v.strip() for v in os.environ.get('WELINK_ACCOUNT_IDS', '').split(','))))
    chats = set(filter(None, (v.strip() for v in os.environ.get('WELINK_CHAT_IDS', '').split(','))))
    if not key or not accounts or not chats:
        raise Stop('Set WELINK_API_KEY, WELINK_ACCOUNT_IDS and WELINK_CHAT_IDS')
    service = urlunsplit((base.scheme, base.netloc, base.path.rstrip('/'), '', ''))
    endpoint = urlunsplit(('wss' if base.scheme == 'https' else 'ws', base.netloc, base.path.rstrip('/') + '/v1/ws', '', ''))
    identity = hashlib.sha256((service + '\n' + key).encode()).hexdigest()
    return service, endpoint, key, accounts, chats, identity


def run():
    service, endpoint, key, accounts, chats, identity = settings()
    state = State(os.environ.get('WELINK_STATE_FILE', '.welink-echo/state.sqlite3'), identity)
    client = WeLink(api_key=key, base_url=service, timeout=20)
    wait = 1
    try:
        if state.unresolved():
            raise Stop('Unresolved send in local state; inspect it before restarting')
        while True:
            conn = None
            try:
                cursor = state.get('cursor')
                url = endpoint + ('?' + urlencode({'cursor': cursor}) if cursor else '')
                conn = create_connection(url, header={'Authorization': 'Bearer ' + key}, timeout=60)
                print('Connected; waiting for allowed commands', flush=True)
                while True:
                    raw = conn.recv()  # websocket-client handles protocol Ping/Pong.
                    if not raw:
                        raise ConnectionError('closed')
                    try:
                        event = json.loads(raw)
                    except (ValueError, UnicodeDecodeError):
                        raise Stop('Invalid JSON frame') from None
                    try:
                        process(event, state, client, accounts, chats, os.environ.get('WELINK_ALLOW_GROUPS') == '1')
                    except Stop:
                        raise
                    except Exception:
                        raise Stop('Local event processing failed; check state storage before restarting') from None
                    wait = 1
            except WebSocketBadStatusException as err:
                if err.status_code in (400, 401, 403, 404):
                    raise Stop('Handshake HTTP %s; check service, permissions and key' % err.status_code) from None
                print('Handshake failed; reconnecting', flush=True)
            except (ConnectionError, OSError) as err:
                print(type(err).__name__ + '; reconnecting from saved cursor', flush=True)
            except Stop:
                raise
            except Exception:
                # Protocol timeouts/disconnects may be library-specific exceptions.
                # Business/send exceptions have already become Stop, never retry writes.
                print('Transport interrupted; reconnecting from saved cursor', flush=True)
            finally:
                if conn:
                    conn.close()
            time.sleep(wait + random.random())
            wait = min(wait * 2, 30)
    finally:
        state.close()


if __name__ == '__main__':
    try:
        run()
    except KeyboardInterrupt:
        sys.exit(0)
    except Stop as err:
        print(str(err), file=sys.stderr)
        sys.exit(2)
