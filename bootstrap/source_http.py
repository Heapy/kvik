"""Exact HTTPS object acquisition with byte and whole-transfer bounds."""
from contextlib import contextmanager
import hashlib
import os
import signal
import threading
import urllib.request

from bootstrap import source_graph as graph
from bootstrap.source_files import directory


class _NoRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        raise ValueError('Pinned source URL redirected; review and update its lock')


@contextmanager
def deadline(seconds):
    if threading.current_thread() is not threading.main_thread() or signal.getitimer(signal.ITIMER_REAL) != (0.0, 0.0):
        raise ValueError('Source transfers require the main thread and an unused interval timer')
    previous = signal.getsignal(signal.SIGALRM)
    def expired(_number, _frame):
        raise ValueError('Source transfer exceeded whole-transfer deadline')
    signal.signal(signal.SIGALRM, expired)
    try:
        signal.setitimer(signal.ITIMER_REAL, seconds)
        yield
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)
        signal.signal(signal.SIGALRM, previous)


def download(url, expected, destination, *, opener=None):
    graph._url(url)
    if '#' in url:
        raise ValueError('Fragments are unsupported in pinned source URLs')
    opener = opener or urllib.request.build_opener(urllib.request.ProxyHandler({}), _NoRedirects())
    directory(destination.parent)
    hasher, size = hashlib.sha256(), 0
    with deadline(120), opener.open(url, timeout=15) as response, open(destination, 'xb') as output:
        if getattr(response, 'status', 200) != 200:
            raise ValueError('Pinned source transfer did not return HTTP 200')
        while True:
            block = response.read(min(65536, expected['sizeBytes'] + 1 - size))
            if not block:
                break
            size += len(block)
            if size > expected['sizeBytes']:
                raise ValueError('Pinned source transfer exceeds locked size')
            hasher.update(block)
            output.write(block)
        if size != expected['sizeBytes'] or hasher.hexdigest() != expected['hash']:
            raise ValueError('Pinned source transfer differs from locked digest')
        output.flush()
        os.fchmod(output.fileno(), 0o644)
        os.fsync(output.fileno())
