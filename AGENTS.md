# AGENTS.md — MG4 ABRP Telemetry

Context for AI agents and new contributors working in this repository.

## What this is

An Android Automotive app for the **MG4 (SAIC eh32)** that reads vehicle telemetry and
pushes it to A Better Route Planner. It runs on the car's head unit (AAOS 9, MT2712).

Fork of Leon Kernan's `ABRP_Uploader`. See `LICENSE.md` — the licence status is
**unresolved** and that is a real constraint, not paperwork.

## Non-negotiables

1. **Read-only towards the car.** This app must never write a vehicle property. The
   sibling MG4Control project writes settings and gates every write on 0 km/h; this one has
   no business writing at all. A write path is a bug.
2. **A failed read is not a zero.** Getters return `Integer`/`Float`/`Boolean` and `null`
   means "could not read". Null fields are omitted from the payload. Sending 0 for SOC
   tells ABRP the battery is empty and wrecks a live route plan.
3. **Fail closed.** Update origin, APK signature, unknown vehicle state — refuse rather
   than assume.
4. **Never let a credential reach a URL, a log line, or a crash report.**
5. **AAOS 9 must stay stable.** A third-party app crashing the head unit is unacceptable.
   Every car call goes through reflection with try/catch; keep it that way.
6. **Power matters.** The head unit is awake whenever the car is on. No wake locks, no
   alarms. The GPS subscription and the upload tick both cost power — keep them in step.

## Architecture

```
AbrpUploadService   foreground service; scheduler thread does all IPC and HTTP
CarPropertyAdapter  reflection bridge to android.car (not on the compile classpath)
TelemetryPayload    pure JSON building — no Android types, unit-tested
UploadCadence       when a tick actually uploads — pure, unit-tested
UploadSettings      user cadence configuration
UploadLog           last 20 attempts + derived service state — pure, unit-tested
SecurePrefs         credentials in EncryptedSharedPreferences, migrates plaintext
AbrpApi             HTTP; credentials in the POST body, never the query string
MainActivity        configuration, service state, upload log
```

**Put logic in the pure classes.** Anything Android-free can be unit-tested on the JVM;
anything inside the service effectively cannot be tested without a car.

## Source sets

| Set | Contents | Ships in |
|---|---|---|
| `main` | everything shared | all builds |
| `stable` | `UpdateHook` no-op | stable channel |
| `unstable` | `OtaUpdater`, `ApkSignature`, `UpdateHook` | unstable channel |
| `debug` | `VhalProbe` — enumerates the whole VHAL | debug builds only |
| `testUnstable` | OTA policy tests | test only |

The stable channel does not merely disable self-update — the updater is **not in the
APK**.

## Firmware generations

MG4 infotainment ships as SWI68 / SWI69 / SWI131 / SWI132 / SWI133 / SWI165, and they
differ. Property IDs confirmed on one generation are not universal. Anything vehicle-facing
must tolerate a property simply not existing.

**Reference firmware: `SWI68-29958-1300R69`.** Every vendor property ID in
`CarPropertyAdapter` was reverse-engineered from this exact ROM — the SWI68 "R69"
distribution, which is also what the workspace `apks/` dump was decompiled from. So the
`0x216xxxxx` / `0x214xxxxx` IDs (SOC, range) are confirmed for **SWI68 only**. Unlike
MG4Control, this app does not branch per generation, so on another firmware those reads
may silently return nothing (handled safely — the field is omitted — but SOC/range would
be missing). Re-confirm with `VhalProbe` before trusting the IDs on any other generation.

## Working here

```bash
mise run check      # what CI runs: permission gate + lint + unit tests
mise run build
mise run logs       # adb logcat, filtered to this app
```

Gradle needs JDK 17. If a build fails in `JdkImageTransform` complaining about `jlink`,
a stale Gradle daemon started by VS Code's Java extension is being reused —
`pkill -f GradleDaemon` and retry.

## Things that will bite you

- **`android.car` is not on the compile classpath.** Everything car-related is reflection.
  R8 cannot see those uses, so `proguard-rules.pro` keeps them by name.
- **Unit tests get a stub `org.json`** from `android.jar` that returns null. The real
  implementation is added as a test dependency; do not remove it.
- **`service_running` in preferences goes stale-true** after a force-kill. Use
  `AbrpUploadService.isRunning()`.
- **Permission changes fail CI** until allowlisted with a justification.

## Honesty requirements

Most of this cannot be verified off a vehicle. When reporting work, state plainly what was
tested and what was not. "Unit tests pass, not tried on the car" is the expected and
acceptable answer. Do not imply road-testing that did not happen.
