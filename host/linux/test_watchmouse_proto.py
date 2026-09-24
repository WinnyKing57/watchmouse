"""Protocol v2 tests for the WatchMouse Linux host.

The known-answer vector below MUST stay in sync with ProtocolCryptoTest.java in the
app/ module: both run the same HKDF-SHA256 derivation over PIN="1234" and
salt=bytes(range(16)).

Run with:  python3 -m pytest host/linux/test_watchmouse_proto.py
"""

import json
import socket
import threading

import uinput

from watchmouse_host import (
    Client,
    SessionCrypto,
    hkdf,
)

KDF_INFO = b"watchmouse-v2"

# Computed with the host's own hkdf(); must equal ProtocolCryptoTest.HKDF_KAT.
HKDF_KAT = "F3F25E46F794502E875412C222FB5079"


def test_hkdf_matches_java_client_vector():
    key = hkdf(b"1234", bytes(range(16)), KDF_INFO, 16)
    assert key.hex().upper() == HKDF_KAT


def test_frame_roundtrip_and_tamper():
    crypto = SessionCrypto("1234", bytes(range(16)))
    frame = crypto.encrypt(b'{"t":"ping"}')
    assert not frame.startswith("{")
    assert crypto.decrypt(frame) == '{"t":"ping"}'

    tampered = ("A" if frame[0] != "A" else "B") + frame[1:]
    assert crypto.decrypt(tampered) is None
    assert crypto.decrypt("not-base64!!") is None
    assert crypto.decrypt("c2hvcnQ=") is None


class FakeSink:
    def __init__(self):
        self.mouse_events = []
        self.keyboard_states = []

    def mouse(self, left, right, middle, dx, dy, wheel):
        self.mouse_events.append((left, right, middle, dx, dy, wheel))

    def keyboard(self, pressed):
        self.keyboard_states.append(set(pressed))


def _start_server(sink, pin):
    """Binds an ephemeral listener and returns (port, server_socket)."""
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("127.0.0.1", 0))
    server.listen(1)
    port = server.getsockname()[1]

    def accept_and_serve():
        conn, addr = server.accept()
        Client(conn, addr, sink, pin).handle()

    threading.Thread(target=accept_and_serve, daemon=True).start()
    return server


def _receive_line(sock):
    data = b""
    while not data.endswith(b"\n"):
        chunk = sock.recv(1)
        if not chunk:
            return None
        data += chunk
    return data.decode("utf-8").strip()


def test_full_encrypted_handshake_and_input():
    sink = FakeSink()
    server = _start_server(sink, "1234")
    try:
        client = socket.create_connection(("127.0.0.1", server.getsockname()[1]), timeout=5)
        try:
            hello = _receive_line(client)
            assert hello is not None
            hello_json = json.loads(hello)
            assert hello_json["t"] == "hello"
            assert hello_json["v"] == 2
            salt = __import__("base64").decodebytes(hello_json["salt"].encode())

            local = SessionCrypto("1234", salt)
            client.sendall((local.encrypt(b'{"t":"auth"}') + "\n").encode())

            welcome = client.recv(4096)
            assert welcome, "server closed after auth"
            # welcome + pings arrive as encrypted frames; first frame must be a valid b64.
            frame = welcome.decode().strip().split("\n")[0]
            assert json.loads(local.decrypt(frame)) == {"t": "welcome"}

            client.sendall((local.encrypt(b'{"t":"mouse","l":0,"r":0,"m":0,"dx":10,"dy":-20,"w":0}') + "\n").encode())
            client.sendall((local.encrypt(b'{"t":"key","mod":0,"k":[40,0,0,0,0,0]}') + "\n").encode())
            client.sendall((local.encrypt(b'{"t":"ping"}') + "\n").encode())
            client.settimeout(1.0)

            ping_seen = False
            try:
                while True:
                    frame = _receive_line(client)
                    if frame is None:
                        break
                    assert json.loads(local.decrypt(frame)) == {"t": "ping"}
                    ping_seen = True
            except socket.timeout:
                pass

            # Server should have authenticated BEFORE touching the input sink.
            assert sink.mouse_events == [(False, False, False, 10, -20, 0)]
            assert sink.keyboard_states and uinput.KEY_ENTER in sink.keyboard_states[0]
            assert ping_seen, "host ping loop never delivered an encrypted ping"
        finally:
            client.close()
    finally:
        server.close()


def test_auth_rejected_wrong_proof():
    sink = FakeSink()
    server = _start_server(sink, "1234")
    try:
        client = socket.create_connection(("127.0.0.1", server.getsockname()[1]), timeout=5)
        try:
            hello = _receive_line(client)
            hello_json = json.loads(hello)
            salt = __import__("base64").decodebytes(hello_json["salt"].encode())
            wrong = SessionCrypto("9999", salt)
            client.sendall((wrong.encrypt(b'{"t":"auth"}') + "\n").encode())
            client.settimeout(3)
            assert client.recv(4096) == b"", "server must drop unauthenticated clients"
            assert not sink.mouse_events
        finally:
            client.close()
    finally:
        server.close()


def test_insecure_mode_is_plaintext():
    sink = FakeSink()
    server = _start_server(sink, None)
    try:
        client = socket.create_connection(("127.0.0.1", server.getsockname()[1]), timeout=5)
        try:
            client.sendall(b'{"t":"hello","app":"WatchMouse","v":1}\n')
            client.sendall(b'{"t":"mouse","l":0,"r":0,"m":0,"dx":5,"dy":5,"w":0}\n')
            line = _receive_line(client)
            assert json.loads(line) == {"t": "ping"}, line
            assert sink.mouse_events == [(False, False, False, 5, 5, 0)]
        finally:
            client.close()
    finally:
        server.close()