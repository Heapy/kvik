"""Bounded synchronous acquisition processes, owned by their foreground caller.

Main-thread calls only; concurrent callers use separate processes. No shell or
executable PATH lookup. A private guardian kills the owned group on owner loss
and after leader exit. Children that escape their process group, change
credentials, or cannot be killed/reaped are outside this ownership contract.
"""
from dataclasses import dataclass
import json
import math
import os
from pathlib import Path
import selectors
import signal
import subprocess
import sys
import threading
import time
from types import MappingProxyType
from typing import Mapping

CLEANUP_GRACE_SECONDS = 2.0
MAX_ARGV_BYTES = MAX_ENVIRONMENT_BYTES = 256 * 1024
MAX_STDIN_BYTES = 1024 * 1024
MAX_STDOUT_BYTES = 64 * 1024 * 1024 + 4096
MAX_STDERR_BYTES = 1024 * 1024


@dataclass(frozen=True)
class OwnedProcessRequest:
    argv: tuple[str, ...]
    cwd: Path
    environment: Mapping[str, str]
    stdin: bytes = b""
    timeout_seconds: float = 30
    stdout_limit: int = 65536
    stderr_limit: int = 65536

    def __post_init__(self):
        object.__setattr__(self, "argv", tuple(self.argv))
        object.__setattr__(self, "environment", MappingProxyType(dict(self.environment)))


@dataclass(frozen=True)
class OwnedProcessResult:
    outcome: str
    exit_code: int | None
    stdout: bytes
    stderr: bytes
    elapsed_seconds: float
    limit_stream: str | None = None
    error: str | None = None


class OwnedProcessCancelled(Exception):
    def __init__(self, signal_number: int, result: OwnedProcessResult):
        self.signal_number = signal_number
        self.result = result
        super().__init__(f"Acquisition cancelled by signal {signal_number}")


def _validate(request):
    if threading.current_thread() is not threading.main_thread():
        raise ValueError("Owned acquisition requires the main thread")
    if not request.argv or any(not isinstance(x, str) or "\0" in x for x in request.argv):
        raise ValueError("argv must contain non-NUL strings")
    if not os.path.isabs(request.argv[0]):
        raise ValueError("Executable path must be absolute")
    if not isinstance(request.cwd, Path) or not request.cwd.is_absolute() or "\0" in str(request.cwd):
        raise ValueError("Working directory must be an absolute Path")
    if len(os.fsencode(request.cwd)) > 4096:
        raise ValueError("Working directory path exceeds 4096 bytes")
    if sum(len(x.encode("utf-8")) + 1 for x in request.argv) > MAX_ARGV_BYTES:
        raise ValueError("argv exceeds 256 KiB")
    for key, value in request.environment.items():
        if not isinstance(key, str) or not key or "=" in key or "\0" in key or not isinstance(value, str) or "\0" in value:
            raise ValueError("Invalid environment entry")
    if sum(len(k.encode("utf-8")) + len(v.encode("utf-8")) + 2 for k, v in request.environment.items()) > MAX_ENVIRONMENT_BYTES:
        raise ValueError("Environment exceeds 256 KiB")
    if not isinstance(request.stdin, bytes) or len(request.stdin) > MAX_STDIN_BYTES:
        raise ValueError("stdin must be at most 1 MiB of bytes")
    if type(request.timeout_seconds) not in (int, float) or not math.isfinite(request.timeout_seconds) or not 0 < request.timeout_seconds <= 120:
        raise ValueError("Timeout must be finite and in (0, 120] seconds")
    for name, maximum in (("stdout_limit", MAX_STDOUT_BYTES), ("stderr_limit", MAX_STDERR_BYTES)):
        value = getattr(request, name)
        if type(value) is not int or not 0 <= value <= maximum:
            raise ValueError(f"Invalid {name}; expected 0..{maximum}")


def run_owned(request: OwnedProcessRequest) -> OwnedProcessResult:
    """Collect binary prefixes; any quota/deadline failure is never an exit success.

    Deadline includes helper startup, stdin delivery and output drain. Cleanup
    may add at most CLEANUP_GRACE_SECONDS (subject to OS scheduling). SIGINT/TERM
    raise OwnedProcessCancelled only after cleanup and handler restoration.
    """
    started = time.monotonic()
    try:
        _validate(request)
        configuration = json.dumps(dict(argv=request.argv, cwd=str(request.cwd), environment=dict(request.environment)),
                                   ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    except Exception as failure:
        return OwnedProcessResult("setup-error", None, b"", b"", time.monotonic() - started, error=str(failure)[:512])
    deadline = started + request.timeout_seconds
    selector = selectors.DefaultSelector()
    descriptors = set()
    handlers = {}
    guardian = None
    owner_write = None
    cancellation = None
    reason = None
    cleanup_deadline = None
    status = bytearray()
    status_bytes = 0
    cleanup_started = False
    output = {"stdout": bytearray(), "stderr": bytearray()}
    received = None
    error = None

    def interrupted(number, _frame):
        nonlocal cancellation
        if cancellation is None:
            cancellation = number

    def pipe():
        pair = os.pipe()
        descriptors.update(pair)
        return pair

    def close(fd):
        if fd is None or fd not in descriptors:
            return
        try:
            selector.unregister(fd)
        except KeyError:
            pass
        os.close(fd)
        descriptors.remove(fd)

    def register(fd, event, tag):
        os.set_blocking(fd, False)
        selector.register(fd, event, tag)

    def stop(selected):
        nonlocal reason, cleanup_deadline
        if reason is None:
            reason = selected
            cleanup_deadline = min(cleanup_deadline or math.inf, time.monotonic() + CLEANUP_GRACE_SECONDS)
            if owner_write is not None:
                try:
                    os.write(owner_write, selected)
                except BrokenPipeError:
                    pass
            close(input_write)
            close(request_write)

    input_write = request_write = None
    try:
        for number in (signal.SIGINT, signal.SIGTERM):
            handlers[number] = signal.signal(number, interrupted)
        owner_read, owner_write = pipe()
        request_read, request_write = pipe()
        result_read, result_write = pipe()
        input_read, input_write = pipe()
        output_read, output_write = pipe()
        error_read, error_write = pipe()
        helper = Path(__file__).resolve().with_name("source_guardian.py")
        inherited = (owner_read, request_read, result_write, input_read, output_write, error_write)
        guardian = subprocess.Popen([str(Path(sys.executable).resolve()), "-I", "-S", str(helper),
                                     *map(str, inherited), str(deadline)],
                                    cwd="/", env={"PATH": "/usr/bin:/bin", "LANG": "C", "LC_ALL": "C"},
                                    stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                    close_fds=True, pass_fds=inherited, start_new_session=True)
        for fd in inherited:
            close(fd)
        register(request_write, selectors.EVENT_WRITE, "request")
        if request.stdin:
            register(input_write, selectors.EVENT_WRITE, "stdin")
        else:
            close(input_write)
        register(output_read, selectors.EVENT_READ, "stdout")
        register(error_read, selectors.EVENT_READ, "stderr")
        register(result_read, selectors.EVENT_READ, "result")
        offsets = {"request": 0, "stdin": 0}
        payloads = {"request": configuration, "stdin": request.stdin}
        while True:
            now = time.monotonic()
            if cancellation is not None:
                stop(b"i" if cancellation == signal.SIGINT else b"s")
            elif now >= deadline and (not cleanup_started or output_read in descriptors or error_read in descriptors):
                stop(b"t")
            reaped = guardian.poll() is not None
            readers = any(key.data in ("stdout", "stderr", "result") for key in selector.get_map().values())
            if reaped and not readers:
                break
            if reaped and received is None and result_read not in descriptors:
                error = "Acquisition guardian exited without a result"
                stop(b"x")
            if cleanup_deadline is not None and now >= cleanup_deadline - 0.2:
                error = "Acquisition cleanup did not finish within 2 seconds"
                break
            boundary = cleanup_deadline if cleanup_deadline is not None else deadline
            for key, _ in selector.select(max(0, min(0.02, boundary - now))):
                tag, fd = key.data, key.fd
                if fd not in descriptors:
                    continue
                if tag in payloads:
                    data = payloads[tag]
                    try:
                        count = os.write(fd, memoryview(data)[offsets[tag]:offsets[tag] + 65536])
                    except BlockingIOError:
                        continue
                    except BrokenPipeError:
                        close(fd)
                        continue
                    offsets[tag] += count
                    if offsets[tag] == len(data):
                        close(fd)
                    continue
                try:
                    chunk = os.read(fd, 65536 if tag != "result" else 4097)
                except BlockingIOError:
                    continue
                if not chunk:
                    close(fd)
                    if tag == "result" and (status or received is None):
                        error = "Invalid acquisition guardian result"
                        stop(b"x")
                    continue
                if tag == "result":
                    status_bytes += len(chunk)
                    if status_bytes > 4096:
                        error = "Acquisition guardian result exceeds control bound"
                        stop(b"x")
                        close(fd)
                        continue
                    status.extend(chunk)
                    while b"\n" in status:
                        line, _, rest = status.partition(b"\n")
                        status = bytearray(rest)
                        try:
                            value = json.loads(line)
                            if value == {"cleanupStarted": True} and not cleanup_started and received is None:
                                cleanup_started = True
                                cleanup_deadline = min(cleanup_deadline or math.inf, time.monotonic() + CLEANUP_GRACE_SECONDS)
                            elif (cleanup_started and received is None and isinstance(value, dict)
                                  and set(value) == {"outcome", "exit_code", "limit_stream", "error"}
                                  and value["outcome"] in ("exited", "timed-out", "output-limit", "setup-error")
                                  and (value["exit_code"] is None or type(value["exit_code"]) is int and 0 <= value["exit_code"] <= 255)
                                  and value["limit_stream"] in (None, "stdout", "stderr")
                                  and (value["error"] is None or isinstance(value["error"], str))):
                                received = value
                            else:
                                raise ValueError("Invalid guardian result fields")
                        except (ValueError, TypeError):
                            error = "Invalid acquisition guardian result"
                            stop(b"x")
                else:
                    limit = getattr(request, tag + "_limit")
                    remaining = limit - len(output[tag])
                    output[tag].extend(chunk[:remaining])
                    if len(chunk) > remaining:
                        stop(b"o" if tag == "stdout" else b"e")
        if received is None and error is None:
            error = "Acquisition guardian supplied no terminal result"
    except Exception as failure:
        error = f"{type(failure).__name__}: {failure}"[:512]
    finally:
        # Closing ownership also covers exceptions before a stop request was sent.
        close(owner_write)
        if guardian is not None and guardian.poll() is None:
            final_deadline = cleanup_deadline or time.monotonic() + CLEANUP_GRACE_SECONDS
            try:
                guardian.wait(timeout=max(0, final_deadline - time.monotonic() - 0.2))
            except subprocess.TimeoutExpired:
                guardian.kill()
                try:
                    guardian.wait(timeout=max(0, final_deadline - time.monotonic()))
                except subprocess.TimeoutExpired:
                    error = "Acquisition guardian could not be reaped"
        for fd in list(descriptors):
            close(fd)
        selector.close()
        for number, previous in handlers.items():
            signal.signal(number, previous)

    value = received or dict(outcome="setup-error", exit_code=None, limit_stream=None, error=error)
    if error is not None:
        value = dict(outcome="setup-error", exit_code=None, limit_stream=None, error=error)
    if reason == b"t":
        value = dict(outcome="timed-out", exit_code=124, limit_stream=None, error=error)
    elif reason in (b"o", b"e"):
        value = dict(outcome="output-limit", exit_code=value["exit_code"], limit_stream="stdout" if reason == b"o" else "stderr", error=error)
    if cancellation is not None:
        value = dict(outcome="exited", exit_code=128 + cancellation, limit_stream=None, error=error)
    result = OwnedProcessResult(**value, stdout=bytes(output["stdout"]), stderr=bytes(output["stderr"]), elapsed_seconds=time.monotonic() - started)
    if cancellation is not None:
        raise OwnedProcessCancelled(cancellation, result)
    return result
