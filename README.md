# WatchMouse

A fork of [ginkage/wearmouse](https://github.com/ginkage/wearmouse), adapted to control a computer, tablet or Android phone from a Wear OS watch.

This project is a sample for the new Bluetooth HID Device API, which was
introduced in Android P. It implements a simple air mouse and cursor keys
emulation on a Wear OS device.

## Compatibility

This app is only compatible with Wear OS devices running Android P and above.
You can use it to connect with pretty much any laptop or desktop computer,
running Windows, Linux, Chrome OS, Mac OSX, Android TV, without any additional
software, as long as it has a Bluetooth receiver.

## Installation

The latest signed build is published on the [releases page](https://github.com/WinnyKing57/watchmouse/releases).

The **first** launch requires a one-time ADB sideload (the watch has no browser to
download an APK). After that, all updates are delivered over-the-air by the built-in
OTA updater (À propos screen -> Check for updates), no cable needed.

1. On the watch: *Settings > System > About*, tap the build number 7 times to
   enable Developer options.
2. *Developer options > ADB debugging* on (and *Debug over Wi-Fi* if offered).
3. Keep the watch on your wrist (or on the charger) and on the same Wi-Fi as your
   computer, otherwise Wi-Fi switches off.
4. Get the IP from *Settings > System > About > IP address*.
5. From this repo:
   ```
   scripts/install-watch.sh 192.168.1.42:5555
   ```
   If the watch asks for a code (Android 11+): `adb pair 192.168.1.42:39711`
   first, enter the code shown on screen, then re-run the install script.

Signing note: releases are signed with a production keystore; the OTA updater
will only accept later builds signed with the same key.

## How to use this app

1. After launch, the first thing you see is the paired devices list.
    * You probably want to pair a laptop or a desktop computer if it is the
       first time you've launched the app.
1. If you tap on "Available devices" option, you'll see nearby devices that
   you can try pairing with.
    * It's a good idea to try pairing with a laptop or a desktop computer.
    * At this screen, the Wear OS device is also discoverable for the nearby
       devices, so you can try searching for it on the other device as well.
1. When you have a paired device, tapping on it will give you an option to
   connect to it. This will bring up the Input Mode dialog.
    * Sometimes this dialog pops up immediately after pairing, saving you a few
       taps.
1. You can now choose between Mouse (the air mouse), Cursor Keys and Keyboard
   Input modes, and also can change a few settings.
    * Every mode (except for the keyboard input) has a welcome screen that
       describes the way to use it.
       
## Navigating the source code

The main sections of the code tree are:

1. /bluetooth
    * Everything related to the HID Device emulation, like report descriptor,
       app configuration, and everything else that uses the new Bluetooth HID
       Device API.
1. /input
    * Handy utilities for sending actual input events, e.g. converting
       characters of an en-US keyboard to scan codes, or converting Rotation
       Vector sensor events to mouse pointer movements.
1. /sensors
    * Implements orientation tracking using Google VR library. The GVR-based
       approach produces results that are a drop-in replacement for the 
       Rotation Vector sensor, but doesn't rely on the watch manufacturer's
       implementation of that sensor.
1. /ui
    * The user interface

## Credits

This project is a fork of [WearMouse by ginkage](https://github.com/ginkage/wearmouse).
All credits go to the original author for the initial design and implementation.
