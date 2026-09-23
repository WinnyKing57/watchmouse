# WatchMouse receivers ("hosts")

The watch stops being blocked by the broken Bluetooth HID HAL and instead sends its
input over your Wi-Fi network through a very small TCP protocol. Any computer or
phone on the same network can run a receiver.

## Protocol

TCP, port **8888** by default, newline-delimited JSON.

Watch → receiver:

```
{"t":"hello","app":"WatchMouse","v":1}
{"t":"mouse","l":0,"r":0,"m":0,"dx":1,"dy":-2,"w":0}
{"t":"key","mod":0,"k":[40,0,0,0,0,0]}
```

* `mouse`: relative movement (`dx`,`dy`), wheel (`w`) and the **current** button
  state (`l`,`r`,`m` — 1 pressed / 0 released). Each message is a full state.
* `key`: modifier bitmask (`mod`, see table below) + up to 6 HID usage codes.

Receiver → watch:

```
{"t":"hello","app":"<name>","v":1}
{"t":"ping"}   every ~5 s — this keeps the watch's stale-connection watchdog alive
```

The watch reconnects automatically every 2–30 s and is fire-and-forget: it never
parses receiver traffic, it just uses it to detect dead connections.

### Modifier bitmask (`mod`)

| bit | key     | bit | key      |
|----:|---------|----:|----------|
| 0   | Left Ctrl | 4 | Right Ctrl |
| 1   | Left Shift| 5 | Right Shift |
| 2   | Left Alt  | 6 | Right Alt  |
| 3   | Left GUI (Win/Super) | 7 | Right GUI |

### Key codes

Standard USB HID keyboard usage codes are sent in `k[]` (0 = no key): `a`–`z`
(0x04–0x1D), `0`–`9` (0x1E–0x27), punctuation (0x2C–0x38), and the specials used
by the watch: Enter 40, Esc 41, Backspace 42, Tab 43, Space 44,
Right 79, Left 80, Down 81, Up 82.

## Receivers

### Linux (`linux/watchmouse_host.py`)

Real mouse + keyboard through the `uinput` kernel module. Every relative move is
applied immediately, buttons are latched, and keyboard state is diffed so repeated
messages do not re-send the same key.

Setup:

```bash
sudo modprobe uinput
sudo chmod 666 /dev/uinput              # or add yourself to the input group + udev rule
pip install python-uinput               # or apt install python3-uinput
sudo cp linux/watchmouse_host.service /etc/systemd/system/
sudo systemctl enable --now watchmouse-host
```

Run manually:

```bash
python linux/watchmouse_host.py --port 8888
```

### Windows (`windows/`)

`SendInput`-based PowerShell/C# receiver. **Written but not tested** (no Windows
build available): see `windows/README.md`. Mouse uses `SetCursorPos` + `SendInput`
relative moves; keyboards use virtual-key mapping.

### Android (`../companion/`)

The companion app turns the input into touch gestures through the Accessibility API
(tap, drag, long-press, swipe, back). Keyboard text injection is not implemented.

## Feature notes / next steps

- [ ] Test the Linux host with a real watch + PC.
- [ ] Windows host: run it on a Windows machine, tune the HID→VK/LVK key map.
- [ ] Android: type into a focused editable field (`ACTION_SET_TEXT`).
- [ ] Auth: optional passphrase in the `hello` handshake (LAN-only today).
- [ ] Discover receivers automatically (mDNS/Bonjour) so no IP entry is needed.
- [ ] Keep-alive from the watch so half-open sockets on the receiver side are
      cleaned up faster.