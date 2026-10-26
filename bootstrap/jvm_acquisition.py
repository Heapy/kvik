"""JVM artifact transport loaded only when provisioning is requested."""
import fcntl
from contextlib import contextmanager
import hashlib
import os
from pathlib import Path
import signal
import tempfile
import urllib.request
from urllib.parse import urlsplit

from bootstrap.jvm_worker import all_artifacts, valid_artifact, sync_directory


class LockedRedirects(urllib.request.HTTPRedirectHandler):
    def __init__(self, source_url=None, release_url=None):
        super().__init__()
        self.source_url = source_url
        self.release_url = release_url

    def redirect_request(self, request, fp, code, message, headers, new_url):
        destination = urlsplit(new_url)
        pinned_release = self.release_url is not None and request.full_url == self.source_url and new_url == self.release_url
        release_asset = (self.release_url is not None and request.full_url == self.release_url and
                         destination.scheme == 'https' and destination.netloc == 'release-assets.githubusercontent.com' and
                         destination.path.startswith('/github-production-release-asset/3432266/') and
                         not destination.fragment)
        if pinned_release or release_asset:
            return super().redirect_request(request, fp, code, message, headers, new_url)
        source = urlsplit(request.full_url).path.rsplit('/', 1)[-1]
        # Report enough to diagnose CDN/repository redirects without logging signed
        # destination paths, credentials or query parameters.
        raise ValueError(f"Pinned JVM artifact {source} URL redirected (HTTP {code}, "
                         f"destination {destination.scheme}://{destination.hostname}); "
                         "explicitly review and update the lock")


@contextmanager
def transfer_deadline(seconds):
    """Bound headers, handshake and body even when traffic continually resets idle timeouts.

    Bootstrap runs in the main thread on the supported Unix host. It does not share
    an interval timer with other work; refuse a caller's active timer rather than steal it.
    """
    if signal.getitimer(signal.ITIMER_REAL) != (0.0, 0.0):
        raise ValueError("Cannot acquire JVM artifacts while another interval timer is active")
    previous = signal.getsignal(signal.SIGALRM)
    def expired(_number, _frame):
        raise ValueError(f"JVM artifact download exceeded {seconds} seconds")
    signal.signal(signal.SIGALRM, expired)
    try:
        signal.setitimer(signal.ITIMER_REAL, seconds)
        yield
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)
        signal.signal(signal.SIGALRM, previous)


def acquire(lock, directory, offline):
    directory.mkdir(parents=True, exist_ok=True)
    if directory.is_symlink():
        raise ValueError("JVM artifact cache must be a real directory")
    for item in all_artifacts(lock):
        destination = directory / item["filename"]
        descriptor = os.open(directory / (item["filename"] + ".lock"), os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
        with os.fdopen(descriptor, "a+b") as handle:
            fcntl.flock(handle, fcntl.LOCK_EX)
            if valid_artifact(destination, item):
                continue
            if offline:
                raise ValueError(f"Missing or corrupt pinned JVM artifact in offline mode: {item['filename']}")
            temporary = None
            try:
                hasher = hashlib.sha256()
                size = 0
                opener = urllib.request.build_opener(LockedRedirects(item['url'], item.get('releaseUrl')))
                with transfer_deadline(120), opener.open(item["url"], timeout=15) as source, tempfile.NamedTemporaryFile(dir=directory, delete=False) as output:
                    temporary = Path(output.name)
                    while True:
                        block = source.read(min(65536, item["size"] + 1 - size))
                        if not block:
                            break
                        size += len(block)
                        if size > item["size"]:
                            raise ValueError("JVM artifact exceeds locked size")
                        hasher.update(block)
                        output.write(block)
                    if size != item["size"] or hasher.hexdigest() != item["sha256"]:
                        raise ValueError(f"JVM artifact differs from lock: {item['filename']}")
                    output.flush()
                    os.fsync(output.fileno())
                temporary.replace(destination)
                sync_directory(directory)
            finally:
                if temporary is not None:
                    temporary.unlink(missing_ok=True)
    return {a["filename"]: directory / a["filename"] for a in all_artifacts(lock)}
