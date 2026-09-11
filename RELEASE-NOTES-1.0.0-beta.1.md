# FarmGuard 1.0.0-beta.1

**This is a Beta release. It is not Production Stable.**

FarmGuard 1.0.0-beta.1 is intended for:

- Test servers
- Small or medium Paper servers with an administrator watching
- Controlled environments where `/fg status`, `/fg top`, and `/fg limits` can be checked after lag events

It is **not** claimed to be production-ready, 100% safe, or suitable for every server. Folia is not supported.

## What was verified

On **Paper 1.21.8** (build 60) with Java 21:

- MONITOR safety for bot player interactions (button, lever, door, trapdoor, repeater, comparator, hopper, minecart, item, breeding)
- SpawnReason handling: COMMAND / NATURAL / SPAWNER
- Hopper item conservation (NORMAL / THROTTLE / EMERGENCY / recovery)
- Real Paper HIGH and CRITICAL server pressure (production thresholds 48 ms / 65 ms, not lowered)
- LagCorrelation temporal behavior (hold evidence during sustained pressure; Farm A does not inherit STRONG)
- THROTTLE under correlated HIGH
- EMERGENCY reachability under CRITICAL + STRONG correlation
- Automatic recovery after pressure stops
- Protect → Monitor immediately releasing restrictions
- JFR load profiling (FarmGuard sample share well below TestProbe burner)
- 2 hour continuous soak (wall clock ≥ 7200 s after setup)
- Restart persistence (graceful stop, cold start, readable `state.yml` / `incidents.yml`, isolated 64-item hopper)

Pressure used in the gate is **synthetic controlled tick pressure** (FarmGuardTestProbe). Do not read that as “a vanilla farm caused 65 ms MSPT”.

## Known Beta limitations

- Chunk-level protection, not per-machine outlines
- Estimated entity density, not an exact census
- Approximate cluster boundaries
- LagCorrelation is statistical, not causal
- analysis / census budgets cap how much is scored per tick
- No Folia
- Item throttle is OFF by default
- Redstone automatic suppression currently has limited effectiveness against some observer-clock patterns. FarmGuard does **not** freeze every high-frequency redstone circuit. This is an effectiveness issue, not a recorded safety failure (doors and inventories were not corrupted in the gate)
- Piston EMERGENCY suppression coverage is less extensive than hopper protection coverage
- `/fg reload` does not overwrite persisted `state.yml` mode / whitelist

### Testing limitation (not a product limitation)

FPP console `/fpp spawn` is player-only. The test environment uses a Mineflayer player to spawn FPP.

## Install

1. Java 21, Paper 1.21.8 (or a close 1.21.x Paper build)
2. Place `FarmGuard-1.0.0-beta.1.jar` in `plugins/`
3. Confirm `FarmGuard enabled successfully` and start in MONITOR unless you persist PROTECT
4. Watch `/fg status` during the first real lag events before leaving PROTECT unattended
