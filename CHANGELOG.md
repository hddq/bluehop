# Changelog

All notable changes to BlueHop across all clients are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.3.1] - 2026-11-25

### Pairing

- Default manual pairing port to 8766.

### Nix

- Run BlueHop as a user service.

## [0.3.0] - 2026-11-24

### Protocol

- Use static default pairing port 8766.

### Nix

- Package BlueHop and add a NixOS service module.

### Android

- Allow companion background network access.

### CI

- Update stable branch for tagged Nix releases.

## [0.2.0] - 2026-11-21

### Linux

- Add Linux client.

### Android

- Support manual pairing addresses and IPv6 pairing servers.
- Adopt ECDH (P-256) + 6-digit SAS verification and mutual key confirmation for pairing.
- Improve UI.

## [0.1.0] - 2026-11-18

- Initial Android release.

[Unreleased]: https://github.com/hddq/bluehop/compare/v0.3.1...HEAD
[0.3.1]: https://github.com/hddq/bluehop/releases/tag/v0.3.1
[0.3.0]: https://github.com/hddq/bluehop/releases/tag/v0.3.0
[0.2.0]: https://github.com/hddq/bluehop/releases/tag/v0.2.0
[0.1.0]: https://github.com/hddq/bluehop/releases/tag/v0.1.0
