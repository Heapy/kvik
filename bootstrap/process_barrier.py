"""Recursive cleanup acknowledgement shared with the Native process boundary.

The reserved environment entry is transport metadata, not a declared tool input.
Only an explicitly inherited, identity-checked, nonblocking pipe writer is used.
Guardians retain their upstream writer until their own group and downstream pipe
are drained. Any byte poisons completion; empty EOF acknowledges cleanup.
"""
import errno
import fcntl
import os
import re
import signal
import stat
import subprocess
import sys
import threading
import time

ENVIRONMENT = "KVIK_PROCESS_BARRIER_V1"
DESCRIPTOR = 6


class OwnershipError(RuntimeError):
    pass


class Capability:
    def __init__(self, descriptor, device, inode):
        self.descriptor, self.device, self.inode = descriptor, device, inode

    def validate(self):
        value = os.fstat(self.descriptor)
        flags = fcntl.fcntl(self.descriptor, fcntl.F_GETFL)
        if (not stat.S_ISFIFO(value.st_mode) or value.st_dev != self.device or value.st_ino != self.inode
                or flags & os.O_ACCMODE != os.O_WRONLY or not flags & os.O_NONBLOCK):
            raise OwnershipError("Invalid inherited process completion capability")

    def marker(self):
        self.validate()
        return f"{DESCRIPTOR}:{self.device}:{self.inode}"

    def poison(self):
        self.validate()
        try:
            os.write(self.descriptor, b"!")
        except (BlockingIOError, BrokenPipeError):
            # Full implies poison already present; a closed reader has no waiter.
            pass


def _capture():
    text = os.environ.pop(ENVIRONMENT, None)
    if text is None:
        return None
    if len(text) >= 128 or re.fullmatch(r"6:(0|[1-9][0-9]*):(0|[1-9][0-9]*)", text) is None:
        raise OwnershipError("Malformed inherited process completion marker")
    _, device, inode = map(int, text.split(":"))
    if device >= 2**64 or inode >= 2**64:
        raise OwnershipError("Process completion identity exceeds uint64")
    value = Capability(DESCRIPTOR, device, inode)
    value.validate()
    if sys.platform == "darwin":
        fcntl.fcntl(DESCRIPTOR, 73, 1)  # SDK F_SETNOSIGPIPE
    os.set_inheritable(DESCRIPTOR, False)
    return value


try:
    _upstream, _capture_error = _capture(), None
except (OSError, ValueError, OwnershipError) as failure:
    _upstream, _capture_error = None, f"Invalid inherited process completion capability: {failure}"


def check():
    if _capture_error is not None:
        raise OwnershipError(_capture_error)
    if _upstream is not None:
        _upstream.validate()


def poison():
    check()
    if _upstream is not None:
        _upstream.poison()


def inherited(environment):
    """Forward the captured capability, never a caller-supplied marker."""
    check()
    if ENVIRONMENT in environment:
        raise OwnershipError("Process completion marker is reserved")
    result = dict(environment)
    if _upstream is None:
        return result, ()
    result[ENVIRONMENT] = _upstream.marker()
    return result, (_upstream.descriptor,)


def exec_runtime(executable, arguments):
    environment, descriptors = inherited(os.environ)
    previous = {fd: os.get_inheritable(fd) for fd in descriptors}
    try:
        for fd in descriptors:
            os.set_inheritable(fd, True)
        os.execve(executable, arguments, environment)
    finally:
        for fd, value in previous.items():
            os.set_inheritable(fd, value)


class Completion:
    """One guardian's downstream pipe. Used only by single-threaded helpers."""
    def __init__(self):
        self.reader, self.writer = os.pipe()
        os.set_blocking(self.reader, False)
        os.set_blocking(self.writer, False)
        if sys.platform == "darwin":
            fcntl.fcntl(self.writer, 73, 1)
        value = os.fstat(self.writer)
        self.capability = Capability(self.writer, value.st_dev, value.st_ino)
        self.failed = self.eof = False

    def spawn(self, command, *, environment, **options):
        check()
        if threading.active_count() != 1:
            raise OwnershipError("Guardian spawn requires a single-threaded helper")
        if ENVIRONMENT in environment:
            raise OwnershipError("Process completion marker is reserved")
        child_environment = dict(environment, **{ENVIRONMENT: self.capability.marker()})
        # preexec performs only descriptor syscalls in a single-threaded private
        # helper. Keeping FD6 in pass_fds ensures close_fds preserves the dup2.
        # Reserve it if absent so Popen's exec-error pipe cannot occupy that slot.
        reserved = False
        try:
            os.fstat(DESCRIPTOR)
        except OSError as failure:
            if failure.errno != errno.EBADF:
                raise
            temporary = os.open(os.devnull, os.O_RDONLY)
            if temporary != DESCRIPTOR:
                os.dup2(temporary, DESCRIPTOR, inheritable=False)
                os.close(temporary)
            reserved = True
        writer = self.writer

        def transfer():
            os.dup2(writer, DESCRIPTOR, inheritable=True)
            if writer != DESCRIPTOR:
                os.close(writer)

        try:
            return subprocess.Popen(command, env=child_environment, close_fds=True,
                                    pass_fds=tuple(sorted({writer, DESCRIPTOR})),
                                    preexec_fn=transfer, **options)
        finally:
            if reserved:
                os.close(DESCRIPTOR)
            os.close(self.writer)
            self.writer = None

    def drain(self):
        if self.eof:
            return True
        # Bounded work per iteration; a full pipe already means failure.
        for _ in range(16):
            try:
                data = os.read(self.reader, 4096)
            except BlockingIOError:
                return False
            if not data:
                self.eof = True
                return True
            self.failed = True
        return False

    def close(self):
        for name in ("reader", "writer"):
            fd = getattr(self, name)
            if fd is not None:
                os.close(fd)
                setattr(self, name, None)


def _darwin_inventory_types():
    # Ordinary cached launch only forwards the completion capability. Load libproc
    # types when an owned process group actually needs a Darwin inventory.
    module = globals().get("ctypes")
    if module is None:
        import ctypes as module
        globals()["ctypes"] = module
    info_type = globals().get("_ShortProcessInfo")
    if info_type is None:
        class _ShortProcessInfo(module.Structure):
            # macOS SDK sys/proc_info.h: proc_bsdshortinfo, PROC_PIDT_SHORTBSDINFO=13.
            _fields_ = [(name, module.c_uint32) for name in ("pid", "ppid", "pgid", "status")]
            _fields_ += [("comm", module.c_char * 16)]
            _fields_ += [(name, module.c_uint32) for name in ("flags", "uid", "gid", "ruid", "rgid", "svuid", "svgid", "reserved")]
        info_type = _ShortProcessInfo
        globals()["_ShortProcessInfo"] = info_type
    return module, info_type


def __getattr__(name):
    # Keep explicit access (including test patches) to the former module-level
    # ctypes binding and ABI structure compatible without eager initialization.
    if name in ("ctypes", "_ShortProcessInfo"):
        _darwin_inventory_types()
        return globals()[name]
    raise AttributeError(f"module {__name__!r} has no attribute {name!r}")


def _linux_group_alive(group, proc_root="/proc"):
    """Inspect procfs without treating failed reads as an empty process group."""
    try:
        with os.scandir(proc_root) as entries:
            for count, entry in enumerate(entries):
                if count >= 262144:
                    raise OwnershipError("Process inventory exceeds bound")
                if not entry.name.isascii() or not entry.name.isdecimal():
                    continue
                try:
                    with open(os.path.join(entry.path, "stat"), "rb") as source:
                        data = source.read(4097)
                except (FileNotFoundError, ProcessLookupError):
                    continue  # The process exited between listing and observation.
                if len(data) > 4096:
                    raise OwnershipError("Process stat exceeds bound")
                prefix, separator, tail = data.rpartition(b") ")
                fields = tail.split()
                if (not separator or not prefix.startswith(entry.name.encode() + b" (") or
                        len(fields) < 3 or len(fields[0]) != 1 or not fields[2].isdigit()):
                    raise OwnershipError("Invalid process stat")
                if int(fields[2]) == group and fields[0] not in (b"Z", b"X"):
                    return True
    except OSError as failure:
        raise OwnershipError(f"Cannot inventory owned process group: {failure}") from failure
    return False


def group_alive(group):
    """Bounded host snapshot; errors never mean an empty group."""
    if sys.platform == "linux":
        return _linux_group_alive(group)
    if sys.platform != "darwin":
        raise OwnershipError("Unsupported bootstrap process inventory host")
    ctypes, info_type = _darwin_inventory_types()
    library = ctypes.CDLL("/usr/lib/libproc.dylib", use_errno=True)
    listing = library.proc_listpids
    listing.argtypes = [ctypes.c_uint32, ctypes.c_uint32, ctypes.c_void_p, ctypes.c_int]
    listing.restype = ctypes.c_int
    details = library.proc_pidinfo
    details.argtypes = [ctypes.c_int, ctypes.c_int, ctypes.c_uint64, ctypes.c_void_p, ctypes.c_int]
    details.restype = ctypes.c_int
    # A full buffer cannot certify that all members were observed.
    capacity = 256
    while capacity <= 262144:
        values = (ctypes.c_int * capacity)()
        ctypes.set_errno(0)
        size = listing(2, group, values, ctypes.sizeof(values))
        if size < 0 or size % ctypes.sizeof(ctypes.c_int) or (size == 0 and ctypes.get_errno()):
            raise OwnershipError(f"Cannot inventory owned process group: errno={ctypes.get_errno()}")
        if size < ctypes.sizeof(values):
            break
        capacity *= 2
    else:
        raise OwnershipError("Owned process group exceeds inventory bound")
    for pid in values[:size // ctypes.sizeof(ctypes.c_int)]:
        if pid <= 0:
            continue
        info = info_type()
        ctypes.set_errno(0)
        count = details(pid, 13, 0, ctypes.byref(info), ctypes.sizeof(info))
        if count == 0 and ctypes.get_errno() == errno.ESRCH:
            continue
        if count != ctypes.sizeof(info) or info.pid != pid:
            raise OwnershipError(f"Cannot inspect owned process: errno={ctypes.get_errno()}")
        if info.pgid == group and info.status != 5:  # SZOMB
            return True
    return False


def clean_group(process, completion, *, grace=0.2, deadline=None):
    """Reserve leader PID until group and recursive acknowledgements finish."""
    try:
        for number in (signal.SIGTERM, signal.SIGKILL):
            try:
                os.killpg(process.pid, number)
            except ProcessLookupError:
                pass
            except PermissionError:
                if group_alive(process.pid):
                    raise
            if number == signal.SIGTERM:
                until = time.monotonic() + grace
                while time.monotonic() < until:
                    completion.drain()
                    time.sleep(min(0.01, max(0, until - time.monotonic())))
        while True:
            drained = completion.drain()
            if drained and not group_alive(process.pid):
                break
            if deadline is not None and time.monotonic() >= deadline:
                raise OwnershipError("Recursive cleanup deadline exceeded")
            time.sleep(0.01)
        if completion.failed:
            raise OwnershipError("Nested process cleanup was not verified")
        process.wait()
    except BaseException:
        poison()
        raise
    finally:
        completion.close()
