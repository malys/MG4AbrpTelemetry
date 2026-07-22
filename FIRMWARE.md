# Reference firmware

Facts below are extracted directly from the vehicle ROM
`SWI68-29958-1300R69.zip`, not guessed. They ground the emulator profile, the
build config and the vendor property IDs.

## Identity

| | |
|---|---|
| OTA name | `SWI68-29958-1300R69` (`checksoc.txt`) |
| Build fingerprint | `mediatek/mt2712_saic_eh32/mt2712_saic_eh32:9/PPR1.181005.003/25566:user/test-keys` |
| Android | 9 (Pie) — **API 28** |
| Chipset | MediaTek **MT2712** |
| Device | `mt2712_saic_eh32` (eh32 = MG4) |
| Security patch | 2020-07-05 |
| Build tags | **`test-keys`** |
| Screen density | **`ro.sf.lcd_density=160`** (`vendor/build.prop`) |
| OTA format | A/B (`payload.bin`), partitions: `system`, `vendor` |

## Why `test-keys` matters

The ROM is signed and built with the public AOSP **test keys**. That is why the
app-signing keystore is the well-known default (`platform`, password `android`)
— it is not a secret, it is public. Consequences:

- Anyone can produce an APK signed with the same platform certificate, so the
  nightly OTA's "signed like me" check cannot, on its own, prove an APK came
  from us. The real defence there is the https + exact-host GitHub allowlist.
  (Same caveat applies to MG4Control's OTA.)

## Vendor property IDs

The vendor VHAL is `vendor/bin/hw/android.hardware.automotive.vehicle@2.0-service`
(ARM aarch64), backed by `vendor/lib64/android.hardware.automotive.YFvehicle@2.0.so`
("YF" supplier implementation). The property IDs (`0x2160F404` SOC,
`0x2140F41C` range, …) are **compiled into that binary**, not stored in a
readable config, so the human-readable source of truth stays the decompiled
SAIC SDK under the workspace `apks/` — which is decompiled from this same ROM.

These IDs are therefore confirmed for **SWI68 (R69) only**. MG4 ships other
generations (SWI69/131/132/133/165) and the VHAL binary lists many SAIC
platforms (`eh32`, `as33`, `ec32`, `ip42`, …); the IDs may differ there. This
app does not branch per generation — see `CarPropertyAdapter` and `AGENTS.md`.

## Emulator fidelity

A faithful emulator built *from this ROM* is not practical: the system and
vendor images are ARM aarch64 (MT2712), and running a full ARM automotive
system on an x86 host is unusably slow. The `mise` emulator tasks stay the
answer — an API 33 automotive AVD for the car service and an API 28 AVD at the
confirmed **1920×1080 @ 160 dpi** for the screen — with the understanding that
neither exposes the SAIC vendor properties.
