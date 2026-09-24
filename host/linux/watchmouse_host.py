"""WatchMouse Linux host.

Turns the TCP input sent by the Wear OS watch into a real mouse + keyboard on a
Linux desktop, using the uinput kernel module.

Requirements
------------
* Kernel module:                sudo modprobe uinput
* Write access to /dev/uinput:  sudo chmod 666 /dev/uinput
                                (or add your user to the "input" group and udev)
* Python modules:               pip install python-uinput python3-cryptography

Usage
-----
    python watchmouse_host.py --pin 1234 [--port 8888] [--bind 0.0.0.0]

The watch must use the network transport and point at this machine (port 8888
by default) with the same PIN. The encrypted protocol (v2) is shared with the
companion/ Android app and the host/windows server (see host/PROTOCOL.md).

Legacy plaintext mode:
    python watchmouse_host.py --insecure
"""

import argparse
import base64
import hmac
import hashlib
import json
import logging
import os
import socket
import secrets
import threading
import time

import uinput

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

# BUS_USB is not exported by the Debian python3-uinput package.
BUS_USB = getattr(uinput, "BUS_USB", 0x03)

LOG = logging.getLogger("watchmouse-host")

PROTOCOL_VERSION = 2
KDF_INFO = b"watchmouse-v2"
KEY_LEN = 16
NONCE_LEN = 12
TAG_LEN = 16

PING = {"t": "ping"}
WELCOME = {"t": "welcome"}
_COMPACT = (",", ":")


def _dumps(message):
    return json.dumps(message, separators=_COMPACT)

# HID usage code -> Linux key code (from KeyboardHelper's keyMap + special keys).
HID_TO_LINUX = {
    # a..z
    0x04: uinput.KEY_A, 0x05: uinput.KEY_B, 0x06: uinput.KEY_C, 0x07: uinput.KEY_D,
    0x08: uinput.KEY_E, 0x09: uinput.KEY_F, 0x0A: uinput.KEY_G, 0x0B: uinput.KEY_H,
    0x0C: uinput.KEY_I, 0x0D: uinput.KEY_J, 0x0E: uinput.KEY_K, 0x0F: uinput.KEY_L,
    0x10: uinput.KEY_M, 0x11: uinput.KEY_N, 0x12: uinput.KEY_O, 0x13: uinput.KEY_P,
    0x14: uinput.KEY_Q, 0x15: uinput.KEY_R, 0x16: uinput.KEY_S, 0x17: uinput.KEY_T,
    0x18: uinput.KEY_U, 0x19: uinput.KEY_V, 0x1A: uinput.KEY_W, 0x1B: uinput.KEY_X,
    0x1C: uinput.KEY_Y, 0x1D: uinput.KEY_Z,
    # 1..0
    0x1E: uinput.KEY_1, 0x1F: uinput.KEY_2, 0x20: uinput.KEY_3, 0x21: uinput.KEY_4,
    0x22: uinput.KEY_5, 0x23: uinput.KEY_6, 0x24: uinput.KEY_7, 0x25: uinput.KEY_8,
    0x26: uinput.KEY_9, 0x27: uinput.KEY_0,
    # punctuation
    0x2C: uinput.KEY_SPACE, 0x2D: uinput.KEY_MINUS, 0x2E: uinput.KEY_EQUAL,
    0x2F: uinput.KEY_LEFTBRACE, 0x30: uinput.KEY_RIGHTBRACE, 0x31: uinput.KEY_BACKSLASH,
    0x33: uinput.KEY_SEMICOLON, 0x34: uinput.KEY_APOSTROPHE, 0x35: uinput.KEY_GRAVE,
    0x36: uinput.KEY_COMMA, 0x37: uinput.KEY_DOT, 0x38: uinput.KEY_SLASH,
    # special keys
    40: uinput.KEY_ENTER, 41: uinput.KEY_ESC, 42: uinput.KEY_BACKSPACE,
    43: uinput.KEY_TAB, 44: uinput.KEY_SPACE,
    # arrows
    79: uinput.KEY_RIGHT, 80: uinput.KEY_LEFT, 81: uinput.KEY_DOWN, 82: uinput.KEY_UP,
}

# KeyboardHelper.Modifier bitmask positions -> Linux modifier keys.
MODIFIER_TO_LINUX = [
    uinput.KEY_LEFTCTRL,
    uinput.KEY_LEFTSHIFT,
    uinput.KEY_LEFTALT,
    uinput.KEY_LEFTMETA,
    uinput.KEY_RIGHTCTRL,
    uinput.KEY_RIGHTSHIFT,
    uinput.KEY_RIGHTALT,
    uinput.KEY_RIGHTMETA,
]

MOUSE_EVENTS = [
    uinput.REL_X,
    uinput.REL_Y,
    uinput.REL_WHEEL,
    uinput.BTN_LEFT,
    uinput.BTN_RIGHT,
    uinput.BTN_MIDDLE,
]

KEYBOARD_EVENTS = [uinput.KEY_MAX]

_BTN_MAP = {
    "left": uinput.BTN_LEFT,
    "right": uinput.BTN_RIGHT,
    "middle": uinput.BTN_MIDDLE,
}


def hkdf(ikm, salt, info, length):
    """RFC 5869 HKDF-SHA256 (extract + expand) — mirrors ProtocolCrypto (Java)."""
    prk = hmac.new(salt, ikm, hashlib.sha256).digest()
    output = b""
    previous = b""
    counter = 1
    while len(output) < length:
        previous = hmac.new(prk, previous + info + bytes([counter]), hashlib.sha256).digest()
        output += previous
        counter += 1
    return output[:length]


class SessionCrypto:
    """AES-128-GCM session key + framing, mirroring ProtocolCrypto (Java)."""

    def __init__(self, pin, salt):
        self.key = hkdf((pin or "").strip().encode("utf-8"), salt, KDF_INFO, KEY_LEN)
        self._aead = AESGCM(self.key)

    def encrypt(self, message):
        if isinstance(message, str):
            message = message.encode("utf-8")
        nonce = os.urandom(NONCE_LEN)
        ciphertext = self._aead.encrypt(nonce, message, None)
        return base64.b64encode(nonce + ciphertext).decode("ascii")

    def decrypt(self, frame):
        try:
            data = base64.b64decode(frame, validate=True)
            if len(data) < NONCE_LEN + TAG_LEN:
                return None
            nonce, ciphertext = data[:NONCE_LEN], data[NONCE_LEN:]
            return self._aead.decrypt(nonce, ciphertext, None).decode("utf-8")
        except Exception:
            return None


class UinputSink:
    """Owns the uinput device and applies mouse + keyboard state to it."""

    def __init__(self, name):
        self.device = uinput.Device(
            MOUSE_EVENTS + KEYBOARD_EVENTS,
            name=name,
            bustype=BUS_USB,
        )
        self._buttons = {"left": 0, "right": 0, "middle": 0}
        self._pressed = set()
        LOG.info("uinput device '%s' created", name)

    def close(self):
        try:
            self.device.destroy()
        except Exception:
            pass

    def mouse(self, left, right, middle, dx, dy, wheel):
        states = {"left": left, "right": right, "middle": middle}
        for name, current in states.items():
            value = 1 if current else 0
            if value != self._buttons[name]:
                self.device.emit(_BTN_MAP[name], value)
                self._buttons[name] = value
        if dx != 0:
            self.device.emit(uinput.REL_X, dx)
        if dy != 0:
            self.device.emit(uinput.REL_Y, dy)
        if wheel != 0:
            self.device.emit(uinput.REL_WHEEL, wheel)
        self.device.syn()

    def keyboard(self, pressed):
        """Apply the new set of pressed Linux key codes (diff against previous)."""
        for code in self._pressed - pressed:
            self.device.emit(code, 0)
        for code in pressed - self._pressed:
            self.device.emit(code, 1)
        self._pressed = set(pressed)
        self.device.syn()


def parse_modifier_keys(modifier, keys):
    pressed = set()
    for bit, code in enumerate(MODIFIER_TO_LINUX):
        if modifier & (1 << bit):
            pressed.add(code)
    for usage in keys:
        code = HID_TO_LINUX.get(usage)
        if code is not None:
            pressed.add(code)
    return pressed


class Client:
    def __init__(self, sock, addr, sink, pin):
        self.sock = sock
        self.addr = addr
        self.sink = sink
        self.pin = pin
        self._stop = threading.Event()

    def handle(self):
        sock = self.sock
        try:
            if self.pin is None:
                self._handle_plaintext(sock)
            else:
                self._handle_encrypted(sock)
        except OSError as exc:
            LOG.info("client %s disconnected: %s", self.addr, exc)
        finally:
            self._stop.set()
            sock.close()

    def _handle_plaintext(self, sock):
        """Legacy protocol v1: newline-delimited plaintext JSON."""
        LOG.info("client %s: legacy plaintext transport (--insecure)", self.addr[0])
        pinger = threading.Thread(target=self._send_plain, args=(_dumps(PING),), daemon=True)
        pinger.start()
        for line in sock.makefile("r", encoding="utf-8", errors="replace"):
            line = line.strip()
            if not line:
                continue
            if self._stop.is_set():
                break
            try:
                self._handle_message(json.loads(line))
            except ValueError:
                LOG.warning("bad message from %s: %r", self.addr, line)

    def _handle_encrypted(self, sock):
        """Protocol v2: salted hello, PIN auth, AES-128-GCM frames."""
        salt = secrets.token_bytes(16)
        hello = _dumps(
            {"t": "hello", "app": "WatchMouseHost", "v": PROTOCOL_VERSION,
             "salt": base64.b64encode(salt).decode("ascii")}
        )
        sock.sendall((hello + "\n").encode("utf-8"))

        first = self._read_line(sock)
        if first is None:
            LOG.info("client %s closed during handshake", self.addr[0])
            return
        crypto = SessionCrypto(self.pin, salt)
        if os.environ.get("WATCHMOUSE_TRACE"):
            LOG.info(
                "trace: salt=%s key=%s pinLen=%d",
                base64.b64encode(salt).decode("ascii"),
                crypto.key.hex(),
                len(self.pin or ""),
            )
        plaintext = crypto.decrypt(first.strip())
        authorized = plaintext is not None and plaintext.find('"t":"auth"') != -1
        if not authorized:
            LOG.warning("client %s: authentication failed (bad PIN?)", self.addr[0])
            return
        LOG.info("client %s: authenticated, encrypted transport", self.addr[0])

        sock.sendall((crypto.encrypt(_dumps(WELCOME)) + "\n").encode("utf-8"))
        pinger = threading.Thread(
            target=self._send_encrypted, args=(crypto, _dumps(PING)), daemon=True
        )
        pinger.start()
        while not self._stop.is_set():
            line = self._read_line(sock)
            if line is None:
                return
            plaintext = crypto.decrypt(line.strip())
            if plaintext is None:
                LOG.warning("client %s: broken frame, dropping", self.addr[0])
                break
            try:
                self._handle_message(json.loads(plaintext))
            except ValueError:
                LOG.warning("bad message from %s: %r", self.addr, plaintext)

    def _read_line(self, sock, timeout=None):
        """Reads a single newline-terminated ASCII-safe line as raw bytes.

        Logs any partial data if the peer disconnects mid-frame, so a client
        closing during the handshake can be diagnosed.
        """
        old_timeout = sock.gettimeout()
        if timeout is not None:
            sock.settimeout(timeout)
        try:
            data = b""
            while True:
                chunk = sock.recv(1)
                if not chunk:
                    if data:
                        LOG.info(
                            "client %s closed after sending %d bytes: %r",
                            self.addr[0], len(data), data[:96],
                        )
                    return None
                if chunk == b"\n":
                    return data
                data += chunk
                if len(data) > 65536:
                    return None
        except OSError:
            if data:
                LOG.info(
                    "client %s read error after %d bytes: %r",
                    self.addr[0], len(data), data[:96],
                )
            return None
        finally:
            sock.settimeout(old_timeout)

    def _send_encrypted(self, crypto, message):
        while not self._stop.is_set():
            try:
                self.sock.sendall((crypto.encrypt(message) + "\n").encode("utf-8"))
            except OSError:
                return
            time.sleep(5)

    def _send_plain(self, message):
        while not self._stop.is_set():
            try:
                self.sock.sendall((message + "\n").encode("utf-8"))
            except OSError:
                return
            time.sleep(5)

    def _handle_message(self, message):
        kind = message.get("t")
        if kind == "mouse":
            self.sink.mouse(
                bool(message.get("l", 0)),
                bool(message.get("r", 0)),
                bool(message.get("m", 0)),
                int(message.get("dx", 0)),
                int(message.get("dy", 0)),
                int(message.get("w", 0)),
            )
        elif kind == "key":
            pressed = parse_modifier_keys(message.get("mod", 0), message.get("k", []))
            self.sink.keyboard(pressed)
        elif kind in ("hello", "ping", "auth", "welcome"):
            pass
        else:
            LOG.warning("unknown message type %r", kind)


def main():
    ap = argparse.ArgumentParser(description="WatchMouse Linux host (uinput)")
    ap.add_argument("--port", type=int, default=8888, help="TCP port to listen on (default 8888)")
    ap.add_argument("--bind", default="0.0.0.0", help="bind address (default 0.0.0.0)")
    ap.add_argument("--name", default="WatchMouse", help="name of the virtual device")
    ap.add_argument(
        "--pin", default=None,
        help="shared PIN required to connect (encrypted protocol v2)")
    ap.add_argument(
        "--insecure", action="store_true",
        help="accept legacy plaintext connections without a PIN (NOT recommended)")
    args = ap.parse_args()

    pin = (args.pin or "").strip()
    if not pin:
        pin = (os.environ.get("WATCHMOUSE_PIN") or "").strip()
    if not pin and not args.insecure:
        ap.error("set --pin (or the WATCHMOUSE_PIN environment variable), or pass --insecure")
    if not pin:
        pin = None

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

    sink = UinputSink(args.name)
    try:
        server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind((args.bind, args.port))
        server.listen(4)
        mode = "encrypted/PIN" if pin else "plaintext (--insecure)"
        LOG.info("WatchMouse host listening on %s:%d [%s]", args.bind, args.port, mode)
        while True:
            conn, addr = server.accept()
            conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            LOG.info("watch connected from %s:%d", addr[0], addr[1])
            threading.Thread(
                target=Client(conn, addr, sink, pin).handle, daemon=True
            ).start()
    except KeyboardInterrupt:
        pass
    finally:
        sink.close()


if __name__ == "__main__":
    main()