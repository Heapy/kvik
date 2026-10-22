"""Test-only TCP fault boundary. Accepted connections proxy to the real HTTP/2 backend."""
import select
import socket
import threading


class DropAfterProxy:
    def __init__(self, destination, allowed):
        self.destination = destination
        self.allowed = allowed
        self.stop = threading.Event()
        self.listener = socket.socket()
        self.listener.bind(('127.0.0.1', 0))
        self.listener.listen()
        self.listener.settimeout(0.1)
        self.port = self.listener.getsockname()[1]
        self.workers = []
        self.accepted = 0
        self.thread = threading.Thread(target=self.accept, daemon=True)

    def __enter__(self):
        self.thread.start()
        return self

    def accept(self):
        while not self.stop.is_set():
            try:
                client, _ = self.listener.accept()
            except (TimeoutError, OSError):
                continue
            self.accepted += 1
            if self.accepted > self.allowed:
                client.close()
                continue
            worker = threading.Thread(target=self.forward, args=(client,), daemon=True)
            self.workers.append(worker)
            worker.start()

    def forward(self, client):
        with client:
            try:
                with socket.create_connection(self.destination, timeout=1) as backend:
                    client.settimeout(1)
                    backend.settimeout(1)
                    while not self.stop.is_set():
                        readable, _, _ = select.select([client, backend], [], [], 0.1)
                        for source in readable:
                            data = source.recv(65536)
                            if not data:
                                return
                            (backend if source is client else client).sendall(data)
            except OSError:
                return

    def __exit__(self, *_):
        self.stop.set()
        self.listener.close()
        self.thread.join(timeout=2)
        for worker in self.workers:
            worker.join(timeout=2)
        assert not self.thread.is_alive() and all(not worker.is_alive() for worker in self.workers)
