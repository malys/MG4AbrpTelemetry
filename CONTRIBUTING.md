# Contributing

This app runs on a car. That single fact drives everything below.

## Ground rules

1. **The app never writes to the vehicle.** It is read-only towards the car. A patch that
   introduces a write will be rejected regardless of how useful the feature is.
2. **A property that cannot be read is omitted, never defaulted.** Sending 0 for an
   unreadable battery level tells ABRP the car is empty and corrupts the user's route
   plan. Nullable in, absent from the payload.
3. **Fail closed on anything security-shaped.** Unknown update origin, mismatched
   signature, unreadable state: refuse, do not guess.
4. **Say what you did not verify.** Most of this code can only be fully tested on a
   vehicle. "Builds and unit tests pass, not tried on the car" is a perfectly good PR
   note. Silence implying it was road-tested is not.

## Before opening a PR

```bash
mise run check      # permission gate + lint + unit tests
```

or, without mise:

```bash
bash .github/security/check-permissions.sh
./gradlew testStableDebugUnitTest testNightlyDebugUnitTest lintStableDebug
```

New behaviour needs a unit test. The pure-logic classes — `TelemetryPayload`,
`UploadCadence`, `UploadLog`, `OtaUpdater` — are deliberately free of Android types so
they can be tested on the JVM; put testable logic there rather than in the service.

## Permissions

Any new `uses-permission` fails CI until it is added to
`.github/security/permission-allowlist.txt` **with a justification comment**. This is
deliberate: an app on someone's car should not gain capabilities quietly.

## Channels

- `stable` — tagged releases, no self-update, no updater code in the APK.
- `nightly` — automated pre-releases that update themselves.

If you touch the updater, remember it installs code on a vehicle: the origin allowlist and
the signature check are the whole defence.

## Commit messages

Explain **why**, not what — the diff already says what. If you fixed something subtle,
say what the failure looked like, so the next person recognises it.

## Licensing

Read [`LICENSE.md`](LICENSE.md) first. The project's licence status is unresolved because
the upstream fork source has none. Contributions are accepted on the understanding that
they are offered under MIT once that is settled.
