# REVIEW.md

Rules for reviewing pull requests in this repository (the NFE fork of OpenEMS). Follow `AGENTS.md` and `NFE_DEVELOPMENT.md` first; this file adds what to always flag and what to skip.

## Always flag (blocking)
- **Discharge safeguards weakened.** The SRNE battery inverter driver must keep: the start threshold (do not start a window below the configured SoC), the SoC floor, the hold cap, and the disarm-only path while the machine state is not verified. Schedule registers are written only when the machine state is verified. No code path may bypass these, even behind a flag.
- **Changing the interfaces a component implements.** Adding or removing an interface (for example `ClockProvider`) changes how OSGi registers the component and has broken the edge before (a recursion that needed a rollback). Any such change needs a regression test and a real-edge smoke test noted in the PR.
- **Unbounded writes or retries.** Modbus writes must be rate limited with a cooldown; flag retry loops, tight polling and anything that can flood the serial line.
- **Behaviour change without a test.** New driver rules, limits or state transitions need unit tests that fail without the change.
- **Silent failure.** Errors swallowed or logged at debug, or a control path that continues after a failed read.

## Also check
- Checkstyle has zero warnings, and dependency or wrapper bumps are in the PR description with a reason.
- Config defaults for a live site match the gateway repo (image digest, `CONFIG_VERSION`); changes that need a gateway update say so.
- The change is one concern per PR; unrelated reformatting is a finding.

## Skip
- Style nits that Checkstyle does not enforce, naming taste, and comment wording.
