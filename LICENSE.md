# Licensing status

**This project cannot be redistributed under an open-source licence yet. Please read
this before forking, packaging or publishing it.**

## Why

MG4 ABRP Telemetry is a fork of
[leonkernan/ABRP_Uploader](https://github.com/lkernan), written by Leon Kernan.

**The upstream repository never contained a licence file.** Under the Berne Convention,
code published without a licence is *all rights reserved* by default: the author holds
copyright and grants no permission to copy, modify or redistribute. GitHub's terms allow
forking within GitHub, but they do not grant redistribution rights beyond that.

A meaningful part of this repository is still Leon Kernan's original work, so the project
as a whole cannot simply be relicensed by us.

## What this means in practice

| | Status |
|---|---|
| Using the app on your own car | Fine |
| Forking on GitHub, reading, learning from the code | Fine (GitHub ToS) |
| Redistributing APKs or source outside GitHub | **Needs Leon Kernan's permission** |
| Relicensing the whole project under MIT/Apache/GPL | **Needs Leon Kernan's permission** |

## No warranty

Separately from the licensing question above, this software carries **no warranty and no
liability** — see [`DISCLAIMER.md`](DISCLAIMER.md). Resolving the licence would not change
that.

## Contributions made in this fork

Everything authored in this fork — the telemetry correctness work, credential handling,
the vehicle-data layer changes, the cadence and UI work, the build and CI tooling — is
offered by its authors under the **MIT Licence**, on the condition that the upstream
portion is resolved. That offer alone is not enough to make the combined work
redistributable.

## How to resolve this

1. Contact Leon Kernan and ask him to add a licence upstream (MIT is the obvious fit and
   matches the sibling MG4Control project), or to grant this fork an explicit licence.
2. Record the outcome here, replacing this file with the agreed licence text.
3. If he declines or cannot be reached, the alternative is to rewrite the remaining
   original code so that no upstream-copyrighted material is left, and document that.

Until then this file stays as it is. Publishing an MIT `LICENSE` over code we do not hold
the rights to would be worse than having no licence at all — it would tell users they have
permissions that nobody actually granted them.
