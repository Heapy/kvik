"""Local fixture bytes and invocation-owned HTTPS; never production transport overrides."""
import gzip
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import io
import json
from pathlib import Path
import ssl
import stat
import subprocess
import tarfile
import threading


def digest(data):
    return {'hash': hashlib.sha256(data).hexdigest(), 'sizeBytes': len(data)}


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode('utf-8')


def inventory(root):
    files = []
    for path in sorted(root.rglob('*')):
        info = path.lstat()
        if stat.S_ISDIR(info.st_mode):
            continue
        if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
            raise ValueError(f'Nonregular/linked fixture input: {path}')
        files.append({'path': path.relative_to(root).as_posix(), 'digest': digest(path.read_bytes()),
                      'executable': bool(info.st_mode & 0o111)})
    return {'schemaVersion': 1, 'files': files}


def archive(root, prefix, destination):
    """Stable file order, modes, gzip header and tar metadata; no machine paths."""
    manifest = inventory(root)
    raw = io.BytesIO()
    with tarfile.open(fileobj=raw, mode='w', format=tarfile.USTAR_FORMAT) as output:
        for entry in manifest['files']:
            data = (root / entry['path']).read_bytes()
            if digest(data) != entry['digest']:
                raise ValueError('Fixture changed during archive creation')
            info = tarfile.TarInfo(prefix + '/' + entry['path'])
            info.size = len(data)
            info.mode = 0o755 if entry['executable'] else 0o644
            info.uid = info.gid = info.mtime = 0
            output.addfile(info, io.BytesIO(data))
    with destination.open('wb') as stream:
        with gzip.GzipFile(filename='', mode='wb', fileobj=stream, mtime=0) as output:
            output.write(raw.getvalue())
    return {'archiveDigest': digest(destination.read_bytes()), 'tree': digest(canonical(manifest)), 'inventory': manifest}


def certificates(directory):
    directory.mkdir()
    commands = [
        ['/usr/bin/openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '2',
         '-subj', '/CN=Kvik invocation private test CA', '-config', str(directory/'ca.cnf'), '-extensions', 'v3_ca', '-keyout', str(directory/'ca.key'), '-out', str(directory/'ca.pem')],
        ['/usr/bin/openssl', 'req', '-newkey', 'rsa:2048', '-nodes', '-subj', '/CN=localhost',
         '-keyout', str(directory/'server.key'), '-out', str(directory/'server.csr')],
        ['/usr/bin/openssl', 'x509', '-req', '-in', str(directory/'server.csr'), '-CA', str(directory/'ca.pem'),
         '-CAkey', str(directory/'ca.key'), '-set_serial', '1', '-days', '2', '-extfile', str(directory/'extensions.cnf'),
         '-out', str(directory/'server.pem')],
    ]
    (directory/'ca.cnf').write_text('[req]\ndistinguished_name=dn\n[dn]\n[v3_ca]\nbasicConstraints=critical,CA:TRUE\nkeyUsage=critical,keyCertSign,cRLSign\nsubjectKeyIdentifier=hash\nauthorityKeyIdentifier=keyid:always\n')
    (directory/'extensions.cnf').write_text('subjectAltName=DNS:localhost,IP:127.0.0.1\nbasicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\nsubjectKeyIdentifier=hash\nauthorityKeyIdentifier=keyid,issuer\n')
    with (directory/'openssl.log').open('wb') as log:
        for command in commands:
            subprocess.run(command, check=True, stdout=log, stderr=subprocess.STDOUT, timeout=30)
    for path in directory.glob('*.key'):
        path.chmod(0o600)
    public_roots = ssl.create_default_context().get_ca_certs(binary_form=True)
    if not public_roots:
        raise RuntimeError('No public CA roots available for isolated trust bundle')
    (directory/'trust.pem').write_text(''.join(ssl.DER_cert_to_PEM_cert(cert) for cert in public_roots) +
                                       (directory/'ca.pem').read_text())
    (directory/'public-root-hashes.json').write_bytes(canonical(sorted(digest(cert)['hash'] for cert in public_roots))+b'\n')
    (directory/'commands.json').write_bytes(canonical(commands)+b'\n')


class HttpsFixture:
    """Listener/threads belong to this process, so abrupt owner loss closes them too."""
    def __init__(self, directory, certificates, port=0):
        self.requests = []
        self.blocked = False
        self.directory = directory
        owner = self
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                owner.requests.append({'method': 'GET', 'path': self.path, 'blocked': owner.blocked})
                name = self.path.removeprefix('/')
                if owner.blocked or '/' in name or name not in {p.name for p in directory.glob('*.tar.gz')}:
                    self.send_error(503 if owner.blocked else 404)
                    return
                data = (directory/name).read_bytes()
                self.send_response(200)
                self.send_header('Content-Length', str(len(data)))
                self.send_header('Content-Type', 'application/gzip')
                self.end_headers()
                self.wfile.write(data)
            def log_message(self, *_args):
                pass
        self.server = ThreadingHTTPServer(('127.0.0.1', port), Handler)
        self.server.daemon_threads = True
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(certificates/'server.pem', certificates/'server.key')
        self.server.socket = context.wrap_socket(self.server.socket, server_side=True)
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, name='fixture-https', daemon=True)
    def __enter__(self):
        self.thread.start()
        return self
    def __exit__(self, *_args):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)
        if self.thread.is_alive():
            raise RuntimeError('Fixture HTTPS listener did not stop')
