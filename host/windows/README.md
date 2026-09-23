# WatchMouse Windows host (untested)

Command-line receiver that injects a real mouse + keyboard on Windows via
`SendInput` (`user32.dll`, P/Invoke from a small `Add-Type` C# payload, no
external tools needed).

```
powershell -ExecutionPolicy Bypass -File watchmouse_host.ps1
```

Defaults: listen on `0.0.0.0:8888` (same defaults as the Linux host and the
Android companion). Run it as Administrator if elevation ever becomes an issue
(plain SendInput normally works without).

### Behaviour

* Mouse moves are **relative** (`MOUSEEVENTF_MOVE`), matching the watch's
  `dx`/`dy`. Buttons and wheel use `MOUSEEVENTF_*`; one watch wheel unit = 120.
* Keys are injected as **Set-1 scan codes** (`KEYEVENTF_SCANCODE` +
  `KEYEVENTF_EXTENDEDKEY` for arrows / right-hand modifiers / GUI key), so the
  active keyboard layout resolves the character — this keeps `_+-//` etc.
  correct on AZERTY and other layouts.
* One watch at a time; the listener accepts serial connections. Any key still
  held when the watch disconnects is released.

### Not tested

This file was written without a Windows machine to test on. Things to verify on
real hardware:

* `Add-Type` compiles on your PowerShell (5.1 and 7).
* Relative movement speed / acceleration feel.
* Wheel direction (swap `* 120` to `* -120` if it scrolls the wrong way).
* Extended keys (4D/4B/50/48 with the `Extendedkey` prefix) on your keyboard.
* `StreamReader` read-timeout actually throws (so pings go out) while a watch is
  connected; if it never pings, the watch reconnects every few seconds anyway.