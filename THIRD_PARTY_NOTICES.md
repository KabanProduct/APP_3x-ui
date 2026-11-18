# Third-party components

## AndroidLibXrayLite

- Upstream: https://github.com/2dust/AndroidLibXrayLite
- Release: https://github.com/2dust/AndroidLibXrayLite/releases/tag/v26.11.9
- Source: https://github.com/2dust/AndroidLibXrayLite/tree/v26.11.9
- License: LGPL-3.0; text bundled in `app/src/main/assets/licenses/AndroidLibXrayLite-LGPL-3.0.txt`.
- Unmodified upstream `libv2ray.aar`, SHA-256 `cdfef146218233a31a8b52c8b1016557730d3b63fc121d4c84cacea50e669155`.
- Download using `python scripts/fetch-native.py`. To replace/relink, supply a compatible AAR at `app/libs/libv2ray.aar` and rebuild this application's supplied source. To rebuild upstream, follow its tagged README (Go/gomobile, JDK, Android SDK/NDK).

## Xray-core

- https://github.com/XTLS/Xray-core/tree/676eaf66f8f05d4d558ee7a74b4736623d3feeb9
- Version 26.11.9, included by the native wrapper.
- MPL-2.0; text bundled in `app/src/main/assets/licenses/Xray-core-MPL-2.0.txt`.

## Android and Java libraries

The build resolves AndroidX/Compose (Apache-2.0), Kotlin and coroutines (Apache-2.0), OkHttp/Okio (Apache-2.0), ZXing (Apache-2.0), mwiede/JSch (BSD-style), and net.i2p EdDSA (CC0). Their upstream license metadata remains in the dependency artifacts. No code was copied from v2rayNG.

## 3x-ui server

https://github.com/MHSanaei/3x-ui/tree/f727d04f6522bb94a8fb52e8352fdcafb51c11e1

The Android APK does not bundle the panel. On explicit installation it downloads upstream v3.7.0 Linux binaries and verifies the release digest:

- amd64: `0f8dd7baef3458f6591574e24814f322cf7f5e1e27f0a594683745e50be84ec5`
- arm64: `3caf1db1e8b10bb1fa1324c945522690bcf01c533ee75b377268f1c01a3ce896`

The installer in this project is separate from upstream's interactive installation script. It installs a minimal systemd deployment and does not configure ancillary upstream features such as fail2ban or PostgreSQL.
