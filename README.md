# BlueHop

BlueHop brings multipoint-like switching to Bluetooth devices — such as headphones, speakers, and peripherals — that only support a single active connection at a time.

## Why BlueHop?

Many Bluetooth devices refuse new connections while already connected to another phone or computer, forcing you to find the currently connected device, disconnect manually, and then connect from the device you actually want to use.

BlueHop automates that handoff over your local Wi-Fi network:

- **One-tap switching:** Tap a Bluetooth device in BlueHop on the host you want to use it with.
- **Automatic release & connect:** BlueHop finds paired hosts on your LAN via mDNS, asks the host currently holding the device to disconnect it, and immediately connects locally.
- **No cloud or accounts:** Hosts pair directly over Wi-Fi using ephemeral ECDH (P-256) with 6-digit SAS verification and mutual HMAC key confirmation, and authenticate release requests with per-peer HMAC-SHA256 keys.

## Supported Platforms

| Platform | Status |
| --- | --- |
| Android | Supported (Android 17 / SDK 37+) |
| Linux | Pairing CLI (`linux/`), host daemon coming soon |
| Windows / macOS / iOS / others | Pull requests welcome! |

If you'd like to add support for another platform (Windows, macOS, iOS, etc.), feel free to open a pull request — the [wire protocol](protocol/README.md) is simple HTTP/JSON + ECDH/HKDF + HMAC-SHA256 over mDNS.

### Android

BlueHop requires **Android 17 (SDK 37) or newer**. Android 17 is the first version of Android that lets regular apps connect and disconnect your paired Bluetooth devices on your behalf, which wasn't possible on older versions without root or system-level workarounds.
