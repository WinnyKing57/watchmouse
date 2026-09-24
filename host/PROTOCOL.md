# WatchMouse transport protocol

The input channel between the Wear OS watch and a "receiver" (Linux host, Windows
server or Android companion app) is a plain TCP socket carrying **newline-delimited
JSON**, exactly one message per line.

Default port: **8888**.

## Versioning

The receiver is the session owner and announces the protocol version:

| Version | Description                                        | Security          |
| ------- | -------------------------------------------------- | ----------------- |
| 1       | Plaintext JSON (legacy)                            | none              |
| 2       | Encrypted transport, PIN-authenticated (current)   | AES-128-GCM       |

The watch accepts only the version announced by the receiver. A v2-safe server must
refuse to negotiate v1 unless explicitly started in `--insecure` mode, and must never
downgrade a v2 client below its own version.

## Protocol v2

### Handshake (server -> client)

The receiver generates a random 16-byte salt per connection and sends, in clear text:

```json
{"t":"hello","app":"WatchMouseHost","v":2,"salt":"<base64 16 bytes>"}
```

### Session key

Both sides derive the same AES-128 session key:

```
key = HKDF-SHA256(
    ikm  = PIN as UTF-8,
    salt = the 16-byte salt from hello,
    info = "watchmouse-v2",
    LEN  = 16)             # AES-128
```

HKDF follows RFC 5869 (HMAC-SHA256 extract + expand).

### Client authentication

The client replies with its first **encrypted frame**:

```json
{"t":"auth"}
```

The server decrypts it; if the tag verifies and the message is `auth`, the client is
authenticated (it necessarily knows the PIN). Any failure closes the connection.

### Encrypted frames

After the handshake **every** message — mouse, key, ping, welcome, cmd — is wrapped:

```
frame = base64( nonce[12] || AES-128-GCM(key, nonce, plaintextJSON) )
```

- GCM tag is 128 bits, included in the ciphertext.
- AAD is empty; a fresh random `nonce` per message.
- A fresh salt per connection yields a fresh key, so frames cannot be replayed across
  sessions. Sender authentication and integrity both come from the original PIN proof.

### Pings

The receiver sends `{"t":"ping"}` (encrypted) every 5 seconds. The watch treats any
incoming line as a staleness refresh and reconnects on timeout/EOF.

## Message types

Both protocols share these payloads (JSON, after decryption in v2):

| Type    | Summary                 | Example                                              |
| ------- | ----------------------- | ---------------------------------------------------- |
| `mouse` | Relative pointer state  | `{"t":"mouse","l":0,"r":0,"m":0,"dx":3,"dy":-2,"w":0}` |
| `key`   | Keyboard state          | `{"t":"key","mod":0,"k":[40,0,0,0,0,0]}` (6 HID codes) |
| `cmd`   | Receiver command, server -> watch | `{"t":"cmd","a":"update"}`                    |

## Reference implementations

Keep the three implementations byte-for-byte compatible whenever this file changes:

- Watch client: `app/.../net/ProtocolCrypto.java`, `app/.../net/NetDataSender.java`
- Linux host: `host/linux/watchmouse_host.py` (shared vector asserted in
  `host/linux/test_watchmouse_proto.py` and `app/src/test/.../ProtocolCryptoTest.java`)
- Companion: `companion/.../ProtocolCrypto.java`, `companion/.../TcpInputServer.java`