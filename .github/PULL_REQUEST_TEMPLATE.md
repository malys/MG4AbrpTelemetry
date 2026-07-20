## What and why

<!-- What changes, and what problem it solves. The diff already says what; explain why. -->

## Verification

<!-- Be specific about what you actually ran. "Not verified on a vehicle" is a fine and
     expected answer — most of this code cannot be fully tested off the car. -->

- [ ] `mise run check` passes (permission gate + lint + unit tests)
- [ ] New behaviour is covered by a unit test
- [ ] Tried on a real MG4 — if yes, which firmware: <!-- e.g. SWI133 -->

## Vehicle-safety checklist

- [ ] This change does not write anything to the vehicle
- [ ] Unreadable properties are omitted from telemetry, not defaulted to a value
- [ ] Any new failure mode fails closed
- [ ] No new `uses-permission` — or it is added to the allowlist with a justification
- [ ] No credential reaches a URL, a log line or a crash report

## Notes for the reviewer

<!-- Anything you are unsure about, or deliberately left out of scope. -->
