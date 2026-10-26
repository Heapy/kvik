"""Exact HTTPS object acquisition with byte and whole-transfer bounds."""
from contextlib import contextmanager
import hashlib
import ipaddress
import os
import re
import signal
import threading
import urllib.request
import urllib.error
from urllib.parse import urljoin, urlsplit

from bootstrap import source_graph as graph
from bootstrap.source_files import directory


class _NoRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        fp.close()
        raise ValueError('Pinned source URL redirected; review and update its lock')


def _origin(url):
    parsed = urlsplit(url)
    host = parsed.hostname
    if ':' in host:
        host = '[' + ipaddress.IPv6Address(host).compressed + ']'
    port = parsed.port
    return 'https://' + host + (':' + str(port) if port is not None and port != 443 else '')


def validate_redirect_origins(origins):
    if not isinstance(origins, list) or len(origins) > 4:
        raise ValueError('Tool redirect origins must be a list of at most four HTTPS origins')
    for origin in origins:
        graph._url(origin)
        parsed = urlsplit(origin)
        if (parsed.path or parsed.query or '?' in origin or parsed.fragment or
                not re.fullmatch(r'https://(?:[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?|\[[0-9a-f:]+\])(?::[1-9][0-9]{0,4})?', origin) or
                origin != _origin(origin)):
            raise ValueError('Tool redirect origin must be canonical HTTPS without a path, query or credentials')
    if len(set(origins)) != len(origins):
        raise ValueError('Tool redirect origins must be unique')
    return origins


class _ToolRedirects(urllib.request.HTTPRedirectHandler):
    def __init__(self, origins):
        self.origins = frozenset(validate_redirect_origins(origins))

    def http_error_302(self, request, fp, code, message, headers):
        try:
            location = headers.get('Location')
            if (not isinstance(location, str) or not location or len(location) > 4096 or
                    any(ord(c) <= 32 or ord(c) == 127 for c in location) or '\\' in location):
                raise ValueError('Invalid tool archive redirect location')
            target = urljoin(request.full_url, location)
            graph._url(target)
            if _origin(target) not in self.origins:
                raise ValueError('Tool archive redirect origin is not explicitly allowed')
            hops = getattr(request, '_tool_redirect_hops', 0)
            if hops >= 3:
                raise ValueError('Tool archive redirect hop limit exceeded')
            # Do not forward credentials, cookies, proxy authorization or other
            # headers; signed URLs exist only in this in-flight request.
            next_request = urllib.request.Request(target, method='GET')
            next_request._tool_redirect_hops = hops + 1
        finally:
            fp.close()
        return self.parent.open(next_request, timeout=request.timeout)

    http_error_301 = http_error_303 = http_error_307 = http_error_308 = http_error_302


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


def download(url, expected, destination, *, opener=None, redirect_origins=None, transfer_timeout=120):
    graph._url(url)
    if '#' in url:
        raise ValueError('Fragments are unsupported in pinned source URLs')
    origins = validate_redirect_origins([] if redirect_origins is None else redirect_origins)
    if type(transfer_timeout) is not int or not 1 <= transfer_timeout <= 3600:
        raise ValueError('Invalid whole-transfer timeout')
    opener = opener or urllib.request.build_opener(urllib.request.ProxyHandler({}),
                                                   _ToolRedirects(origins) if origins else _NoRedirects())
    directory(destination.parent)
    hasher, size = hashlib.sha256(), 0
    with deadline(transfer_timeout), _response(opener, url, redirect_origins is not None) as response, open(destination, 'xb') as output:
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


@contextmanager
def _response(opener, url, sanitize_errors):
    try:
        with opener.open(url, timeout=15) as response:
            yield response
    except urllib.error.URLError as failure:
        if isinstance(failure, urllib.error.HTTPError):
            failure.close()
        if sanitize_errors:
            raise ValueError('Pinned tool archive HTTPS transfer failed') from None
        raise
