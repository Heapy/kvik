#!/usr/bin/env python3
"""Real urllib redirect processing with a deterministic in-memory HTTPS transport."""
from email.message import Message
import io
import os
from pathlib import Path
import signal
import sys
import tempfile
import time
import unittest
from unittest.mock import patch
import urllib.error
import urllib.request
import urllib.response

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from bootstrap import source_http
from bootstrap.identity import digest


class Transport(urllib.request.HTTPSHandler):
    def __init__(self, responses):
        super().__init__()
        self.responses = responses
        self.requests = []

    def https_open(self, request):
        self.requests.append(request)
        status, target, body = self.responses[request.full_url]
        headers = Message()
        if target is not None: headers['Location'] = target
        response = urllib.response.addinfourl(io.BytesIO(body), headers, request.full_url, status)
        response.msg = 'OK' if status == 200 else 'Redirect'
        return response


class SourceHttpTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.output = Path(temporary.name)/'bytes'
        self.url = 'https://origin.example/archive'
        self.payload = b'pinned bytes'

    def download(self, responses, origins=None, expected=None, timeout=120):
        transport = Transport(responses)
        self.transport = transport
        build = urllib.request.build_opener
        def opener(*handlers): return build(*handlers, transport)
        with patch.object(source_http.urllib.request, 'build_opener', side_effect=opener), \
                patch.dict(os.environ, {'https_proxy': 'http://proxy.example:8888', 'HTTPS_PROXY': 'http://proxy.example:8888'}):
            source_http.download(self.url, expected or digest(self.payload), self.output,
                                 redirect_origins=origins, transfer_timeout=timeout)
        return transport

    def test_real_handler_chain_allows_three_hops_without_proxy_or_credentials(self):
        b, c, d = ('https://cdn.example/one?signature=secret', 'https://next.example/two', 'https://cdn.example/final')
        responses = {self.url: (302, b, b''), b: (307, c, b''), c: (308, d, b''), d: (200, None, self.payload)}
        transport = self.download(responses, ['https://cdn.example', 'https://next.example'])
        self.assertEqual(self.output.read_bytes(), self.payload)
        self.assertEqual([r.full_url for r in transport.requests], [self.url, b, c, d])
        for request in transport.requests:
            self.assertNotEqual(request.host, 'proxy.example:8888')
            self.assertFalse(set(map(str.lower, dict(request.header_items()))) &
                             {'authorization', 'proxy-authorization', 'cookie', 'referer'})

    def test_redirect_builds_fresh_request_without_sensitive_headers(self):
        handler = source_http._ToolRedirects(['https://cdn.example'])
        original = urllib.request.Request(self.url, headers={'Authorization': 'secret', 'Cookie': 'secret',
                                                           'Proxy-Authorization': 'secret', 'Referer': 'secret'})
        original.timeout = 15
        sent = []
        class Parent:
            def open(self, request, timeout): sent.append(request); return 'response'
        handler.parent = Parent()
        response = io.BytesIO(b'ignored redirect body')
        self.assertEqual(handler.http_error_302(original, response, 302, 'Redirect',
                         {'Location': 'https://cdn.example/file'}), 'response')
        self.assertTrue(response.closed)
        self.assertEqual(sent[0].header_items(), [])

    def test_every_hop_requires_allowlist_and_source_default_rejects_redirects(self):
        for origins in (None, [], ['https://allowed.example']):
            with self.subTest(origins=origins), self.assertRaises(ValueError):
                self.download({self.url: (302, 'https://unlisted.example/intermediate', b'')}, origins)
            self.assertEqual(len(self.transport.requests), 1)
            self.assertFalse(self.output.exists())
        first = 'https://allowed.example/one'
        with self.assertRaisesRegex(ValueError, 'not explicitly allowed'):
            self.download({self.url: (302, first, b''), first: (302, 'https://unlisted.example/two', b'')},
                          ['https://allowed.example'])
        self.assertEqual(len(self.transport.requests), 2)

    def test_downgrade_userinfo_fragment_and_malformed_locations_fail_before_follow(self):
        for target in ('http://cdn.example/file', 'https://user:secret@cdn.example/file',
                       'https://cdn.example/file#secret', 'https://cdn.example/bad path',
                       'file:///private/secret', '//user@cdn.example/file'):
            with self.subTest(target=target), self.assertRaises(ValueError):
                self.download({self.url: (302, target, b'')}, ['https://cdn.example'])
            self.assertEqual(len(self.transport.requests), 1)

    def test_loops_and_fourth_hop_stop_without_recording_signed_url(self):
        target = 'https://cdn.example/signed?secret=value'
        with self.assertRaisesRegex(ValueError, 'hop limit') as failure:
            self.download({self.url: (302, target, b''), target: (302, target, b'')}, ['https://cdn.example'])
        self.assertEqual(len(self.transport.requests), 4)
        self.assertNotIn('secret', str(failure.exception))
        with self.assertRaisesRegex(ValueError, 'HTTPS transfer failed') as failure:
            self.download({self.url: (302, target, b''), target: (403, None, b'')}, ['https://cdn.example'])
        self.assertNotIn('secret', str(failure.exception))
        self.assertTrue(failure.exception.__suppress_context__)

    def test_explicit_empty_tool_policy_sanitizes_http_error_messages(self):
        class BrokenOpener:
            def open(self, url, timeout):
                raise urllib.error.HTTPError(url, 403, 'signed-secret-in-server-message', {}, io.BytesIO())
        with self.assertRaisesRegex(ValueError, 'HTTPS transfer failed') as failure:
            source_http.download(self.url, digest(self.payload), self.output, opener=BrokenOpener(), redirect_origins=[])
        self.assertNotIn('secret', str(failure.exception))

    def test_redirects_do_not_bypass_hash_or_size(self):
        target = 'https://cdn.example/file'
        for payload in (b'wrong bytes!', b'pinned bytes plus more'):
            with self.subTest(payload=payload), self.assertRaisesRegex(ValueError, 'locked'):
                self.download({self.url: (302, target, b''), target: (200, None, payload)}, ['https://cdn.example'])
            self.output.unlink()

    def test_default_deadline_remains_120_and_explicit_deadline_is_forwarded(self):
        for timeout in (120, 900, 3600):
            with patch.object(source_http, 'deadline', wraps=source_http.deadline) as deadline:
                self.download({self.url: (200, None, self.payload)}, timeout=timeout)
            deadline.assert_called_once_with(timeout)
            self.output.unlink()
        for timeout in (0, True, 3601, 1.5):
            with self.assertRaisesRegex(ValueError, 'timeout'):
                self.download({}, timeout=timeout)

    def test_deadline_restores_signal_handler_and_cancels_timer_after_failure(self):
        previous = signal.getsignal(signal.SIGALRM)
        self.assertEqual(signal.getitimer(signal.ITIMER_REAL), (0.0, 0.0))
        with self.assertRaisesRegex(ValueError, 'deadline'):
            with source_http.deadline(0.01): time.sleep(0.1)
        self.assertEqual(signal.getsignal(signal.SIGALRM), previous)
        self.assertEqual(signal.getitimer(signal.ITIMER_REAL), (0.0, 0.0))


if __name__ == '__main__':
    unittest.main()
