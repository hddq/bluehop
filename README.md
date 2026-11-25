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
| Linux | Supported (BlueZ over D-Bus, `linux/` CLI & host daemon) |
| Windows / macOS / iOS / others | Pull requests welcome! |

If you'd like to add support for another platform (Windows, macOS, iOS, etc.), feel free to open a pull request — the [wire protocol](protocol/README.md) is simple HTTP/JSON + ECDH/HKDF + HMAC-SHA256 over mDNS.

### Android

BlueHop requires **Android 17 (SDK 37) or newer**. Android 17 is the first version of Android that lets regular apps connect and disconnect your paired Bluetooth devices on your behalf, which wasn't possible on older versions without root or system-level workarounds.

You can install the Android app from the [F-Droid repository](https://fdroid.link/#https://fdroid.hddq.org/repo?fingerprint=A0B4263C095D99C34423F4171D458930CD4167C154A79D38DFC9F6EC0925A595) or download the APK from [GitHub Releases](https://github.com/hddq/bluehop/releases).

### Linux

BlueHop provides a standalone `bluehop` CLI and host daemon in [`linux/`](linux/) for Linux systems running **BlueZ** (`org.bluez` on the system D-Bus) with local network mDNS access.

Prebuilt static binaries for `linux/amd64` and `linux/arm64` are attached to [GitHub Releases](https://github.com/hddq/bluehop/releases) alongside the Android APK (verified with `SHA256SUMS.txt`), or you can build from source with Go:

```sh
cd linux
go build -o bluehop ./cmd/bluehop
```

Common commands:

- `bluehop devices` — list paired Bluetooth devices
- `bluehop pair --listen` / `bluehop pair` — pair with another BlueHop host over Wi-Fi
- `bluehop serve` — run the holder server so other hosts can request releases
- `bluehop grab <mac-or-name>` — release a Bluetooth device from the current holder and connect locally
- `bluehop peers [list|scan|remove]` — manage paired BlueHop hosts
- `bluehop version` — print the CLI version

#### Nix / NixOS

The repository flake exports `packages.<system>.bluehop` (as `default` for `x86_64-linux` and `aarch64-linux`), `overlays.default`, and `nixosModules.default` (`nixosModules.bluehop`).

Use `github:hddq/bluehop/stable` for the latest stable release (automatically updated on each release tag), `github:hddq/bluehop/vX.Y.Z` to pin a specific tag, or `github:hddq/bluehop` for `master`.

Run or install the CLI with flakes:

```sh
nix run github:hddq/bluehop/stable -- devices
nix profile install github:hddq/bluehop/stable
```

To run the `bluehop serve` daemon on NixOS, add the flake input and enable `services.bluehop`:

```nix
# flake.nix
{
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    bluehop.url = "github:hddq/bluehop/stable";
  };

  outputs = { nixpkgs, bluehop, ... }: {
    nixosConfigurations.my-host = nixpkgs.lib.nixosSystem {
      system = "x86_64-linux";
      modules = [
        bluehop.nixosModules.default
        {
          hardware.bluetooth.enable = true;

          services.bluehop = {
            enable = true;
            openFirewall = true; # opens holder (8765) and pairing (8766) TCP ports + mDNS UDP port (5353)
            # listenAddress = ":8765";
            # deviceName = "my-host";
            # storePath = "/path/to/peers.json";
          };
        }
      ];
    };
  };
}
```

`services.bluehop` runs as a systemd user service (`systemctl --user status bluehop`), so by default it automatically shares `~/.config/bluehop/peers.json` with interactive `bluehop pair`, `bluehop peers`, and `bluehop grab` commands. If you set a custom `storePath`, pass the matching path to interactive commands via `--store` (for example, `bluehop pair --store /path/to/peers.json`).
