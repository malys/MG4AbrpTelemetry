# MG4 ABRP Telemetry

<p align="center"><img src="docs/logo.svg" width="440" alt="MG4 ABRP Telemetry"></p>

[![Tests](https://github.com/malys/MG4AbrpTelemetry/actions/workflows/tests.yml/badge.svg)](https://github.com/malys/MG4AbrpTelemetry/actions/workflows/tests.yml)
[![Security](https://github.com/malys/MG4AbrpTelemetry/actions/workflows/security.yml/badge.svg)](https://github.com/malys/MG4AbrpTelemetry/actions/workflows/security.yml)
[![Unstable](https://github.com/malys/MG4AbrpTelemetry/actions/workflows/unstable.yml/badge.svg)](https://github.com/malys/MG4AbrpTelemetry/actions/workflows/unstable.yml)
[![Release](https://img.shields.io/github/v/release/malys/MG4AbrpTelemetry?include_prereleases&sort=semver)](https://github.com/malys/MG4AbrpTelemetry/releases)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

Sends live telemetry from an **MG4 (SAIC eh32)** to
[A Better Route Planner](https://abetterrouteplanner.com) — battery level, speed, range,
temperature, charging state and position — straight from the car's own APIs.

No OBD dongle. No Home Assistant. No phone in the loop. The app runs on the car's head
unit and talks to ABRP directly.

> ⚠️ **No warranty, no liability.** This software is provided "as is" and runs on a
> **vehicle**. Installing it is your decision and your risk — see
> [`DISCLAIMER.md`](DISCLAIMER.md). Not affiliated with SAIC, MG Motor or Iternio.

> **Fork notice.** This is a fork of Leon Kernan's `ABRP_Uploader`, substantially
> reworked. **Read [`LICENSE.md`](LICENSE.md) before redistributing anything** — the
> upstream project carries no licence, which limits what may legally be published.

---

## What it does

| Signal | Source |
|---|---|
| State of charge, range | `CarPropertyManager` (EV battery properties) |
| Speed, gear, parked state | Vehicle speed / gear selection |
| Charging, DC fast charging | Charge port state + charge rate heuristic |
| Outside and cabin temperature | HVAC properties |
| Position, elevation, heading | GPS |

A property the car will not give up is **omitted** from the payload, never sent as zero —
so ABRP is never told the battery is empty because a read failed.

**The app never writes to the car.** It only reads. Any write path would be a bug; see
[`SECURITY.md`](SECURITY.md).

## Install

The MG4 head unit hides Settings and APK install. The known route in:
The MG4 head unit has no visible way to open Settings or install an APK. The known route
in (via the on-screen keyboard) is:

1. Open any app with a text field and tap it to raise the on-screen keyboard — e.g. the
   Amazon Music app's email/login field.
2. **Long-press** the comma `,` (or the `@`) key on the keyboard.
3. Tap **"Language settings"**.
4. Tap the **search** icon in the top bar and type **`backup`**. It opens an empty page —
   now press the **back** arrow, and you land in Android's Settings panel.
5. Enable **Developer options**, and turn on **"Install unknown apps"** (unknown sources).
6. In Settings, search **`storage`** — you now have access to internal storage and the USB
   key. Navigate to the APK and tap it to install.

> ⚠️ You are enabling developer options and sideloading on a car. Do this **parked**, and
> only with an APK you trust. See [DISCLAIMER.md](DISCLAIMER.md).

Two channels. Pick one — they install side by side.

| Channel | Auto-update | Use it if |
|---|---|---|
| **Stable** | No. Contains no updater at all. | You want the car to run what you put on it |
| **Unstable** | Yes, from GitHub pre-releases | You are testing and want fixes as they land |

Grab the APK from [Releases](../../releases). Stable builds are the tagged ones; unstable builds
are marked pre-release.

### Getting the APK onto the car

The MG4 has no visible file manager. To reach it:

1. Open **Bluetooth Settings** and select the car name.
2. Long-press the **comma** key until the keyboard settings appear.
3. **Android Keyboard Settings → Languages**.
4. Search for `file` in the search box at the top.
5. That gives you the Files app.

From there, open your USB stick and tap the APK.

### First run

1. Open the app.
2. Get an API key from
   [ABRP's telemetry page](https://abetterrouteplanner.com/home/app/api-keys/telemetry)
   and a token from your ABRP account.
3. Paste both, press **Test** (read-only — it does not send telemetry), then **Save**.
4. Turn the switch on. The service restarts with the car from then on.

## Configuration

| Setting | Default | Notes |
|---|---|---|
| Upload frequency | 60 s | 15 / 30 / 60 / 120 / 300 s. Applies while driving |
| More often at low battery | On | Tightens to 15 s below the threshold |
| Low battery threshold | 20 % | Editable, 1–99 |
| Service on/off | Off | Also the "stop uploading" control |

Parked and unplugged, the app drops to **one upload every 15 minutes** on its own,
whatever the setting. A state change — parked, plugged in, unplugged — always uploads
immediately.

### Power

The app holds **no wake lock** and schedules **no alarms**, so it cannot wake a sleeping
head unit; it costs nothing while the car is off. While the car is on, both the upload tick
and the GPS subscription cost power, and the GPS interval follows your configured
frequency.

At defaults that is roughly **59 uploads/hour driving** and **3/hour parked**.

## Building

Requires JDK 17 and the Android SDK. With [mise](https://mise.jdev):

```bash
mise install          # JDK
mise run bootstrap    # Android SDK + local.properties
mise run check        # permission gate + lint + tests
mise run build        # debug APK
```

Without mise: set `JAVA_HOME` to a JDK 17, write `sdk.dir` into `local.properties`, then
`./gradlew assembleStableDebug`.

Release builds are signed from environment variables. These sign the **APK** and have
nothing to do with your ABRP API key, which you type into the app itself:

| Variable | What it is |
|---|---|
| `SIGNING_KEYSTORE` | Path to the keystore file (CI decodes `SIGNING_KEYSTORE_BASE64` into one) |
| `SIGNING_STORE_PASSWORD` | Opens the keystore container |
| `SIGNING_KEY_ALIAS` | Which key inside it to sign with (default `platform`) |
| `SIGNING_KEY_PASSWORD` | Opens that particular key — a keystore protects each key separately |

**Never commit a keystore or a password.** Put them in `mise.local.toml`, which is
gitignored, or in GitHub Actions secrets.

## Project layout

```
app/src/main      shared code: service, car adapter, telemetry, UI
app/src/stable    no-op update hook — the stable channel cannot self-update
app/src/unstable  OTA updater (origin allowlist + signature check)
app/src/debug     VHAL probe tools, absent from every release build
app/src/test      JVM unit tests
```

## Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md). Short version: this code runs on a moving
vehicle, so changes need tests and a clear account of what you verified on a car and what
you did not.

## The MG4 app suite

Part of a small set of projects for the SAIC MG4 (AAOS 9, MT2712), all sharing the
**MG4Hardware** vehicle layer:

| Project | Role |
|---|---|
| [MG4Hardware](https://github.com/malys/MG4Hardware) | Shared vehicle-access layer: reflection hardware layer, 0 km/h safety gate, driving models, condition/action catalogue + firmware matrix |
| [MG4Control](https://github.com/malys/MG4Control) | Drive-profile manager; applies settings at startup; owns the signature-protected TaskerBridge |
| [MG4Tasker](https://github.com/malys/MG4Tasker) | Rule engine — *when* conditions *then* actions — driving the car through MG4Control |
| [MG4AbrpTelemetry](https://github.com/malys/MG4AbrpTelemetry) | Live telemetry uploader to A Better Route Planner |

Common toolchain: **AGP 9.1.1 / Gradle 9.3.1 / compileSdk 36 / JDK 17**. Each app consumes
MG4Hardware as a git submodule (`MG4Hardware/lib` as the `:mg4hardware` subproject).

## Legal

- [`DISCLAIMER.md`](DISCLAIMER.md) — no warranty, no liability, not affiliated with the
  carmaker or with ABRP.
- [`LICENSE.md`](LICENSE.md) — unresolved licence status inherited from the fork source.
  Read it before redistributing.
- [`SECURITY.md`](SECURITY.md) — how to report a vulnerability privately.

## Credits

- Original app: **Leon Kernan** — the car-API approach and the first working uploader.
- ABRP telemetry API: [Iternio](https://documenter.getpostman.com/view/7396339/SWTK5a8w).
- Sibling project: **MG4Control**, whose security and CI patterns this repo reuses.
