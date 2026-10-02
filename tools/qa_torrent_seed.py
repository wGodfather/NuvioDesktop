"""Loopback-only BEP 3/9/10 seed for our tiny generated video, for Android QA.

Run from the repo root. Pass the generated JSON's magnet, bytes and sha256 as
instrumentation arguments to ForkAndroidIntegrationTest on an emulator.
No media from outside the repository is served. Ctrl+C stops both listeners.
"""
import hashlib
import json
import socket
import struct
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import quote


def encode(value):
    if isinstance(value, int):
        return b"i" + str(value).encode() + b"e"
    if isinstance(value, bytes):
        return str(len(value)).encode() + b":" + value
    if isinstance(value, dict):
        return b"d" + b"".join(encode(k) + encode(value[k]) for k in sorted(value)) + b"e"
    raise TypeError(type(value))


def decode(data, offset=0):
    marker = data[offset:offset + 1]
    if marker == b"d":
        result, offset = {}, offset + 1
        while data[offset:offset + 1] != b"e":
            key, offset = decode(data, offset)
            result[key], offset = decode(data, offset)
        return result, offset + 1
    if marker == b"i":
        end = data.index(b"e", offset)
        return int(data[offset + 1:end]), end + 1
    end = data.index(b":", offset)
    size = int(data[offset:end])
    return data[end + 1:end + 1 + size], end + 1 + size


payload = Path("composeApp/src/desktopTest/resources/verification/short-video.mp4").read_bytes()
assert 0 < len(payload) <= 16384
metadata = encode({b"name": b"fixture.mp4", b"length": len(payload),
                   b"piece length": 16384, b"pieces": hashlib.sha1(payload).digest()})
info_hash = hashlib.sha1(metadata).digest()
seed = socket.socket()
seed.bind(("127.0.0.1", 0))
seed.listen()
seed_port = seed.getsockname()[1]


def receive(conn, size):
    result = b""
    while len(result) < size:
        block = conn.recv(size - len(result))
        if not block:
            raise EOFError()
        result += block
    return result


def peer(conn):
    with conn:
        conn.settimeout(120)
        try:
            handshake = receive(conn, 68)
            assert handshake[:20] == b"\x13BitTorrent protocol" and handshake[28:48] == info_hash
            conn.sendall(b"\x13BitTorrent protocol" + bytes([0, 0, 0, 0, 0, 16, 0, 0])
                         + info_hash + b"-NVQA01-123456789012")

            def send(body):
                conn.sendall(struct.pack(">I", len(body)) + body)

            # The bitfield must be the first ordinary message.
            send(b"\x05\x80")
            send(b"\x14\x00" + encode({b"m": {b"ut_metadata": 1}, b"metadata_size": len(metadata)}))
            send(b"\x01")
            extension = 0
            print("peer connected", flush=True)
            while True:
                size = struct.unpack(">I", receive(conn, 4))[0]
                if not size:
                    continue
                assert size <= 1024 * 1024
                message = receive(conn, size)
                if message[0] == 20 and message[1] == 0:
                    value, _ = decode(message[2:])
                    extension = value.get(b"m", {}).get(b"ut_metadata", 0)
                elif message[0] == 20 and message[1] == 1:
                    value, _ = decode(message[2:])
                    if value.get(b"msg_type") == 0 and value.get(b"piece") == 0 and extension:
                        send(bytes([20, extension]) + encode({b"msg_type": 1, b"piece": 0,
                                                             b"total_size": len(metadata)}) + metadata)
                        print("metadata served", flush=True)
                elif message[0] == 6:
                    index, begin, length = struct.unpack(">III", message[1:])
                    assert index == 0 and 0 < length <= 16384 and begin + length <= len(payload)
                    send(b"\x07" + struct.pack(">II", index, begin) + payload[begin:begin + length])
                    print(f"piece served: {length} bytes", flush=True)
        except (EOFError, TimeoutError, ConnectionError):
            pass
        except Exception as error:
            print(f"peer error: {error}", flush=True)


def accept_peers():
    while True:
        conn, _ = seed.accept()
        threading.Thread(target=peer, args=(conn,), daemon=True).start()


class Tracker(BaseHTTPRequestHandler):
    def do_GET(self):
        if not self.path.startswith("/announce?"):
            self.send_error(404)
            return
        body = encode({b"interval": 30, b"complete": 1, b"incomplete": 0,
                       b"peers": socket.inet_aton("10.0.2.2") + struct.pack(">H", seed_port)})
        self.send_response(200)
        self.send_header("Content-Type", "application/x-bittorrent")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *_):
        print("tracker announce", flush=True)


tracker = ThreadingHTTPServer(("127.0.0.1", 0), Tracker)
tracker_url = f"http://10.0.2.2:{tracker.server_port}/announce"
magnet = f"magnet:?xt=urn:btih:{info_hash.hex()}&dn=fixture.mp4&tr={quote(tracker_url, safe='')}&x.pe=10.0.2.2:{seed_port}"
output = Path("../artifacts/android-torrent-fixture.json")
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps({"magnet": magnet, "sha256": hashlib.sha256(payload).hexdigest(),
                              "bytes": len(payload), "seed_port": seed_port,
                              "tracker_port": tracker.server_port}, indent=2))
print(f"Ready: {output.resolve()}", flush=True)
threading.Thread(target=accept_peers, daemon=True).start()
try:
    tracker.serve_forever()
except KeyboardInterrupt:
    tracker.server_close()
    seed.close()
