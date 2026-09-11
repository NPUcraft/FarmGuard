# Changelog

All notable changes to FarmGuard are documented in this file.

## 1.0.0-beta.1

First controlled beta after the Phase 16 automated gate (Paper 1.21.8).

This is a **Beta** release, not Production Stable. Use on a test server or a small/medium server with an admin watching.

Verified on Paper 1.21.8: MONITOR safety, bot player interactions, SpawnReason handling, hopper integrity, HIGH / CRITICAL pressure, LagCorrelation temporal hold, THROTTLE, EMERGENCY reachability, automatic recovery, Protect → Monitor, JFR load share, 2 hour soak, restart persistence.

Known limitations remain: chunk-level protection, estimated entity density, statistical (not causal) lag correlation, no Folia, item throttle off by default, limited redstone observer-clock suppression, thinner piston EMERGENCY coverage than hopper protection, `/fg reload` does not overwrite persisted mode/whitelist.

## 0.1.0-SNAPSHOT

Initial V1 development snapshot, plus a production-readiness hardening pass:

- Conservative protection gates (MONITOR immediate release, emergency-only redstone/piston/item/minecart)
- Bounded metric maps, census budget, analysis budget, notification merge
- Safer token buckets, lag co-movement, cluster family merge, corrupt YAML quarantine
- Upgrade-safe messages.yml merge (missing keys only) and a cap of 8 corrupt YAML backups
- Skip rewriting an existing messages.yml on enable (avoids a Paper warning every boot)

- Event-driven chunk activity monitoring
- Rolling-window metrics and hotspot ranking
- Risk scoring with server-pressure separation
- Basic lag correlation and automation clusters
- Non-destructive protection with MONITOR / PROTECT modes
- Admin commands, whitelist, notifications, and incident history
