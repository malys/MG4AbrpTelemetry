# MG4 ABRP Telemetry

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

Two channels. Pick one — they install side by side.

| Channel | Auto-update | Use it if |
|---|---|---|
| **Stable** | No. Contains no updater at all. | You want the car to run what you put on it |
| **Nightly** | Yes, from GitHub pre-releases | You are testing and want fixes as they land |

Grab the APK from [Releases](../../releases). Stable builds are the tagged ones; nightlies
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

Release builds are signed from environment variables — `ABRP_KEYSTORE`,
`ABRP_KEYSTORE_PASSWORD`, `ABRP_KEY_ALIAS`, `ABRP_KEY_PASSWORD`. **Never commit a
keystore or a password.**

## Project layout

```
app/src/main      shared code: service, car adapter, telemetry, UI
app/src/stable    no-op update hook — the stable channel cannot self-update
app/src/nightly   OTA updater (origin allowlist + signature check)
app/src/debug     VHAL probe tools, absent from every release build
app/src/test      JVM unit tests
```

## Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md). Short version: this code runs on a moving
vehicle, so changes need tests and a clear account of what you verified on a car and what
you did not.

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
