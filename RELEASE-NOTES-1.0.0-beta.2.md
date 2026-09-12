# FarmGuard 1.0.0-beta.2

**This is a Beta release. It is not Production Stable. It is not Production Ready.**

FarmGuard 1.0.0-beta.2 adds an optional Debug Diagnostic Log on top of 1.0.0-beta.1. It does **not** add new farm-detection capability. Core Risk, Protection, Correlation, Metrics, and scheduler behavior are unchanged.

Use this build on a test server or a small/medium Paper server with an administrator watching.

## What is new

Optional structured Debug Diagnostic Log:

- JSON Lines (`schema: 1`) at `plugins/FarmGuard/logs/debug.log`
- Periodic server / hotspot / cluster snapshots
- Pressure / Risk / Correlation / Protection transitions
- Protection activity aggregation
- Incident / recovery diagnostics
- Async bounded writer with rotation
- Enable or disable at runtime with `/fg reload`

**Default: OFF.**

## Enable Debug Diagnostic Log

In `plugins/FarmGuard/config.yml`:

```yaml
debug-log:
  enabled: true
```

Then run `/fg reload`. No server restart is required.

Path:

```
plugins/FarmGuard/logs/debug.log
```

This is an aggregated diagnostic log, not a per-event Minecraft trace.

## How to submit logs

If you see unusual lag, false positives, unexpected throttling, slow recovery, or cannot identify a hotspot:

1. Enable `debug-log`
2. `/fg reload`
3. Reproduce the problem
4. Keep at least 5–15 minutes around the incident
5. Provide `debug.log`
6. If rotation already happened, also provide adjacent files such as `debug.1.log`

Do **not** upload the entire server world. World name and chunk coordinates are enough when you know them. Also include the approximate time, Paper version, and FarmGuard mode (MONITOR / PROTECT).

Do not include player IPs, chat, passwords, or tokens.

## What was verified for Beta.2

Debug Log only (this cut did **not** re-run the 2 hour soak):

- JUnit, including JSONL / rotation / shutdown / disk-failure / writer-thread cycling
- Full JSON Lines validation of real `debug.log`
- Paper 1.21.8 integration smoke: startup, MONITOR, `/fg status`, Debug OFF → ON via reload, snapshots, hopper 64 integrity, Debug OFF reload, clean stop
- Five OFF → ON → OFF reload cycles without extra `FarmGuard-DebugLog` threads
- Short Debug OFF / Debug ON activity with hopper, redstone, bot interaction, and a controlled HIGH window
- Short JFR: Debug Log file IO was not observed on the Minecraft Server thread

Core Risk / Protection / Correlation still rest on the 1.0.0-beta.1 Phase 16 gate, including the 2 hour soak. See [RELEASE-NOTES-1.0.0-beta.1.md](RELEASE-NOTES-1.0.0-beta.1.md).

## Known Beta limitations

Unchanged from 1.0.0-beta.1:

- Chunk-level protection, not per-machine outlines
- Estimated entity density, not an exact census
- Approximate cluster boundaries
- LagCorrelation is statistical, not causal
- No Folia
- Redstone automatic suppression currently has limited effectiveness against some observer-clock patterns
- Piston EMERGENCY suppression coverage is less extensive than hopper protection coverage

## Install

1. Java 21, Paper 1.21.8 (or a close 1.21.x Paper build)
2. Place `FarmGuard-1.0.0-beta.2.jar` in `plugins/`
3. Confirm `FarmGuard enabled successfully` and start in MONITOR unless you persist PROTECT
4. Leave `debug-log.enabled` false unless you are collecting diagnostics
