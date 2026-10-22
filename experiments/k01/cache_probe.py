#!/usr/bin/env python3
"""Bounded REAPI/gRPC feasibility probe against an owned bazel-remote process.

This deliberately small wire encoder is only a spike, not the production client.
Field numbers follow bazelbuild/remote-apis remote_execution.proto (REAPI v2).
"""
import hashlib
import json
from pathlib import Path
import socket
import struct
import subprocess
import tempfile
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT / ".kvik/k01/cache-probe"
URL = "https://github.com/buchgr/bazel-remote/releases/download/v2.6.4/bazel-remote-2.6.4-darwin-arm64"
SHA256 = "ebde44a9dd6fd645b1ee36b6bf5fe141c3e5aa193ae59f031b50cc390cd1a0a2"


def varint(value):
    result = bytearray()
    while value >= 128:
        result.append((value & 127) | 128)
        value >>= 7
    result.append(value)
    return bytes(result)


def field(number, value):
    if isinstance(value, int):
        return varint(number << 3) + varint(value)
    if isinstance(value, str):
        value = value.encode()
    return varint((number << 3) | 2) + varint(len(value)) + value


def fields(data):
    index = 0
    result = {}

    def read_varint():
        nonlocal index
        value = shift = 0
        while True:
            byte = data[index]
            index += 1
            value |= (byte & 127) << shift
            if byte < 128:
                return value
            shift += 7
            if shift > 63:
                raise ValueError("Oversized protobuf varint")

    while index < len(data):
        tag = read_varint()
        wire = tag & 7
        if wire == 0:
            value = read_varint()
        elif wire == 2:
            length = read_varint()
            value = data[index:index + length]
            if len(value) != length:
                raise ValueError("Truncated protobuf field")
            index += length
        else:
            raise ValueError(f"Unsupported spike wire type {wire}")
        result.setdefault(tag >> 3, []).append(value)
    return result


def digest(data):
    return field(1, hashlib.sha256(data).hexdigest()) + field(2, len(data))


def port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def main():
    WORK.mkdir(parents=True, exist_ok=True)
    binary = ROOT / ".kvik/k01/tools/bazel-remote-2.6.4"
    if not binary.exists():
        binary.parent.mkdir(parents=True, exist_ok=True)
        with urllib.request.urlopen(URL, timeout=120) as response:
            data = response.read()
        assert hashlib.sha256(data).hexdigest() == SHA256
        binary.write_bytes(data)
        binary.chmod(0o755)
    assert hashlib.sha256(binary.read_bytes()).hexdigest() == SHA256
    http_port, grpc_port = port(), port()
    while grpc_port == http_port:
        grpc_port = port()
    evidence = {"backend": "bazel-remote 2.6.4", "binarySha256": SHA256, "checks": []}
    with tempfile.TemporaryDirectory(prefix="backend-", dir=WORK) as directory, (WORK / "server.log").open("w") as log:
        server = subprocess.Popen([binary, "--dir", directory, "--max_size", "1",
                                   "--http_address", f"127.0.0.1:{http_port}",
                                   "--grpc_address", f"127.0.0.1:{grpc_port}"], stdout=log, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 10
            while True:
                if server.poll() is not None:
                    raise RuntimeError("Cache server exited; see server.log")
                try:
                    with socket.create_connection(("127.0.0.1", grpc_port), timeout=0.2):
                        break
                except OSError:
                    if time.monotonic() > deadline:
                        raise
                    time.sleep(0.05)

            def rpc(service, method, body):
                name = service + "-" + method
                request = WORK / (name + ".request")
                response = WORK / (name + ".response")
                headers = WORK / (name + ".headers")
                request.write_bytes(b"\0" + struct.pack(">I", len(body)) + body)
                subprocess.run(["/usr/bin/curl", "--silent", "--show-error", "--fail", "--max-time", "10",
                                "--http2-prior-knowledge", "-H", "content-type: application/grpc", "-H", "te: trailers",
                                "--data-binary", "@" + str(request), "-D", headers, "-o", response,
                                f"http://127.0.0.1:{grpc_port}/build.bazel.remote.execution.v2.{service}/{method}"], check=True)
                assert "grpc-status: 0" in headers.read_text().lower(), headers.read_text()
                wire = response.read_bytes()
                assert len(wire) >= 5 and wire[0] == 0 and int.from_bytes(wire[1:5], "big") == len(wire) - 5
                evidence["checks"].append(name)
                return wire[5:]

            capabilities = fields(rpc("Capabilities", "GetCapabilities", b""))
            cache = fields(capabilities[1][0])
            # Repeated enums use packed encoding in proto3.
            assert 1 in cache[1] or any(isinstance(x, bytes) and 1 in x for x in cache[1]), cache
            assert fields(cache[2][0])[1] == [1], cache
            evidence["sha256Supported"] = True
            evidence["actionCacheWritable"] = True
            evidence["maxBatchBytes"] = cache.get(4, [0])[0]
            content = b"Kvik REAPI interoperability probe\n"
            command = field(1, "kvik-k01-probe") + field(7, "result.txt")
            root = b""
            action = field(1, digest(command)) + field(2, digest(root))
            request = b"".join(field(2, field(1, digest(x)) + field(2, x)) for x in [content, command, root, action])
            updated = fields(rpc("ContentAddressableStorage", "BatchUpdateBlobs", request))
            assert len(updated[1]) == 4
            for item in updated[1]:
                assert fields(fields(item).get(2, [b""])[0]).get(1, [0]) == [0]
            read = fields(rpc("ContentAddressableStorage", "BatchReadBlobs", field(2, digest(content))))
            blob = fields(read[1][0])
            assert fields(blob.get(3, [b""])[0]).get(1, [0]) == [0]
            assert blob[2][0] == content
            result = field(2, field(1, "result.txt") + field(2, digest(content)))
            rpc("ActionCache", "UpdateActionResult", field(2, digest(action)) + field(3, result))
            fetched = fields(rpc("ActionCache", "GetActionResult", field(2, digest(action))))
            assert fetched[2] == fields(result)[2]
            evidence["blobSha256"] = hashlib.sha256(content).hexdigest()
        finally:
            server.terminate()
            try:
                server.wait(timeout=5)
            except subprocess.TimeoutExpired:
                server.kill()
                server.wait()
            evidence["serverReaped"] = server.returncode is not None
    (WORK / "result.json").write_text(json.dumps(evidence, indent=2) + "\n")
    print(json.dumps(evidence, indent=2))


if __name__ == "__main__":
    main()
