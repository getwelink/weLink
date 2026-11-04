"""Local-only tests for reply selection and crash/uncertain-send state."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

path = Path(__file__).resolve().parents[1] / 'examples' / 'websocket-echo' / 'main.py'
spec = importlib.util.spec_from_file_location('echo_example', path)
echo = importlib.util.module_from_spec(spec)
spec.loader.exec_module(echo)


def event(event_id='evt_one', message_id='msg_one', **data):
    body = dict(message_id=message_id, chat_id='wxid_friend', self=False,
                is_group=False, type='text', text='/ping')
    body.update(data)
    return dict(event_id=event_id, type='message.received', account_id='acc_demo', data=body)


class Sender:
    def __init__(self, fail=False):
        self.calls = []
        self.fail = fail

    def message_text(self, account, **body):
        self.calls.append((account, body))
        if self.fail:
            raise TimeoutError('secret exception must not be logged')
        return {'message_id': 'msg_reply'}


class EchoTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / 'state.sqlite3'
        self.state = echo.State(self.path, 'fixture')
        self.sender = Sender()

    def tearDown(self):
        self.state.close()
        self.temp.cleanup()

    def process(self, value, **kwargs):
        echo.process(value, self.state, self.sender, {'acc_demo'}, {'wxid_friend', 'group@chatroom'}, **kwargs)

    def test_durable_dedup_by_message_and_cursor(self):
        self.process(event())
        self.process(event())
        self.process(event('evt_new_identity'))
        self.assertEqual(len(self.sender.calls), 1)
        self.state.close()
        self.state = echo.State(self.path, 'fixture')
        self.process(event('evt_after_restart'))
        self.assertEqual(len(self.sender.calls), 1)
        self.assertEqual(self.state.get('cursor'), 'evt_after_restart')

    def test_filters_and_control_frames(self):
        values = [event(self=True), event(type='image'), event(text='ordinary text'),
                  event(chat_id='wxid_other'), event(chat_id='group@chatroom', is_group=True),
                  {'type': 'connected', 'resume_cursor': 'evt_control'}]
        wrong = event(); wrong['account_id'] = 'acc_other'; values.append(wrong)
        missing = event(); del missing['data']['self']; values.append(missing)
        for value in values:
            self.process(value)
        self.assertEqual(self.sender.calls, [])
        self.assertNotEqual(self.state.get('cursor'), 'evt_control')

    def test_group_opt_in_and_echo_limit(self):
        self.process(event(chat_id='group@chatroom', is_group=True, text='/echo ' + '字' * 600), groups=True)
        self.assertEqual(self.sender.calls[0][1], {'to': 'group@chatroom', 'content': '字' * 500})

    def test_send_timeout_stops_without_advancing_or_retry(self):
        self.sender.fail = True
        with self.assertRaises(echo.Stop):
            self.process(event())
        self.assertEqual(self.state.get('cursor'), '')
        self.assertTrue(self.state.unresolved())
        self.sender.fail = False
        with self.assertRaises(echo.Stop):
            self.process(event())
        self.assertEqual(len(self.sender.calls), 1)

    def test_pending_survives_crash_window(self):
        with self.state.db:
            self.state.db.execute('INSERT INTO replies VALUES (?, ?, ?)', ('acc_demo:msg_one', 'evt_one', 'pending'))
        with self.assertRaises(echo.Stop):
            self.process(event())
        self.assertEqual(self.sender.calls, [])

    def test_gap_never_advances_cursor(self):
        self.state.checkpoint('evt_before')
        with self.assertRaises(echo.Stop):
            self.process({'type': 'gap', 'reason': 'cursor_expired'})
        self.assertEqual(self.state.get('cursor'), 'evt_before')

    def test_same_state_rejects_second_process_lock(self):
        with self.assertRaises(echo.Stop):
            echo.State(self.path, 'fixture')


if __name__ == '__main__':
    unittest.main()
