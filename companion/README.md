# WatchMouse Companion (Android)

Companion app to run on an **Android phone or tablet** so the Wear OS watch can control it over
the local network instead of Bluetooth HID.

The watch connects to this app over TCP and the app turns the input into real **touch gestures**
through the Accessibility API (`dispatchGesture`).

## How it works

- The watch's "Network transport" must be enabled (watch → Network target → toggle).
- This app listens on TCP port `8888` and prints the device's Wi-Fi IP addresses.
- Enter one of those IPs on the watch. The watch keeps reconnecting automatically.
- Gesture mapping:
  - Left click → tap at the (logical) cursor position.
  - Left button held while moving → drag gesture.
  - Right click → long-press.
  - Middle click → back.
  - Wheel → vertical swipe.
  - Keyboard → not injected yet (see limitations).

## How to use

1. Install and open the app; enter your Wi-Fi network if asked.
2. Press **Start receiver**.
3. Enable the accessibility service for this app: *Settings → Accessibility →
   WatchMouse Companion* (required for gestures).
4. On the watch: *Network target* → enable network transport, enter the IP shown by the app
   (port `8888`), tap the network target entry, then pick an input mode.

## Protocol

Newline-delimited JSON over a plain TCP socket (see `../host/README.md` for the full spec).

Watch → device:

```
{"t":"hello","app":"WatchMouse","v":1}
{"t":"mouse","l":1,"r":0,"m":0,"dx":-3,"dy":5,"w":0}
{"t":"key","mod":0,"k":[40,0,0,0,0,0]}
```

Device → watch:

```
{"t":"hello","app":"WatchMouseCompanion","v":1}
{"t":"ping"}
```

## Limitations

- Relative "mouse" movement does not exist on Android; the app emulates a logical cursor.
- Keyboard text input is not injected yet (needs a focused editable field + `ACTION_SET_TEXT`).
- The accessibility service keeps the process alive while enabled, so the receiver keeps running
  in the background even when the app UI is closed.