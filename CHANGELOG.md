# Changelog

All notable changes to FarmGuard are documented in this file.

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
