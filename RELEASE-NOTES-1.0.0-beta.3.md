# FarmGuard 1.0.0-beta.3

**This is a Beta release. It is not Production Stable. It is not Production Ready.**

FarmGuard 1.0.0-beta.3 adds runtime language switching and refines administrator diagnostics on top of 1.0.0-beta.2. It does **not** change core farm-detection, Risk, Protection, LagCorrelation, ServerPressure, or Cluster algorithms.

Use this build on a test server or a small/medium Paper server with an administrator watching.

## Added

- Simplified Chinese / English runtime language switching (`/fg language zh_CN|en_US`)
- Chunk-load context in the Debug Diagnostic Log (`chunkLoads` / `chunkUnloads` / `newChunkLoads` on `server_snapshot`)

## Improved

- `/fg top` Cluster-first presentation (one display entry per AutomationCluster)
- `/fg inspect` readability
- Neutral risk wording in administrator UI
- Low-level Risk transition aggregation in Debug Log (`risk_transition_summary` for NONE/LOW/MEDIUM flaps)

HIGH / CRITICAL risk transitions are still written immediately as `risk_transition`. Debug Log machine fields stay English enums and are not localized.

## What did not change

Core scoring and protection logic from 1.0.0-beta.2 / 1.0.0-beta.1 is unchanged. This cut does not claim improved lag detection, Risk accuracy, or Protection accuracy. The 2 hour soak was not re-run.

## Install

1. Build with Java 21: `gradlew.bat clean build`
2. Place `FarmGuard-1.0.0-beta.3.jar` in `plugins/`
3. Start Paper 1.21.x and confirm `FarmGuard by NPUcraft` / `v1.0.0-beta.3`
4. Default UI language is `zh_CN`. Switch with `/fg language en_US` if needed.

Do not overwrite a previously published `FarmGuard-1.0.0-beta.1.jar` or `FarmGuard-1.0.0-beta.2.jar`.
