"""In-process Gradle HTTP protocol fixture; never contacts external services."""
import base64
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import re
import threading
import time
import zlib


class HttpCacheFixture:
    def __init__(self, token='fixture-remote-token'):
        self.entries = {}
        self.requests = []
        self.status = None
        self.delay = 0
        self.redirect = None
        self.partial = False
        self.discard_writes = False
        self.alter_writes = False
        fixture = self
        authorization = 'Basic ' + base64.b64encode(('token-auth:' + token).encode()).decode()

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_GET(self):
                self.handle_cache(False)

            def do_PUT(self):
                self.handle_cache(True)

            def handle_cache(self, put):
                fixture.requests.append((self.command, self.path))
                if fixture.delay:
                    time.sleep(fixture.delay)
                if self.headers.get('Authorization') != authorization:
                    status, data = 401, b'unauthorized'
                elif not re.fullmatch(r'/cache/[0-9a-f]{32}', self.path):
                    status, data = 400, b'bad key'
                elif fixture.status:
                    status, data = fixture.status, b''
                elif put:
                    size = int(self.headers['Content-Length'])
                    if not 0 < size <= 128 * 1024 * 1024:
                        status, data = 413, b''
                    else:
                        body = self.rfile.read(size)
                        if len(body) == size:
                            # BuildFetch accepts 128-bit keys and gzip payloads. Require
                            # both here so opaque-byte fixtures cannot hide incompatibility.
                            try:
                                decoder = zlib.decompressobj(31)
                                expanded = decoder.decompress(body, 128 * 1024 * 1024 + 1)
                                valid = decoder.eof and not decoder.unused_data and not decoder.unconsumed_tail and len(expanded) <= 128 * 1024 * 1024
                            except zlib.error:
                                valid = False
                            if valid:
                                if not fixture.discard_writes:
                                    fixture.entries[self.path] = body + b'trailing' if fixture.alter_writes else body
                                status, data = 201, b''
                            else:
                                status, data = 400, b''
                        else:
                            status, data = 400, b''
                else:
                    data = fixture.entries.get(self.path, b'')
                    status = 200 if self.path in fixture.entries else 404
                self.send_response(status)
                self.send_header('Content-Length', str(len(data)))
                if fixture.redirect:
                    self.send_header('Location', fixture.redirect)
                self.end_headers()
                try:
                    self.wfile.write(data[:len(data)//2] if fixture.partial else data)
                except (BrokenPipeError, ConnectionResetError):
                    pass

        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.server.daemon_threads = False
        self.thread = threading.Thread(target=self.server.serve_forever)
        self.url = f'http://127.0.0.1:{self.server.server_port}/cache/'

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *args):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)
        assert not self.thread.is_alive()
